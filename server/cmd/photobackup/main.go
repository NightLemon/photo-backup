package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"

	"github.com/NightLemon/photo-backup/server/internal/application"
	"github.com/NightLemon/photo-backup/server/internal/config"
	"github.com/NightLemon/photo-backup/server/internal/platform"
)

var version = "dev"

func main() {
	command := "serve"
	if len(os.Args) > 1 && os.Args[1][0] != '-' {
		command = os.Args[1]
		os.Args = append([]string{os.Args[0]}, os.Args[2:]...)
	}
	switch command {
	case "init":
		initCommand()
	case "serve":
		serveCommand()
	case "version":
		fmt.Println(version)
	default:
		log.Fatalf("unknown command %q (expected init, serve, or version)", command)
	}
}

func initCommand() {
	flags := flag.NewFlagSet("init", flag.ExitOnError)
	stateDir := flags.String("state-dir", config.DefaultStateDir(), "configuration and database directory")
	storageRoot := flags.String("storage-root", "", "photo storage directory")
	name := flags.String("name", "Home Photo Backup", "server name shown to phones")
	_ = flags.Parse(os.Args[1:])
	cfg, err := application.Initialize(*stateDir, *storageRoot, *name)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Initialized %s\nState: %s\nPhotos: %s\n", cfg.ServerID, cfg.StateDir, cfg.StorageRoot)
}

func serveCommand() {
	flags := flag.NewFlagSet("serve", flag.ExitOnError)
	stateDir := flags.String("state-dir", config.DefaultStateDir(), "configuration and database directory")
	_ = flags.Parse(os.Args[1:])
	if err := platform.Run(func(ctx context.Context) error {
		return application.Run(ctx, *stateDir)
	}); err != nil {
		log.Fatal(err)
	}
}
