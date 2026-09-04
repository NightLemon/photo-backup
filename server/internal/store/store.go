package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

const ChunkSize int64 = 8 * 1024 * 1024

type Store struct {
	db *sql.DB
}

type Device struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	CreatedAt int64  `json:"createdAt"`
	Revoked   bool   `json:"revoked"`
}

type MediaMetadata struct {
	MediaKey     string `json:"mediaKey"`
	DisplayName  string `json:"displayName"`
	RelativePath string `json:"relativePath"`
	MIMEType     string `json:"mimeType"`
	ByteLength   int64  `json:"byteLength"`
	ModifiedAt   int64  `json:"modifiedAt"`
	CapturedAt   int64  `json:"capturedAt"`
}

type Upload struct {
	ID       string
	DeviceID string
	MediaMetadata
	ChunkSize int64
	TempPath  string
	FinalPath string
	SHA256    string
	AssetID   string
	State     string
	CreatedAt int64
	UpdatedAt int64
}

type Asset struct {
	ID           string `json:"assetId"`
	DeviceID     string `json:"-"`
	MediaKey     string `json:"mediaKey"`
	DisplayName  string `json:"displayName"`
	RelativePath string `json:"relativePath"`
	MIMEType     string `json:"mimeType"`
	ByteLength   int64  `json:"byteLength"`
	ModifiedAt   int64  `json:"modifiedAt"`
	CapturedAt   int64  `json:"capturedAt"`
	SHA256       string `json:"sha256"`
	CompletedAt  int64  `json:"completedAt"`
	FinalPath    string `json:"-"`
}

type PrepareResult struct {
	Asset          *Asset
	Upload         *Upload
	ReceivedChunks []int
}

type Stats struct {
	DeviceCount int64 `json:"deviceCount"`
	AssetCount  int64 `json:"assetCount"`
	TotalBytes  int64 `json:"totalBytes"`
	ActiveCount int64 `json:"activeUploads"`
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;`); err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_hash BLOB NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS uploads (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id),
  media_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  relative_path TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  byte_length INTEGER NOT NULL,
  modified_at INTEGER NOT NULL,
  captured_at INTEGER NOT NULL,
  chunk_size INTEGER NOT NULL,
  temp_path TEXT NOT NULL,
  final_path TEXT NOT NULL DEFAULT '',
  sha256 TEXT NOT NULL DEFAULT '',
  asset_id TEXT NOT NULL DEFAULT '',
  state TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uploads_active_media
  ON uploads(device_id, media_key, byte_length, modified_at)
  WHERE state IN ('uploading', 'finalizing');
CREATE TABLE IF NOT EXISTS received_chunks (
  upload_id TEXT NOT NULL REFERENCES uploads(id) ON DELETE CASCADE,
  chunk_index INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  byte_length INTEGER NOT NULL,
  PRIMARY KEY(upload_id, chunk_index)
);
CREATE TABLE IF NOT EXISTS assets (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id),
  media_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  relative_path TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  byte_length INTEGER NOT NULL,
  modified_at INTEGER NOT NULL,
  captured_at INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  final_path TEXT NOT NULL,
  completed_at INTEGER NOT NULL,
  UNIQUE(device_id, media_key, byte_length, modified_at)
);
CREATE INDEX IF NOT EXISTS assets_device ON assets(device_id, completed_at);
`)
	return err
}

func (s *Store) CreateDevice(ctx context.Context, name string, tokenHash []byte) (Device, error) {
	d := Device{ID: newID(), Name: name, CreatedAt: nowMillis()}
	_, err := s.db.ExecContext(ctx, `INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)`, d.ID, d.Name, tokenHash, d.CreatedAt)
	return d, err
}

func (s *Store) Authenticate(ctx context.Context, tokenHash []byte) (Device, error) {
	var d Device
	var revoked int
	err := s.db.QueryRowContext(ctx, `SELECT id,name,created_at,revoked FROM devices WHERE token_hash=?`, tokenHash).
		Scan(&d.ID, &d.Name, &d.CreatedAt, &revoked)
	d.Revoked = revoked != 0
	if err == nil && d.Revoked {
		return Device{}, sql.ErrNoRows
	}
	return d, err
}

