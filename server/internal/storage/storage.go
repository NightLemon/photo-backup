package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/NightLemon/photo-backup/server/internal/store"
)

type Manager struct {
	root       string
	stagingDir string
}

func New(root string) (*Manager, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	abs = filepath.Clean(abs)
	if err := os.MkdirAll(abs, 0750); err != nil {
		return nil, err
	}
	resolvedRoot, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, fmt.Errorf("resolve storage root: %w", err)
	}
	m := &Manager{root: filepath.Clean(resolvedRoot), stagingDir: filepath.Join(resolvedRoot, ".photobackup-staging")}
	if err := os.MkdirAll(m.stagingDir, 0750); err != nil {
		return nil, err
	}
	resolvedStaging, err := filepath.EvalSymlinks(m.stagingDir)
	if err != nil || !within(m.root, resolvedStaging) {
		return nil, errors.New("staging directory escapes storage root")
	}
	m.stagingDir = filepath.Clean(resolvedStaging)
	probe, err := os.CreateTemp(m.stagingDir, ".write-test-*")
	if err != nil {
		return nil, fmt.Errorf("storage root is not writable: %w", err)
	}
	probePath := probe.Name()
	if err := probe.Close(); err != nil {
		os.Remove(probePath)
		return nil, fmt.Errorf("close storage write test: %w", err)
	}
	if err := os.Remove(probePath); err != nil {
		return nil, fmt.Errorf("remove storage write test: %w", err)
	}
	return m, nil
}

func (m *Manager) Root() string { return m.root }

func (m *Manager) NewTempPath() string {
	return filepath.Join(m.stagingDir, store.NewID()+".part")
}

func (m *Manager) EnsureTemp(path string) error {
	if !m.IsStagingPath(path) {
		return errors.New("invalid staging path")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	return f.Close()
}

func (m *Manager) IsStagingPath(path string) bool {
	abs, err := filepath.Abs(path)
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(m.stagingDir, abs)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func (m *Manager) FinalPath(device store.Device, upload store.Upload, hash string) (string, error) {
	deviceName := safeSegment(device.Name)
	shortID := device.ID
	if len(shortID) > 8 {
		shortID = shortID[:8]
	}
	deviceDir := deviceName + "_" + shortID
	parts := safeRelativeParts(upload.RelativePath)
	if len(parts) == 0 {
		stamp := time.UnixMilli(upload.CapturedAt)
		if upload.CapturedAt <= 0 {
			stamp = time.UnixMilli(upload.ModifiedAt)
		}
		parts = []string{"Unsorted", stamp.Format("2006"), stamp.Format("01")}
	}
	dir := filepath.Join(append([]string{m.root, deviceDir}, parts...)...)
	name := safeSegment(upload.DisplayName)
	if name == "_" {
		name = "media"
	}
	path := filepath.Join(dir, name)
	if !within(m.root, path) {
		return "", errors.New("resolved media path escapes storage root")
	}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		return path, nil
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	shortHash := hash
	if len(shortHash) > 8 {
		shortHash = shortHash[:8]
	}
	for i := 0; i < 10000; i++ {
		suffix := "~" + shortHash
		if i > 0 {
			suffix += fmt.Sprintf("-%d", i)
		}
		candidate := filepath.Join(dir, base+suffix+ext)
		if _, err := os.Stat(candidate); errors.Is(err, os.ErrNotExist) {
			return candidate, nil
		}
	}
	return "", errors.New("too many colliding file names")
}

func (m *Manager) MoveIntoPlace(tempPath, finalPath string, modifiedAt int64) error {
	if !m.IsStagingPath(tempPath) || !within(m.root, finalPath) {
		return errors.New("refusing to move outside managed storage")
	}
	finalDir := filepath.Dir(finalPath)
	if err := os.MkdirAll(finalDir, 0750); err != nil {
		return err
	}
	resolvedDir, err := filepath.EvalSymlinks(finalDir)
	if err != nil || !within(m.root, resolvedDir) {
		return errors.New("final directory escapes storage root")
	}
	if err := os.Rename(tempPath, finalPath); err != nil {
		return err
	}
	if modifiedAt > 0 {
		stamp := time.UnixMilli(modifiedAt)
		_ = os.Chtimes(finalPath, stamp, stamp)
	}
	return nil
}

func HashFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", n, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

func SyncFile(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func within(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

var invalidChars = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]`)

func safeRelativeParts(path string) []string {
	path = strings.ReplaceAll(path, "\\", "/")
	var result []string
	for _, raw := range strings.Split(path, "/") {
		if raw == "" || raw == "." || raw == ".." {
			continue
		}
		result = append(result, safeSegment(raw))
	}
	return result
}

func safeSegment(value string) string {
	value = invalidChars.ReplaceAllString(strings.TrimSpace(value), "_")
	value = strings.TrimRight(value, ". ")
	if value == "" {
		value = "_"
	}
	if len(value) > 120 {
		ext := filepath.Ext(value)
		base := strings.TrimSuffix(value, ext)
		limit := 120 - len(ext)
		if limit < 1 {
			limit = 1
		}
		base = truncateUTF8(base, limit)
		ext = truncateUTF8(ext, 119-len(base))
		value = base + ext
	}
	upper := strings.ToUpper(strings.TrimSuffix(value, filepath.Ext(value)))
	reserved := map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true}
	if reserved[upper] || (len(upper) == 4 && (strings.HasPrefix(upper, "COM") || strings.HasPrefix(upper, "LPT")) && upper[3] >= '1' && upper[3] <= '9') {
		value = "_" + value
	}
	return value
}

func truncateUTF8(value string, maxBytes int) string {
	if maxBytes < 1 {
		return ""
	}
	if len(value) <= maxBytes {
		return value
	}
	end := 0
	for index := range value {
		if index > maxBytes {
			break
		}
		end = index
	}
	if end == 0 {
		return ""
	}
	return value[:end]
}
