package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/NightLemon/photo-backup/server/internal/config"
	"github.com/NightLemon/photo-backup/server/internal/storage"
	"github.com/NightLemon/photo-backup/server/internal/store"
)

func TestPairUploadResumeAndIdempotency(t *testing.T) {
	root := t.TempDir()
	db, err := store.Open(filepath.Join(root, "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	files, err := storage.New(filepath.Join(root, "media"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{ServerID: "server-test", ServerName: "Test", StorageRoot: files.Root(), StateDir: root, APIPort: 5443, AdminPort: 5444}
	server := New(cfg, db, files, "sha256/test", slog.New(slog.NewTextHandler(io.Discard, nil)))

	adminRecorder := httptest.NewRecorder()
	adminRequest := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:5444/admin/pairing", nil)
	adminRequest.RemoteAddr = "127.0.0.1:12345"
	server.AdminHandler().ServeHTTP(adminRecorder, adminRequest)
	if adminRecorder.Code != http.StatusOK {
		t.Fatalf("pairing payload: %d %s", adminRecorder.Code, adminRecorder.Body.String())
	}
	var pairing struct {
		Payload pairingPayload `json:"payload"`
	}
	decode(t, adminRecorder.Body.Bytes(), &pairing)

	pairBody := mustJSON(t, map[string]any{"secret": pairing.Payload.PairSecret, "deviceName": "测试手机"})
	pairResponse := perform(server.APIHandler(), http.MethodPost, "/api/v1/pair", pairBody, "", "")
	if pairResponse.Code != http.StatusCreated {
		t.Fatalf("pair: %d %s", pairResponse.Code, pairResponse.Body.String())
	}
	var credentials struct {
		Token    string `json:"token"`
		DeviceID string `json:"deviceId"`
	}
	decode(t, pairResponse.Body.Bytes(), &credentials)

	content := bytes.Repeat([]byte("photo-backup-content-"), 500000)
	media := store.MediaMetadata{
		MediaKey: "external:42", DisplayName: "旅行?.mp4", RelativePath: "DCIM/../Camera/",
		MIMEType: "video/mp4", ByteLength: int64(len(content)), ModifiedAt: 1700000000000, CapturedAt: 1699999999000,
	}
	prepare := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/prepare", mustJSON(t, media), credentials.Token, "")
	if prepare.Code != http.StatusOK {
		t.Fatalf("prepare: %d %s", prepare.Code, prepare.Body.String())
	}
	var prepared prepareResponse
	decode(t, prepare.Body.Bytes(), &prepared)
	if prepared.Status != "upload" || prepared.UploadID == "" {
		t.Fatalf("unexpected prepare response: %+v", prepared)
	}

	for index, offset := 0, int64(0); offset < int64(len(content)); index, offset = index+1, offset+prepared.ChunkSize {
		end := offset + prepared.ChunkSize
		if end > int64(len(content)) {
			end = int64(len(content))
		}
		chunk := content[offset:end]
		hash := sha256.Sum256(chunk)
		if index == 0 {
			bad := perform(server.APIHandler(), http.MethodPut, "/api/v1/uploads/"+prepared.UploadID+"/chunks/0", chunk, credentials.Token, strings.Repeat("0", 64))
			if bad.Code != http.StatusUnprocessableEntity {
				t.Fatalf("corrupt chunk was accepted: %d %s", bad.Code, bad.Body.String())
			}
		}
		response := perform(server.APIHandler(), http.MethodPut, "/api/v1/uploads/"+prepared.UploadID+"/chunks/"+strconv.Itoa(index), chunk, credentials.Token, hex.EncodeToString(hash[:]))
		if response.Code != http.StatusOK {
			t.Fatalf("chunk %d: %d %s", index, response.Code, response.Body.String())
		}
		if index == 0 {
			resumed := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/prepare", mustJSON(t, media), credentials.Token, "")
			var state prepareResponse
			decode(t, resumed.Body.Bytes(), &state)
			if len(state.ReceivedChunks) != 1 || state.ReceivedChunks[0] != 0 {
				t.Fatalf("resume did not report first chunk: %+v", state)
			}
		}
	}

	finalized := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/"+prepared.UploadID+"/finalize", nil, credentials.Token, "")
	if finalized.Code != http.StatusOK {
		t.Fatalf("finalize: %d %s", finalized.Code, finalized.Body.String())
	}
	var receipt store.Asset
	decode(t, finalized.Body.Bytes(), &receipt)
	fullHash := sha256.Sum256(content)
	if receipt.SHA256 != hex.EncodeToString(fullHash[:]) {
		t.Fatalf("wrong receipt hash: %s", receipt.SHA256)
	}

	retry := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/prepare", mustJSON(t, media), credentials.Token, "")
	var completed prepareResponse
	decode(t, retry.Body.Bytes(), &completed)
	if completed.Status != "complete" || completed.Receipt == nil || completed.Receipt.ID != receipt.ID {
		t.Fatalf("prepare was not idempotent: %+v", completed)
	}

	var saved []byte
	err = filepath.WalkDir(files.Root(), func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr == nil && !entry.IsDir() && filepath.Ext(path) == ".mp4" {
			saved, walkErr = os.ReadFile(path)
		}
		return walkErr
	})
	if err != nil || !bytes.Equal(saved, content) {
		t.Fatalf("saved original does not match: error=%v bytes=%d", err, len(saved))
	}
	if err := db.RevokeDevice(context.Background(), credentials.DeviceID); err != nil {
		t.Fatal(err)
	}
	denied := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/prepare", mustJSON(t, media), credentials.Token, "")
	if denied.Code != http.StatusUnauthorized {
		t.Fatalf("revoked device was accepted: %d", denied.Code)
	}
}

func TestPairSecretIsSingleUse(t *testing.T) {
	m := newPairingManager()
	secret, _ := m.create(time.Minute)
	if !m.consume(secret) || m.consume(secret) || m.consume("wrong") {
		t.Fatal("pairing secret was not single use")
	}
}

func TestAdminStorageConfiguration(t *testing.T) {
	root := t.TempDir()
	stateDir := filepath.Join(root, "state")
	initialRoot := filepath.Join(root, "photos-initial")
	cfg, err := config.LoadOrCreate(stateDir, initialRoot, "Test")
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(filepath.Join(stateDir, "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	files, err := storage.New(initialRoot)
	if err != nil {
		t.Fatal(err)
	}
	server := New(cfg, db, files, "sha256/test", slog.New(slog.NewTextHandler(io.Discard, nil)))

	devices := performAdmin(server.AdminHandler(), http.MethodGet, "/admin/devices", nil)
	if devices.Code != http.StatusOK || strings.TrimSpace(devices.Body.String()) != "[]" {
		t.Fatalf("empty devices response: %d %q", devices.Code, devices.Body.String())
	}

	nextRoot := filepath.Join(root, "photos-next")
	updated := performAdmin(server.AdminHandler(), http.MethodPut, "/admin/storage", mustJSON(t, map[string]string{"storageRoot": nextRoot}))
	if updated.Code != http.StatusOK {
		t.Fatalf("update storage root: %d %s", updated.Code, updated.Body.String())
	}
	if server.storage.Root() != nextRoot {
		t.Fatalf("runtime storage root was not updated: %q", server.storage.Root())
	}
	loaded, err := config.LoadOrCreate(stateDir, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if loaded.StorageRoot != nextRoot {
		t.Fatalf("storage root was not persisted: got %q want %q", loaded.StorageRoot, nextRoot)
	}
	status := performAdmin(server.AdminHandler(), http.MethodGet, "/admin/status", nil)
	var statusBody struct {
		StorageRoot string `json:"storageRoot"`
	}
	decode(t, status.Body.Bytes(), &statusBody)
	if statusBody.StorageRoot != nextRoot {
		t.Fatalf("status returned stale storage root: %q", statusBody.StorageRoot)
	}

	device, err := db.CreateDevice(context.Background(), "Test phone", []byte("test-token-hash"))
	if err != nil {
		t.Fatal(err)
	}
	media := store.MediaMetadata{MediaKey: "media-1", DisplayName: "photo.jpg", MIMEType: "image/jpeg", ByteLength: 1, ModifiedAt: 1}
	if _, err := db.PrepareUpload(context.Background(), device.ID, media, server.storage.NewTempPath()); err != nil {
		t.Fatal(err)
	}
	blockedRoot := filepath.Join(root, "photos-blocked")
	blocked := performAdmin(server.AdminHandler(), http.MethodPut, "/admin/storage", mustJSON(t, map[string]string{"storageRoot": blockedRoot}))
	if blocked.Code != http.StatusConflict {
		t.Fatalf("storage root changed with an active upload: %d %s", blocked.Code, blocked.Body.String())
	}
	if server.storage.Root() != nextRoot {
		t.Fatalf("blocked update changed runtime root: %q", server.storage.Root())
	}

	unsafe := performAdmin(server.AdminHandler(), http.MethodPut, "/admin/storage", mustJSON(t, map[string]string{"storageRoot": root}))
	if unsafe.Code != http.StatusBadRequest {
		t.Fatalf("storage root containing state was accepted: %d %s", unsafe.Code, unsafe.Body.String())
	}
}

func TestConcurrentFinalizationPreservesSameNamedFiles(t *testing.T) {
	root := t.TempDir()
	db, err := store.Open(filepath.Join(root, "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	files, err := storage.New(filepath.Join(root, "media"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{ServerID: "server-test", ServerName: "Test", StorageRoot: files.Root(), StateDir: root, APIPort: 5443, AdminPort: 5444}
	server := New(cfg, db, files, "sha256/test", slog.New(slog.NewTextHandler(io.Discard, nil)))
	token := "concurrent-test-token"
	tokenHash := sha256.Sum256([]byte(token))
	if _, err := db.CreateDevice(context.Background(), "Test phone", tokenHash[:]); err != nil {
		t.Fatal(err)
	}

	contents := [][]byte{[]byte("first photo"), []byte("second photo")}
	uploadIDs := make([]string, len(contents))
	for index, content := range contents {
		media := store.MediaMetadata{
			MediaKey: "media-" + strconv.Itoa(index), DisplayName: "same-name.jpg", RelativePath: "DCIM/Camera",
			MIMEType: "image/jpeg", ByteLength: int64(len(content)), ModifiedAt: int64(index + 1),
		}
		preparedResponse := perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/prepare", mustJSON(t, media), token, "")
		var prepared prepareResponse
		decode(t, preparedResponse.Body.Bytes(), &prepared)
		if preparedResponse.Code != http.StatusOK || prepared.UploadID == "" {
			t.Fatalf("prepare %d: %d %s", index, preparedResponse.Code, preparedResponse.Body.String())
		}
		uploadIDs[index] = prepared.UploadID
		chunkHash := sha256.Sum256(content)
		chunkResponse := perform(server.APIHandler(), http.MethodPut, "/api/v1/uploads/"+prepared.UploadID+"/chunks/0", content, token, hex.EncodeToString(chunkHash[:]))
		if chunkResponse.Code != http.StatusOK {
			t.Fatalf("chunk %d: %d %s", index, chunkResponse.Code, chunkResponse.Body.String())
		}
	}

	responses := make([]*httptest.ResponseRecorder, len(contents))
	var wait sync.WaitGroup
	for index := range contents {
		wait.Add(1)
		go func() {
			defer wait.Done()
			responses[index] = perform(server.APIHandler(), http.MethodPost, "/api/v1/uploads/"+uploadIDs[index]+"/finalize", nil, token, "")
		}()
	}
	wait.Wait()
	for index, response := range responses {
		if response.Code != http.StatusOK {
			t.Fatalf("finalize %d: %d %s", index, response.Code, response.Body.String())
		}
	}

	saved := make(map[string]bool)
	if err := filepath.WalkDir(files.Root(), func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr == nil && !entry.IsDir() && filepath.Ext(path) == ".jpg" {
			value, readErr := os.ReadFile(path)
			if readErr != nil {
				return readErr
			}
			saved[string(value)] = true
		}
		return walkErr
	}); err != nil {
		t.Fatal(err)
	}
	for _, content := range contents {
		if !saved[string(content)] {
			t.Fatalf("concurrent finalization lost content %q; saved=%v", content, saved)
		}
	}
}

func TestFinalizationRejectsMismatchedExistingFile(t *testing.T) {
	root := t.TempDir()
	db, err := store.Open(filepath.Join(root, "catalog.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	files, err := storage.New(filepath.Join(root, "media"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{ServerID: "server-test", ServerName: "Test", StorageRoot: files.Root(), StateDir: root, APIPort: 5443, AdminPort: 5444}
	server := New(cfg, db, files, "sha256/test", slog.New(slog.NewTextHandler(io.Discard, nil)))
	device, err := db.CreateDevice(context.Background(), "Test phone", []byte("mismatch-token"))
	if err != nil {
		t.Fatal(err)
	}
	wanted := []byte("wanted")
	media := store.MediaMetadata{MediaKey: "media-1", DisplayName: "photo.jpg", MIMEType: "image/jpeg", ByteLength: int64(len(wanted)), ModifiedAt: 1}
	prepared, err := db.PrepareUpload(context.Background(), device.ID, media, files.NewTempPath())
	if err != nil {
		t.Fatal(err)
	}
	wantedHash := sha256.Sum256(wanted)
	finalPath := filepath.Join(files.Root(), "existing.jpg")
	if err := os.WriteFile(finalPath, []byte("broken"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := db.SetFinalizing(context.Background(), prepared.Upload.ID, store.NewID(), finalPath, hex.EncodeToString(wantedHash[:])); err != nil {
		t.Fatal(err)
	}
	upload, err := db.GetUpload(context.Background(), prepared.Upload.ID, device.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := server.finishInterrupted(context.Background(), upload); err == nil {
		t.Fatal("mismatched existing final file was accepted")
	}
}

func perform(handler http.Handler, method, path string, body []byte, token, chunkHash string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, bytes.NewReader(body))
	request.ContentLength = int64(len(body))
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	if chunkHash != "" {
		request.Header.Set("X-Chunk-SHA256", chunkHash)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder
}

func performAdmin(handler http.Handler, method, path string, body []byte) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, "http://127.0.0.1:5444"+path, bytes.NewReader(body))
	request.ContentLength = int64(len(body))
	request.RemoteAddr = "127.0.0.1:12345"
	request.Header.Set("Origin", "http://127.0.0.1:5444")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func decode(t *testing.T, data []byte, target any) {
	t.Helper()
	if err := json.Unmarshal(data, target); err != nil {
		t.Fatalf("decode %s: %v", data, err)
	}
}
