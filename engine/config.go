package engine

import (
	"encoding/json"
	"fmt"
	"time"
)

// Config holds the network-facing connection settings for the engine. It is
// passed to Engine.Start as a JSON string so that the Kotlin side can build it
// entirely from user input.
type Config struct {
	// Host is the hostname or IP address of the Bedrock server.
	Host string `json:"host"`
	// Port is the UDP port of the Bedrock server. Defaults to 19132.
	Port int `json:"port"`
	// ChatCommand is an optional chat command (e.g. "/fly on") sent once after
	// spawn. If empty, nothing is sent.
	ChatCommand string `json:"chatCommand"`
	// CommandDelaySeconds is how long to wait after spawn before sending
	// ChatCommand. Defaults to 5.
	CommandDelaySeconds int `json:"commandDelaySeconds"`
	// Offline, when true, skips Microsoft authentication entirely and joins
	// unauthenticated. Only valid for servers that run in offline mode (e.g.
	// local test servers); leave false for normal online-mode servers.
	Offline bool `json:"offline"`
	// TokenCachePath is a directory (must exist) where the Microsoft OAuth
	// token is persisted so login is not required on every launch.
	TokenCachePath string `json:"tokenCachePath"`
	// BackoffMinSeconds is the initial reconnect backoff. Defaults to 2.
	BackoffMinSeconds int `json:"backoffMinSeconds"`
	// BackoffMaxSeconds caps the exponential backoff. Defaults to 120.
	BackoffMaxSeconds int `json:"backoffMaxSeconds"`
	// DialTimeoutSeconds is the total budget for a single connect attempt
	// (RakNet handshake + Minecraft login), including fragment reassembly over
	// lossy mobile links. Defaults to 120.
	DialTimeoutSeconds int `json:"dialTimeoutSeconds"`
	// ZombieCooldownSeconds is how long to wait before retrying after a
	// zombie-creating failure (duplicate-login kick, or a dial that timed out
	// after the login handshake may have reached the server). This must be at
	// least as long as the server's session-expiry timeout so the stale session
	// is gone before we retry. Defaults to 60.
	ZombieCooldownSeconds int `json:"zombieCooldownSeconds"`
}

func defaultConfig() Config {
	return Config{
		Port:                 19132,
		CommandDelaySeconds:  5,
		BackoffMinSeconds:    2,
		BackoffMaxSeconds:    120,
		DialTimeoutSeconds:   120,
		ZombieCooldownSeconds: 60,
	}
}

func parseConfig(configJSON string) (Config, error) {
	cfg := defaultConfig()
	if configJSON == "" {
		return cfg, fmt.Errorf("config JSON is empty")
	}
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return cfg, fmt.Errorf("parse config: %w", err)
	}
	if cfg.Host == "" {
		return cfg, fmt.Errorf("config: host is required")
	}
	if cfg.Port <= 0 || cfg.Port > 65535 {
		return cfg, fmt.Errorf("config: invalid port %d (must be 1-65535)", cfg.Port)
	}
	if cfg.CommandDelaySeconds < 0 {
		cfg.CommandDelaySeconds = 0
	}
	if cfg.BackoffMinSeconds < 1 {
		cfg.BackoffMinSeconds = 2
	}
	if cfg.BackoffMaxSeconds < cfg.BackoffMinSeconds {
		cfg.BackoffMaxSeconds = cfg.BackoffMinSeconds
	}
	if cfg.DialTimeoutSeconds < 10 {
		cfg.DialTimeoutSeconds = 120
	}
	if cfg.ZombieCooldownSeconds < 10 {
		cfg.ZombieCooldownSeconds = 60
	}
	return cfg, nil
}

func (c Config) address() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}

func (c Config) commandDelay() time.Duration {
	return time.Duration(c.CommandDelaySeconds) * time.Second
}

func (c Config) backoffMin() time.Duration {
	return time.Duration(c.BackoffMinSeconds) * time.Second
}

func (c Config) backoffMax() time.Duration {
	return time.Duration(c.BackoffMaxSeconds) * time.Second
}

func (c Config) dialTimeout() time.Duration {
	return time.Duration(c.DialTimeoutSeconds) * time.Second
}

func (c Config) zombieCooldown() time.Duration {
	return time.Duration(c.ZombieCooldownSeconds) * time.Second
}
