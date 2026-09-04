//go:build !windows

package platform

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

func Run(callback func(context.Context) error) error {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	return callback(ctx)
}
