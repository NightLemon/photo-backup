package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/NightLemon/photo-backup/server/internal/config"
	"github.com/NightLemon/photo-backup/server/internal/diskspace"
	"github.com/NightLemon/photo-backup/server/internal/storage"
	"github.com/NightLemon/photo-backup/server/internal/store"
)

//go:embed web/index.html
var webFiles embed.FS

type Server struct {
	cfg        config.Config
	store      *store.Store
	storage    *storage.Manager
	spkiPin    string
	logger     *slog.Logger
	pairings   *pairingManager
	locks      sync.Map
	storageMu  sync.RWMutex
	finalizeMu sync.Mutex
}

type contextKey string

const deviceContextKey contextKey = "device"

type pairingPayload struct {
	Version       int      `json:"version"`
	ServerID      string   `json:"serverId"`
	ServerName    string   `json:"serverName"`
	Port          int      `json:"port"`
	Addresses     []string `json:"addresses"`
	TLSSPKISHA256 string   `json:"tlsSpkiSha256"`
	PairSecret    string   `json:"pairSecret"`
	ExpiresAt     int64    `json:"expiresAt"`
}

type prepareResponse struct {
	Status         string       `json:"status"`
	UploadID       string       `json:"uploadId,omitempty"`
	ChunkSize      int64        `json:"chunkSize,omitempty"`
	ReceivedChunks []int        `json:"receivedChunks,omitempty"`
	Receipt        *store.Asset `json:"receipt,omitempty"`
}

func New(cfg config.Config, db *store.Store, files *storage.Manager, spkiPin string, logger *slog.Logger) *Server {
	if logger == nil {
		logger = slog.Default()
	}
	return &Server{cfg: cfg, store: db, storage: files, spkiPin: spkiPin, logger: logger, pairings: newPairingManager()}
}

func (s *Server) APIHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.health)
	mux.HandleFunc("POST /api/v1/pair", s.pair)
	mux.Handle("POST /api/v1/uploads/prepare", s.authenticate(http.HandlerFunc(s.prepare)))
	mux.Handle("PUT /api/v1/uploads/{id}/chunks/{index}", s.authenticate(http.HandlerFunc(s.putChunk)))
	mux.Handle("POST /api/v1/uploads/{id}/finalize", s.authenticate(http.HandlerFunc(s.finalize)))
	return s.withAPIHeaders(mux)
}

func (s *Server) AdminHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", s.adminIndex)
	mux.HandleFunc("GET /admin/status", s.adminStatus)
	mux.HandleFunc("POST /admin/pairing", s.adminPairing)
	mux.HandleFunc("PUT /admin/storage", s.adminStorage)
	mux.HandleFunc("GET /admin/devices", s.adminDevices)
	mux.HandleFunc("POST /admin/devices/{id}/revoke", s.adminRevoke)
	return s.adminOnly(mux)
}

func (s *Server) RecoverFinalizing(ctx context.Context) error {
	s.storageMu.RLock()
	defer s.storageMu.RUnlock()
	uploads, err := s.store.ListFinalizing(ctx)
	if err != nil {
		return err
	}
	for _, upload := range uploads {
		if _, err := os.Stat(upload.FinalPath); err == nil {
			if _, completeErr := s.store.CompleteUpload(ctx, upload.ID); completeErr != nil {
				return completeErr
			}
			continue
		}
		if _, err := os.Stat(upload.TempPath); err == nil {
			if err := s.storage.MoveIntoPlace(upload.TempPath, upload.FinalPath, upload.ModifiedAt); err != nil {
				return err
			}
			if _, err := s.store.CompleteUpload(ctx, upload.ID); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "serverId": s.cfg.ServerID, "apiVersion": 1})
}

func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Secret     string `json:"secret"`
		DeviceName string `json:"deviceName"`
	}
	if err := decodeJSON(w, r, &request, 8<<10); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	request.DeviceName = strings.TrimSpace(request.DeviceName)
	if len(request.DeviceName) < 1 || len(request.DeviceName) > 100 || !s.pairings.consume(request.Secret) {
		writeError(w, http.StatusUnauthorized, "invalid or expired pairing secret")
		return
	}
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		writeError(w, http.StatusInternalServerError, "could not create device credential")
		return
	}
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	hash := sha256.Sum256([]byte(token))
	device, err := s.store.CreateDevice(r.Context(), request.DeviceName, hash[:])
	if err != nil {
		s.logger.Error("create device", "error", err)
		writeError(w, http.StatusInternalServerError, "could not pair device")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"deviceId": device.ID, "deviceName": device.Name, "token": token, "serverId": s.cfg.ServerID,
	})
}

