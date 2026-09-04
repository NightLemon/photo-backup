//go:build windows

package diskspace

import (
	"path/filepath"

	"golang.org/x/sys/windows"
)

func Free(path string) (uint64, error) {
	root := filepath.VolumeName(path) + `\`
	p, err := windows.UTF16PtrFromString(root)
	if err != nil {
		return 0, err
	}
	var available uint64
	err = windows.GetDiskFreeSpaceEx(p, &available, nil, nil)
	return available, err
}
