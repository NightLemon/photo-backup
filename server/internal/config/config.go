package config

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	DefaultAPIPort   = 5443
	DefaultAdminPort = 5444
)

type Config struct {
	ServerID    string `json:"serverId"`
	ServerName  string `json:"serverName"`
	StorageRoot string `json:"storageRoot"`
	StateDir    string `json:"stateDir"`
	APIPort     int    `json:"apiPort"`
	AdminPort   int    `json:"adminPort"`
}

func DefaultStateDir() string {
	if base := os.Getenv("PROGRAMDATA"); base != "" {
		return filepath.Join(base, "HomePhotoBackup")
	}
	base, err := os.UserConfigDir()
	if err != nil {
		return filepath.Join(".", ".home-photo-backup")
	}
	return filepath.Join(base, "HomePhotoBackup")
}

func LoadOrCreate(stateDir, storageRoot, serverName string) (Config, error) {
	if stateDir == "" {
		stateDir = DefaultStateDir()
	}
	stateDir, err := filepath.Abs(stateDir)
	if err != nil {
		return Config{}, err
	}
	configPath := filepath.Join(stateDir, "config.json")
	data, err := os.ReadFile(configPath)
	if err == nil {
		var cfg Config
		if err := json.Unmarshal(data, &cfg); err != nil {
			return Config{}, fmt.Errorf("decode config: %w", err)
		}
		if storageRoot != "" {
			cfg.StorageRoot = storageRoot
		}
		if serverName != "" {
			cfg.ServerName = serverName
		}
		cfg.StateDir = stateDir
		if err := cfg.Validate(); err != nil {
			return Config{}, err
		}
		if storageRoot != "" || serverName != "" {
			if err := save(configPath, cfg); err != nil {
				return Config{}, err
			}
		}
		return cfg, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return Config{}, err
	}
	if storageRoot == "" {
		storageRoot = filepath.Join(stateDir, "Media")
	}
	if serverName == "" {
		serverName = "Home Photo Backup"
	}
	cfg := Config{
		ServerID:    newID(),
		ServerName:  serverName,
		StorageRoot: storageRoot,
		StateDir:    stateDir,
		APIPort:     DefaultAPIPort,
		AdminPort:   DefaultAdminPort,
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return Config{}, err
	}
	if err := os.MkdirAll(cfg.StorageRoot, 0750); err != nil {
		return Config{}, err
	}
	if err := save(configPath, cfg); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func Save(cfg Config) error {
	if err := cfg.Validate(); err != nil {
		return err
	}
	if err := os.MkdirAll(cfg.StateDir, 0700); err != nil {
		return err
	}
	return save(filepath.Join(cfg.StateDir, "config.json"), cfg)
}

func save(path string, cfg Config) error {
	encoded, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".config-*.tmp")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	if err := temp.Chmod(0600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(encoded); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, path)
}

func (c *Config) Validate() error {
	if c.ServerID == "" || c.ServerName == "" {
		return errors.New("server id and name are required")
	}
	if c.APIPort < 1 || c.APIPort > 65535 || c.AdminPort < 1 || c.AdminPort > 65535 {
		return errors.New("invalid listen port")
	}
	root, err := filepath.Abs(c.StorageRoot)
	if err != nil {
		return fmt.Errorf("resolve storage root: %w", err)
	}
	state, err := filepath.Abs(c.StateDir)
	if err != nil {
		return fmt.Errorf("resolve state directory: %w", err)
	}
	c.StorageRoot = filepath.Clean(root)
	c.StateDir = filepath.Clean(state)
	volumeRoot := filepath.Clean(filepath.VolumeName(c.StorageRoot) + string(filepath.Separator))
	if c.StorageRoot == volumeRoot {
		return errors.New("storage root cannot be an entire volume")
	}
	checkedRoot := resolvedPath(c.StorageRoot)
	checkedState := resolvedPath(c.StateDir)
	if checkedRoot == checkedState {
		return errors.New("storage root cannot equal the private state directory")
	}
	if contains(checkedRoot, checkedState) {
		return errors.New("storage root cannot contain the private state directory")
	}
	return nil
}

func resolvedPath(path string) string {
	resolved, err := filepath.EvalSymlinks(path)
	if err == nil {
		return filepath.Clean(resolved)
	}
	return filepath.Clean(path)
}

func contains(parent, child string) bool {
	relative, err := filepath.Rel(parent, child)
	return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))
}

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	s := fmt.Sprintf("%x", b)
	return strings.Join([]string{s[:8], s[8:12], s[12:16], s[16:20], s[20:]}, "-")
}