func (s *Server) prepare(w http.ResponseWriter, r *http.Request) {
	s.storageMu.RLock()
	defer s.storageMu.RUnlock()
	device := deviceFrom(r.Context())
	var media store.MediaMetadata
	if err := decodeJSON(w, r, &media, 32<<10); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := validateMedia(media); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	result, err := s.store.PrepareUpload(r.Context(), device.ID, media, s.storage.NewTempPath())
	if err != nil {
		s.logger.Error("prepare upload", "error", err, "device", device.ID)
		writeError(w, http.StatusInternalServerError, "could not prepare upload")
		return
	}
	if result.Asset != nil {
		writeJSON(w, http.StatusOK, prepareResponse{Status: "complete", Receipt: result.Asset})
		return
	}
	if result.Upload.State == "uploading" {
		if err := s.storage.EnsureTemp(result.Upload.TempPath); err != nil {
			s.logger.Error("create staging file", "error", err)
			writeError(w, http.StatusInsufficientStorage, "could not create staging file")
			return
		}
	}
	writeJSON(w, http.StatusOK, prepareResponse{
		Status: "upload", UploadID: result.Upload.ID, ChunkSize: result.Upload.ChunkSize,
		ReceivedChunks: result.ReceivedChunks,
	})
}

func (s *Server) putChunk(w http.ResponseWriter, r *http.Request) {
	s.storageMu.RLock()
	defer s.storageMu.RUnlock()
	device := deviceFrom(r.Context())
	uploadID := r.PathValue("id")
	index, err := strconv.Atoi(r.PathValue("index"))
	if err != nil || index < 0 {
		writeError(w, http.StatusBadRequest, "invalid chunk index")
		return
	}
	lock := s.uploadLock(uploadID)
	lock.Lock()
	defer lock.Unlock()
	upload, err := s.store.GetUpload(r.Context(), uploadID, device.ID)
	if err != nil || upload.State != "uploading" {
		writeError(w, http.StatusNotFound, "active upload not found")
		return
	}
	expected, offset, ok := expectedChunk(upload, index)
	if !ok {
		writeError(w, http.StatusBadRequest, "chunk index is outside file")
		return
	}
	if r.ContentLength != expected {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("chunk must contain exactly %d bytes", expected))
		return
	}
	wantHash := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Chunk-SHA256")))
	decodedHash, err := hex.DecodeString(wantHash)
	if err != nil || len(decodedHash) != sha256.Size {
		writeError(w, http.StatusBadRequest, "invalid X-Chunk-SHA256")
		return
	}
	if free, err := diskspace.Free(s.storage.Root()); err == nil && free < uint64(expected)+(16<<20) {
		writeError(w, http.StatusInsufficientStorage, "not enough free disk space")
		return
	}
	f, err := os.OpenFile(upload.TempPath, os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		writeError(w, http.StatusInsufficientStorage, "could not open staging file")
		return
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		writeError(w, http.StatusInternalServerError, "could not seek staging file")
		return
	}
	h := sha256.New()
	n, err := io.CopyN(io.MultiWriter(f, h), r.Body, expected)
	if err != nil || n != expected {
		writeError(w, http.StatusBadRequest, "incomplete chunk")
		return
	}
	gotHash := h.Sum(nil)
	if subtle.ConstantTimeCompare(gotHash, decodedHash) != 1 {
		writeError(w, http.StatusUnprocessableEntity, "chunk checksum mismatch")
		return
	}
	if err := f.Sync(); err != nil {
		writeError(w, http.StatusInsufficientStorage, "could not persist chunk")
		return
	}
	if err := s.store.RecordChunk(r.Context(), upload.ID, index, wantHash, expected); err != nil {
		s.logger.Error("record chunk", "error", err, "upload", upload.ID)
		writeError(w, http.StatusInternalServerError, "could not record chunk")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"received": index})
}

