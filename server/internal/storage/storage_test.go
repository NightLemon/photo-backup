package storage

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestSanitizesUntrustedWindowsPaths(t *testing.T) {
	parts := safeRelativeParts(`../../DCIM\..\Camera`)
	if strings.Join(parts, "/") != "DCIM/Camera" {
		t.Fatalf("unexpected safe path: %v", parts)
	}
	if got := safeSegment("CON.jpg"); got != "_CON.jpg" {
		t.Fatalf("reserved Windows name was not escaped: %q", got)
	}
	long := safeSegment(strings.Repeat("旅行", 100) + ".jpg")
	if !utf8.ValidString(long) || len(long) > 120 {
		t.Fatalf("long Unicode name was not safely truncated: bytes=%d valid=%v", len(long), utf8.ValidString(long))
	}
}

func TestRejectsFinalDirectoryLinkOutsideRoot(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	manager, err := New(root)
	if err != nil {
		t.Fatal(err)
	}
	linkedDir := filepath.Join(root, "linked-device")
	if err := os.Symlink(outside, linkedDir); err != nil {
		t.Skipf("symbolic links are unavailable: %v", err)
	}
	tempPath := manager.NewTempPath()
	if err := os.WriteFile(tempPath, []byte("photo"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := manager.MoveIntoPlace(tempPath, filepath.Join(linkedDir, "photo.jpg"), 0); err == nil {
		t.Fatal("final directory link escaping storage root was accepted")
	}
}
