package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestConfigurationPersistsSafeOverrides(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "state")
	firstStorage := filepath.Join(root, "photos-one")
	created, err := LoadOrCreate(state, firstStorage, "First Name")
	if err != nil {
		t.Fatal(err)
	}
	secondStorage := filepath.Join(root, "photos-two")
	updated, err := LoadOrCreate(state, secondStorage, "Second Name")
	if err != nil {
		t.Fatal(err)
	}
	loaded, err := LoadOrCreate(state, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if loaded.ServerID != created.ServerID || loaded.StorageRoot != updated.StorageRoot || loaded.ServerName != "Second Name" {
		t.Fatalf("configuration override did not persist: %+v", loaded)
	}
}

func TestSavePersistsConfiguration(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "state")
	created, err := LoadOrCreate(state, filepath.Join(root, "photos-one"), "First Name")
	if err != nil {
		t.Fatal(err)
	}
	created.StorageRoot = filepath.Join(root, "photos-two")
	if err := Save(created); err != nil {
		t.Fatal(err)
	}
	loaded, err := LoadOrCreate(state, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if loaded.StorageRoot != created.StorageRoot {
		t.Fatalf("saved storage root was not loaded: got %q want %q", loaded.StorageRoot, created.StorageRoot)
	}
}

func TestRejectsVolumeRootAndPrivateStateAsStorage(t *testing.T) {
	root := filepath.VolumeName(t.TempDir()) + string(filepath.Separator)
	cfg := Config{ServerID: "id", ServerName: "name", StorageRoot: root, StateDir: t.TempDir(), APIPort: 1, AdminPort: 2}
	if cfg.Validate() == nil {
		t.Fatal("volume root was accepted")
	}
	state := t.TempDir()
	cfg.StorageRoot, cfg.StateDir = state, state
	if cfg.Validate() == nil {
		t.Fatal("private state directory was accepted as media storage")
	}
	parent := t.TempDir()
	cfg.StorageRoot, cfg.StateDir = parent, filepath.Join(parent, "private-state")
	if cfg.Validate() == nil {
		t.Fatal("storage root containing the private state directory was accepted")
	}
}

func TestRejectsStorageLinkToPrivateState(t *testing.T) {
	root := t.TempDir()
	state := filepath.Join(root, "state")
	if err := os.MkdirAll(state, 0700); err != nil {
		t.Fatal(err)
	}
	storageLink := filepath.Join(root, "storage-link")
	if err := os.Symlink(state, storageLink); err != nil {
		t.Skipf("symbolic links are unavailable: %v", err)
	}
	cfg := Config{ServerID: "id", ServerName: "name", StorageRoot: storageLink, StateDir: state, APIPort: 1, AdminPort: 2}
	if cfg.Validate() == nil {
		t.Fatal("storage link resolving to private state was accepted")
	}
}