func (s *Server) finalize(w http.ResponseWriter, r *http.Request) {
	s.storageMu.RLock()
	defer s.storageMu.RUnlock()
	device := deviceFrom(r.Context())
	uploadID := r.PathValue("id")
	lock := s.uploadLock(uploadID)
	lock.Lock()
	defer lock.Unlock()
	upload, err := s.store.GetUpload(r.Context(), uploadID, device.ID)
	if err != nil {
		writeError(w, http.StatusNotFound, "upload not found")
		return
	}
	if upload.State == "finalizing" {
		s.finalizeMu.Lock()
		defer s.finalizeMu.Unlock()
		asset, err := s.finishInterrupted(r.Context(), upload)
		if err != nil {
			s.logger.Error("resume finalization", "error", err, "upload", upload.ID)
			writeError(w, http.StatusInternalServerError, "could not finish upload")
			return
		}
		writeJSON(w, http.StatusOK, asset)
		return
	}
	if upload.State != "uploading" {
		writeError(w, http.StatusConflict, "upload cannot be finalized")
		return
	}
	count, err := s.store.ChunkCount(r.Context(), upload.ID)
	expectedCount := chunkCount(upload.ByteLength, upload.ChunkSize)
	if err != nil || count != expectedCount {
		writeError(w, http.StatusConflict, "not all chunks have been received")
		return
	}
	if err := storage.SyncFile(upload.TempPath); err != nil {
		writeError(w, http.StatusInsufficientStorage, "could not persist upload")
		return
	}
	hash, size, err := storage.HashFile(upload.TempPath)
	if err != nil || size != upload.ByteLength {
		writeError(w, http.StatusUnprocessableEntity, "staged file failed final verification")
		return
	}
	s.finalizeMu.Lock()
	defer s.finalizeMu.Unlock()
	finalPath, err := s.storage.FinalPath(device, upload, hash)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not choose final path")
		return
	}
	assetID := store.NewID()
	if err := s.store.SetFinalizing(r.Context(), upload.ID, assetID, finalPath, hash); err != nil {
		writeError(w, http.StatusInternalServerError, "could not commit upload")
		return
	}
	upload.State, upload.AssetID, upload.FinalPath, upload.SHA256 = "finalizing", assetID, finalPath, hash
	asset, err := s.finishInterrupted(r.Context(), upload)
	if err != nil {
		s.logger.Error("finalize upload", "error", err, "upload", upload.ID)
		writeError(w, http.StatusInternalServerError, "could not finish upload")
		return
	}
	writeJSON(w, http.StatusOK, asset)
}

func (s *Server) finishInterrupted(ctx context.Context, upload store.Upload) (store.Asset, error) {
	if _, err := os.Stat(upload.FinalPath); errors.Is(err, os.ErrNotExist) {
		if err := s.storage.MoveIntoPlace(upload.TempPath, upload.FinalPath, upload.ModifiedAt); err != nil {
			return store.Asset{}, err
		}
	} else if err != nil {
		return store.Asset{}, err
	} else {
		hash, size, err := storage.HashFile(upload.FinalPath)
		if err != nil {
			return store.Asset{}, err
		}
		if size != upload.ByteLength || !strings.EqualFold(hash, upload.SHA256) {
			return store.Asset{}, errors.New("existing final file does not match the verified upload")
		}
	}
	return s.store.CompleteUpload(ctx, upload.ID)
}

func (s *Server) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") || len(header) > 512 {
			writeError(w, http.StatusUnauthorized, "device authentication required")
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		hash := sha256.Sum256([]byte(token))
		device, err := s.store.Authenticate(r.Context(), hash[:])
		if err != nil {
			writeError(w, http.StatusUnauthorized, "invalid or revoked device credential")
			return
		}
		ctx := context.WithValue(r.Context(), deviceContextKey, device)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) adminIndex(w http.ResponseWriter, _ *http.Request) {
	data, err := webFiles.ReadFile("web/index.html")
	if err != nil {
		http.Error(w, "dashboard unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(data)
}

func (s *Server) adminStatus(w http.ResponseWriter, r *http.Request) {
	s.storageMu.RLock()
	defer s.storageMu.RUnlock()
	stats, err := s.store.Stats(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read status")
		return
	}
	free, _ := diskspace.Free(s.storage.Root())
	writeJSON(w, http.StatusOK, map[string]any{
		"serverId": s.cfg.ServerID, "serverName": s.cfg.ServerName, "storageRoot": s.storage.Root(),
		"apiPort": s.cfg.APIPort, "tlsPin": s.spkiPin, "freeBytes": free, "stats": stats,
	})
}

func (s *Server) adminStorage(w http.ResponseWriter, r *http.Request) {
	var request struct {
		StorageRoot string `json:"storageRoot"`
	}
	if err := decodeJSON(w, r, &request, 8<<10); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	request.StorageRoot = strings.TrimSpace(request.StorageRoot)
	if request.StorageRoot == "" || !filepath.IsAbs(request.StorageRoot) {
		writeError(w, http.StatusBadRequest, "storageRoot must be an absolute path")
		return
	}

	s.storageMu.Lock()
	defer s.storageMu.Unlock()
	candidate := s.cfg
	candidate.StorageRoot = request.StorageRoot
	if err := candidate.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	previousRoot := s.storage.Root()
	stats, err := s.store.Stats(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read upload status")
		return
	}
	if candidate.StorageRoot == previousRoot {
		writeJSON(w, http.StatusOK, map[string]any{
			"storageRoot": previousRoot, "previousStorageRoot": previousRoot,
			"activeUploads": stats.ActiveCount, "historyMoved": false,
		})
		return
	}
	if stats.ActiveCount > 0 {
		writeJSON(w, http.StatusConflict, map[string]any{
			"error":         "storage root cannot be changed while uploads are active",
			"activeUploads": stats.ActiveCount,
		})
		return
	}
	files, err := storage.New(candidate.StorageRoot)
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}
	candidate.StorageRoot = files.Root()
	if err := candidate.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := config.Save(candidate); err != nil {
		s.logger.Error("save storage root", "error", err)
		writeError(w, http.StatusInternalServerError, "could not save storage root")
		return
	}
	s.cfg.StorageRoot = candidate.StorageRoot
	s.storage = files
	writeJSON(w, http.StatusOK, map[string]any{
		"storageRoot": files.Root(), "previousStorageRoot": previousRoot,
		"activeUploads": 0, "historyMoved": false,
	})
}

func (s *Server) adminPairing(w http.ResponseWriter, _ *http.Request) {
	secret, expiry := s.pairings.create(5 * time.Minute)
	payload := pairingPayload{
		Version: 1, ServerID: s.cfg.ServerID, ServerName: s.cfg.ServerName, Port: s.cfg.APIPort,
		Addresses: localAddresses(), TLSSPKISHA256: s.spkiPin, PairSecret: secret, ExpiresAt: expiry.UnixMilli(),
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not create pairing payload")
		return
	}
	png, err := qrcode.Encode(string(encoded), qrcode.Medium, 320)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not create QR code")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"payload": payload, "qrDataUrl": "data:image/png;base64," + base64.StdEncoding.EncodeToString(png),
	})
}

