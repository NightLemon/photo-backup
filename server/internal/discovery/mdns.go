package discovery

import (
	"fmt"

	"github.com/grandcat/zeroconf"

	"github.com/NightLemon/photo-backup/server/internal/config"
)

type Registration struct{ server *zeroconf.Server }

func Register(cfg config.Config) (*Registration, error) {
	server, err := zeroconf.Register(
		cfg.ServerName,
		"_home-photo-backup._tcp",
		"local.",
		cfg.APIPort,
		[]string{fmt.Sprintf("id=%s", cfg.ServerID), "api=1"},
		nil,
	)
	if err != nil {
		return nil, err
	}
	return &Registration{server: server}, nil
}

func (r *Registration) Shutdown() {
	if r != nil && r.server != nil {
		r.server.Shutdown()
	}
}