func (s *Store) ListDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id,name,created_at,revoked FROM devices ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]Device, 0)
	for rows.Next() {
		var d Device
		var revoked int
		if err := rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &revoked); err != nil {
			return nil, err
		}
		d.Revoked = revoked != 0
		result = append(result, d)
	}
	return result, rows.Err()
}

func (s *Store) RevokeDevice(ctx context.Context, id string) error {
	result, err := s.db.ExecContext(ctx, `UPDATE devices SET revoked=1 WHERE id=?`, id)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func (s *Store) PrepareUpload(ctx context.Context, deviceID string, media MediaMetadata, tempPath string) (PrepareResult, error) {
	asset, err := s.findAsset(ctx, deviceID, media)
	if err == nil {
		return PrepareResult{Asset: &asset}, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return PrepareResult{}, err
	}
	upload, err := s.findActiveUpload(ctx, deviceID, media)
	if errors.Is(err, sql.ErrNoRows) {
		now := nowMillis()
		upload = Upload{
			ID: newID(), DeviceID: deviceID, MediaMetadata: media, ChunkSize: ChunkSize,
			TempPath: tempPath, State: "uploading", CreatedAt: now, UpdatedAt: now,
		}
		_, err = s.db.ExecContext(ctx, `INSERT INTO uploads(
id,device_id,media_key,display_name,relative_path,mime_type,byte_length,modified_at,captured_at,
chunk_size,temp_path,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			upload.ID, upload.DeviceID, upload.MediaKey, upload.DisplayName, upload.RelativePath, upload.MIMEType,
			upload.ByteLength, upload.ModifiedAt, upload.CapturedAt, upload.ChunkSize, upload.TempPath,
			upload.State, upload.CreatedAt, upload.UpdatedAt)
		if err != nil {
			// A concurrent prepare may have inserted the same logical upload.
			upload, err = s.findActiveUpload(ctx, deviceID, media)
		}
	}
	if err != nil {
		return PrepareResult{}, err
	}
	chunks, err := s.ReceivedChunks(ctx, upload.ID)
	return PrepareResult{Upload: &upload, ReceivedChunks: chunks}, err
}

func (s *Store) GetUpload(ctx context.Context, id, deviceID string) (Upload, error) {
	return scanUpload(s.db.QueryRowContext(ctx, uploadSelect+` WHERE id=? AND device_id=?`, id, deviceID))
}

func (s *Store) RecordChunk(ctx context.Context, uploadID string, index int, hash string, length int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT INTO received_chunks(upload_id,chunk_index,sha256,byte_length)
VALUES(?,?,?,?) ON CONFLICT(upload_id,chunk_index) DO UPDATE SET sha256=excluded.sha256,byte_length=excluded.byte_length`, uploadID, index, hash, length); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE uploads SET updated_at=? WHERE id=? AND state='uploading'`, nowMillis(), uploadID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) ReceivedChunks(ctx context.Context, uploadID string) ([]int, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT chunk_index FROM received_chunks WHERE upload_id=? ORDER BY chunk_index`, uploadID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var indices []int
	for rows.Next() {
		var idx int
		if err := rows.Scan(&idx); err != nil {
			return nil, err
		}
		indices = append(indices, idx)
	}
	return indices, rows.Err()
}

func (s *Store) ChunkCount(ctx context.Context, uploadID string) (int64, error) {
	var count int64
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM received_chunks WHERE upload_id=?`, uploadID).Scan(&count)
	return count, err
}

func (s *Store) SetFinalizing(ctx context.Context, uploadID, assetID, finalPath, hash string) error {
	result, err := s.db.ExecContext(ctx, `UPDATE uploads SET state='finalizing',asset_id=?,final_path=?,sha256=?,updated_at=? WHERE id=? AND state='uploading'`, assetID, finalPath, hash, nowMillis(), uploadID)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		return fmt.Errorf("upload is not ready to finalize")
	}
	return nil
}

func (s *Store) CompleteUpload(ctx context.Context, uploadID string) (Asset, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Asset{}, err
	}
	defer tx.Rollback()
	upload, err := scanUpload(tx.QueryRowContext(ctx, uploadSelect+` WHERE id=? AND state='finalizing'`, uploadID))
	if err != nil {
		return Asset{}, err
	}
	asset := Asset{
		ID: upload.AssetID, DeviceID: upload.DeviceID, MediaKey: upload.MediaKey, DisplayName: upload.DisplayName,
		RelativePath: upload.RelativePath, MIMEType: upload.MIMEType, ByteLength: upload.ByteLength,
		ModifiedAt: upload.ModifiedAt, CapturedAt: upload.CapturedAt, SHA256: upload.SHA256,
		FinalPath: upload.FinalPath, CompletedAt: nowMillis(),
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO assets(id,device_id,media_key,display_name,relative_path,mime_type,
byte_length,modified_at,captured_at,sha256,final_path,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(device_id,media_key,byte_length,modified_at) DO NOTHING`, asset.ID, asset.DeviceID, asset.MediaKey,
		asset.DisplayName, asset.RelativePath, asset.MIMEType, asset.ByteLength, asset.ModifiedAt, asset.CapturedAt,
		asset.SHA256, asset.FinalPath, asset.CompletedAt)
	if err != nil {
		return Asset{}, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE uploads SET state='complete',updated_at=? WHERE id=?`, nowMillis(), uploadID); err != nil {
		return Asset{}, err
	}
	if err := tx.Commit(); err != nil {
		return Asset{}, err
	}
	return s.findAsset(ctx, upload.DeviceID, upload.MediaMetadata)
}

func (s *Store) ListFinalizing(ctx context.Context) ([]Upload, error) {
	rows, err := s.db.QueryContext(ctx, uploadSelect+` WHERE state='finalizing'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []Upload
	for rows.Next() {
		u, err := scanUpload(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, u)
	}
	return result, rows.Err()
}

func (s *Store) Stats(ctx context.Context) (Stats, error) {
	var result Stats
	queries := []struct {
		query string
		dest  *int64
	}{
		{`SELECT COUNT(*) FROM devices WHERE revoked=0`, &result.DeviceCount},
		{`SELECT COUNT(*) FROM assets`, &result.AssetCount},
		{`SELECT COALESCE(SUM(byte_length),0) FROM assets`, &result.TotalBytes},
		{`SELECT COUNT(*) FROM uploads WHERE state IN ('uploading','finalizing')`, &result.ActiveCount},
	}
	for _, item := range queries {
		if err := s.db.QueryRowContext(ctx, item.query).Scan(item.dest); err != nil {
			return Stats{}, err
		}
	}
	return result, nil
}

func (s *Store) findAsset(ctx context.Context, deviceID string, media MediaMetadata) (Asset, error) {
	var a Asset
	err := s.db.QueryRowContext(ctx, `SELECT id,device_id,media_key,display_name,relative_path,mime_type,
byte_length,modified_at,captured_at,sha256,final_path,completed_at FROM assets
WHERE device_id=? AND media_key=? AND byte_length=? AND modified_at=?`, deviceID, media.MediaKey, media.ByteLength, media.ModifiedAt).
		Scan(&a.ID, &a.DeviceID, &a.MediaKey, &a.DisplayName, &a.RelativePath, &a.MIMEType, &a.ByteLength,
			&a.ModifiedAt, &a.CapturedAt, &a.SHA256, &a.FinalPath, &a.CompletedAt)
	return a, err
}

func (s *Store) findActiveUpload(ctx context.Context, deviceID string, media MediaMetadata) (Upload, error) {
	return scanUpload(s.db.QueryRowContext(ctx, uploadSelect+` WHERE device_id=? AND media_key=? AND byte_length=? AND modified_at=? AND state IN ('uploading','finalizing')`,
		deviceID, media.MediaKey, media.ByteLength, media.ModifiedAt))
}

const uploadSelect = `SELECT id,device_id,media_key,display_name,relative_path,mime_type,byte_length,
modified_at,captured_at,chunk_size,temp_path,final_path,sha256,asset_id,state,created_at,updated_at FROM uploads`

type scanner interface{ Scan(...any) error }

func scanUpload(row scanner) (Upload, error) {
	var u Upload
	err := row.Scan(&u.ID, &u.DeviceID, &u.MediaKey, &u.DisplayName, &u.RelativePath, &u.MIMEType,
		&u.ByteLength, &u.ModifiedAt, &u.CapturedAt, &u.ChunkSize, &u.TempPath, &u.FinalPath,
		&u.SHA256, &u.AssetID, &u.State, &u.CreatedAt, &u.UpdatedAt)
	return u, err
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func NewID() string { return newID() }

func nowMillis() int64 { return time.Now().UnixMilli() }