func (s *Server) adminDevices(w http.ResponseWriter, r *http.Request) {
	devices, err := s.store.ListDevices(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not list devices")
		return
	}
	writeJSON(w, http.StatusOK, devices)
}

func (s *Server) adminRevoke(w http.ResponseWriter, r *http.Request) {
	if err := s.store.RevokeDevice(r.Context(), r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "device not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) adminOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
			http.Error(w, "local access only", http.StatusForbidden)
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" && origin != fmt.Sprintf("http://127.0.0.1:%d", s.cfg.AdminPort) && origin != fmt.Sprintf("http://localhost:%d", s.cfg.AdminPort) {
			http.Error(w, "invalid origin", http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) withAPIHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) uploadLock(id string) *sync.Mutex {
	lock, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	return lock.(*sync.Mutex)
}

func deviceFrom(ctx context.Context) store.Device { return ctx.Value(deviceContextKey).(store.Device) }

func validateMedia(media store.MediaMetadata) error {
	if media.MediaKey == "" || len(media.MediaKey) > 512 {
		return errors.New("invalid mediaKey")
	}
	if media.DisplayName == "" || len(media.DisplayName) > 512 || len(media.RelativePath) > 2048 || len(media.MIMEType) > 200 {
		return errors.New("invalid media metadata")
	}
	if media.ByteLength < 0 || media.ModifiedAt < 0 || media.CapturedAt < 0 {
		return errors.New("invalid media size or timestamp")
	}
	return nil
}

func expectedChunk(upload store.Upload, index int) (length, offset int64, ok bool) {
	count := chunkCount(upload.ByteLength, upload.ChunkSize)
	if int64(index) >= count {
		return 0, 0, false
	}
	offset = int64(index) * upload.ChunkSize
	length = upload.ChunkSize
	if remaining := upload.ByteLength - offset; remaining < length {
		length = remaining
	}
	return length, offset, true
}

func chunkCount(size, chunkSize int64) int64 {
	if size == 0 {
		return 0
	}
	return (size + chunkSize - 1) / chunkSize
}

func localAddresses() []string {
	interfaces, _ := net.Interfaces()
	var addresses []string
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		items, _ := iface.Addrs()
		for _, item := range items {
			var ip net.IP
			switch value := item.(type) {
			case *net.IPNet:
				ip = value.IP
			case *net.IPAddr:
				ip = value.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			if v4 := ip.To4(); v4 != nil {
				addresses = append(addresses, v4.String())
			}
		}
	}
	return addresses
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any, limit int64) error {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request must contain one JSON object")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

type pairingManager struct {
	mu      sync.Mutex
	entries map[[32]byte]time.Time
}

func newPairingManager() *pairingManager {
	return &pairingManager{entries: make(map[[32]byte]time.Time)}
}

func (m *pairingManager) create(ttl time.Duration) (string, time.Time) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	secret := base64.RawURLEncoding.EncodeToString(b)
	hash := sha256.Sum256([]byte(secret))
	expiry := time.Now().Add(ttl)
	m.mu.Lock()
	defer m.mu.Unlock()
	for key, value := range m.entries {
		if time.Now().After(value) {
			delete(m.entries, key)
		}
	}
	m.entries[hash] = expiry
	return secret, expiry
}

func (m *pairingManager) consume(secret string) bool {
	hash := sha256.Sum256([]byte(secret))
	m.mu.Lock()
	defer m.mu.Unlock()
	expiry, ok := m.entries[hash]
	delete(m.entries, hash)
	return ok && time.Now().Before(expiry)
}
