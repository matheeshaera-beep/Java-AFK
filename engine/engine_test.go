package engine

import (
	"testing"
	"time"

	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func TestClassifyReason(t *testing.T) {
	cases := []struct {
		reason string
		want   disconnectKind
	}{
		{"You were kicked by an operator", kindKick},
		{"You are banned from this server", kindKick},
		{"server full", kindKick},
		{"We don't allow connections from Indonesia", kindNetwork},
		{"dial raknet: discover mtu: read: connection refused", kindNetwork},
		{"read udp: i/o timeout", kindNetwork},
		{"connection reset by peer", kindNetwork},
		{"This player is not whitelisted", kindKick},
		{"You are already logged in from another location", kindKick},
		{"Duplicate login: you are already connected on this server", kindKick},
		{"Account already connected to the server", kindKick},
		{"logged in anywhere else - try again later", kindKick},
	}
	for _, c := range cases {
		if got := classifyReason(c.reason); got != c.want {
			t.Errorf("classifyReason(%q) = %v, want %v", c.reason, got, c.want)
		}
	}
}

func TestBackoff(t *testing.T) {
	min, max := 2*time.Second, 120*time.Second
	cases := []struct {
		n    int
		want time.Duration
	}{
		{1, 2 * time.Second},
		{2, 4 * time.Second},
		{3, 8 * time.Second},
		{4, 16 * time.Second},
		{5, 32 * time.Second},
		{6, 64 * time.Second},
		{7, 120 * time.Second}, // capped
		{20, 120 * time.Second},
	}
	for _, c := range cases {
		if got := backoff(c.n, min, max); got != c.want {
			t.Errorf("backoff(%d) = %v, want %v", c.n, got, c.want)
		}
	}
}

func TestBackoffTracker(t *testing.T) {
	c := defaultConfig()
	c.BackoffMinSeconds = 2
	c.BackoffMaxSeconds = 120
	var tr attemptTracker
	if got := tr.nextBackoff(c); got != 2*time.Second {
		t.Fatalf("first backoff = %v", got)
	}
	if got := tr.nextBackoff(c); got != 4*time.Second {
		t.Fatalf("second backoff = %v", got)
	}
	tr.resetNetwork()
	if got := tr.nextBackoff(c); got != 2*time.Second {
		t.Fatalf("backoff after reset = %v", got)
	}
}

func TestDisconnectText(t *testing.T) {
	if got := disconnectText(&packet.Disconnect{Message: "You were kicked"}); got != "You were kicked" {
		t.Errorf("got %q", got)
	}
	if got := disconnectText(&packet.Disconnect{Reason: packet.DisconnectReasonServerFull}); got != "server full" {
		t.Errorf("got %q", got)
	}
	if got := disconnectText(&packet.Disconnect{}); got != "disconnected by server" {
		t.Errorf("got %q", got)
	}
}

func TestParseConfigDefaults(t *testing.T) {
	cfg, err := parseConfig(`{"host":"example.com"}`)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 19132 {
		t.Errorf("port default = %d", cfg.Port)
	}
	if cfg.CommandDelaySeconds != 5 {
		t.Errorf("delay default = %d", cfg.CommandDelaySeconds)
	}
	if cfg.backoffMin() != 2*time.Second || cfg.backoffMax() != 120*time.Second {
		t.Errorf("backoff defaults wrong: %v %v", cfg.backoffMin(), cfg.backoffMax())
	}
	if cfg.dialTimeout() != 120*time.Second {
		t.Errorf("dial timeout default = %v", cfg.dialTimeout())
	}
	if cfg.zombieCooldown() != 60*time.Second {
		t.Errorf("zombie cooldown default = %v", cfg.zombieCooldown())
	}
	if _, err := parseConfig(`{}`); err == nil {
		t.Error("expected error for missing host")
	}
	if cfg, err := parseConfig(`{"host":"example.com","port":0}`); err == nil {
		t.Errorf("expected error for invalid port, got %+v", cfg)
	}
	if _, err := parseConfig(`{"host":"example.com","port":70000}`); err == nil {
		t.Error("expected error for out-of-range port")
	}
	if cfg, err := parseConfig(`{"host":"example.com","port":19133}`); err != nil || cfg.Port != 19133 {
		t.Errorf("valid custom port rejected: %+v, %v", cfg, err)
	}
	if cfg, err := parseConfig(`{"host":"example.com","dialTimeoutSeconds":45,"zombieCooldownSeconds":30}`); err != nil ||
		cfg.dialTimeout() != 45*time.Second || cfg.zombieCooldown() != 30*time.Second {
		t.Errorf("custom timeouts rejected: %+v, %v", cfg, err)
	}
	if cfg, err := parseConfig(`{"host":"example.com","dialTimeoutSeconds":2,"zombieCooldownSeconds":3}`); err != nil ||
		cfg.dialTimeout() != 120*time.Second || cfg.zombieCooldown() != 60*time.Second {
		t.Errorf("timeout floors not enforced: %+v, %v", cfg, err)
	}
}

func TestIsDuplicateLogin(t *testing.T) {
	for _, ok := range []string{
		"already logged in",
		"You are already logged in from another location",
		"logged in anywhere else - try again later",
		"Duplicate login: you are already connected on this server",
		"duplicate connection from a nearby device",
		"Account already connected to the server",
	} {
		if !isDuplicateLogin(ok) {
			t.Errorf("isDuplicateLogin(%q) = false, want true", ok)
		}
	}
	for _, no := range []string{
		"You were kicked by an operator",
		"server full",
		"dial raknet: read: connection refused",
		"read udp: i/o timeout",
	} {
		if isDuplicateLogin(no) {
			t.Errorf("isDuplicateLogin(%q) = true, want false", no)
		}
	}
}
