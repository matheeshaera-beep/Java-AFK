package engine

import (
	"strings"
	"time"
)

// disconnectKind classifies why a connection ended.
type disconnectKind int

const (
	// kindNetwork covers plain network drops, timeouts and host unreachable.
	kindNetwork disconnectKind = iota
	// kindKick covers deliberate server-side disconnects: kicks, bans,
	// server-full, whitelist denials and the like.
	kindKick
	// kindAuth covers authentication failures.
	kindAuth
	// kindInternal covers unexpected engine errors and user-initiated stops.
	kindInternal
)

// result is the outcome of a single connection attempt.
type result struct {
	kind   disconnectKind
	reason string
	// zombie marks failures where the server may still be holding our previous
	// session as "online". This includes duplicate-login kicks (the server
	// still sees the old session) and dial timeouts (the login handshake may
	// have reached the server before our side gave up). After a zombie failure
	// the next attempt must wait for the server's session-expiry timeout rather
	// than retrying immediately and colliding with the stale session again.
	zombie bool
}

// human targets strings that usually indicate a deliberate server-side
// rejection rather than a plain network problem.
var kickSignals = []string{
	"kick",
	"banned",
	"ban ",
	"ban:",
	"bann",
	"server full",
	"server is full",
	"whitelisted",
	"not whitelisted",
	"cannot join",
	"too many connections",
	"account",
	"permission",
	"expired",
	// Duplicate-session rejections: when the server still has the previous
	// session alive, it refuses a new login from the same account. Classifying
	// these as kicks stops the infinite reconnect loop while the old session
	// (typical server-side timeout 10-30s) expires.
	"already logged in",
	"logged in from another location",
	"logged in anywhere else",
	"duplicate login",
	"duplicate connection",
	"already connected",
}

// zombieSignals are the subset of kick signals that indicate the server is
// still holding our previous session as online (a "zombie" session). The
// server refuses the new login until that session expires. Retrying before
// the server's expiry timeout is guaranteed to hit the same wall, so these
// trigger a zombie cooldown instead of the normal fast retry.
var zombieSignals = []string{
	"already logged in",
	"logged in from another location",
	"logged in anywhere else",
	"duplicate login",
	"duplicate connection",
	"already connected",
}

// isDuplicateLogin reports whether a disconnect reason means the server still
// holds an earlier session from this account.
func isDuplicateLogin(reason string) bool {
	return matchesAny(reason, zombieSignals)
}

func matchesAny(reason string, signals []string) bool {
	low := strings.ToLower(reason)
	for _, sig := range signals {
		if strings.Contains(low, sig) {
			return true
		}
	}
	return false
}

// classifyReason decides whether a disconnect should be treated as a kick
// (surfaced, limited auto-retry) or a network fault (auto-retry).
func classifyReason(reason string) disconnectKind {
	for _, sig := range kickSignals {
		if matchesAny(reason, []string{sig}) {
			return kindKick
		}
	}
	return kindNetwork
}

// isRetriable reports whether a result of this kind is worth auto-retrying.
func (r result) isRetriable() bool {
	switch r.kind {
	case kindKick:
		return false
	default:
		return true
	}
}

// backoff returns the delay before the nth consecutive failed attempt with the
// same backoff policy. n starts at 1. The delay grows exponentially from min up
// to max.
func backoff(n int, min, max time.Duration) time.Duration {
	d := min
	for i := 1; i < n; i++ {
		d *= 2
		if d >= max {
			return max
		}
	}
	if d > max {
		return max
	}
	return d
}

// attemptTrackers tracks consecutive network (auto-retried) failures so that
// backoff only grows across retries of the same run.
type attemptTracker struct {
	networkFailures int
	kickFailures    int
}

func (t *attemptTracker) resetNetwork() { t.networkFailures = 0 }

func (t *attemptTracker) nextBackoff(cfg Config) time.Duration {
	t.networkFailures++
	return backoff(t.networkFailures, cfg.backoffMin(), cfg.backoffMax())
}
