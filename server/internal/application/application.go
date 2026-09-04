package application

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/NightLemon/photo-backup/server/internal/api"
	"github.com/NightLemon/photo-backup/server/internal/config"
	"github.com/NightLemon/photo-backup/server/internal/discovery"
	"github.com/NightLemon/photo-backup/server/internal/security"
	"github.com/NightLemon/photo-backup/server/internal/storage"
	"github.com/NightLemon/photo-backup/server/internal/store"
)

func Initialize(stateDir, storageRoot, serverName string) (config.Config, error) {
	cfg, err := config.LoadOrCreate(stateDir, storageRoot, serverName)
	if err != nil {
		return config.Config{}, err
	}
	if err := os.MkdirAll(cfg.StateDir, 0700); err != nil {
		return config.Config{}, err
	}
	if err := os.MkdirAll(cfg.StorageRoot, 0750); err != nil {
		return config.Config{}, err
	}
	if _, _, _, err := security.EnsureCertificate(cfg.StateDir, cfg.ServerID); err != nil {
		return config.Config{}, err
	}
	db, err := store.Open(filepath.Join(cfg.StateDir, "catalog.db"))
	if err != nil {
		return config.Config{}, err
	}
	defer db.Close()
	return cfg, nil
}

func Run(ctx context.Context, stateDir string) error {
	cfg, err := config.LoadOrCreate(stateDir, "", "")
	if err != nil {
		return err
	}
	logFile, err := os.OpenFile(filepath.Join(cfg.StateDir, "server.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0640)
	if err != nil {
		return err
	}
	defer logFile.Close()
	logger := slog.New(slog.NewJSONHandler(logFile, &slog.HandlerOptions{Level: slog.LevelInfo}))
	certPath, keyPath, spkiPin, err := security.EnsureCertificate(cfg.StateDir, cfg.ServerID)
	if err != nil {
		return err
	}
	db, err := store.Open(filepath.Join(cfg.StateDir, "catalog.db"))
	if err != nil {
		return err
	}
	defer db.Close()
	files, err := storage.New(cfg.StorageRoot)
	if err != nil {
		return err
	}
	handler := api.New(cfg, db, files, spkiPin, logger)
	if err := handler.RecoverFinalizing(ctx); err != nil {
		return fmt.Errorf("recover interrupted uploads: %w", err)
	}

	apiServer := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.APIPort),
		Handler:           handler.APIHandler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12},
	}
	adminServer := &http.Server{
		Addr:              fmt.Sprintf("127.0.0.1:%d", cfg.AdminPort),
		Handler:           handler.AdminHandler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       time.Minute,
	}

	registration, err := discovery.Register(cfg)
	if err != nil {
		logger.Warn("mDNS registration failed; QR address fallback remains available", "error", err)
	} else {
		defer registration.Shutdown()
	}

	errorsCh := make(chan error, 2)
	go func() {
		logger.Info("HTTPS API listening", "port", cfg.APIPort)
		errorsCh <- apiServer.ListenAndServeTLS(certPath, keyPath)
	}()
	go func() {
		logger.Info("admin dashboard listening", "port", cfg.AdminPort)
		errorsCh <- adminServer.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = adminServer.Shutdown(shutdownCtx)
		_ = apiServer.Shutdown(shutdownCtx)
		return nil
	case err := <-errorsCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}
