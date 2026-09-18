package engine

import (
	"context"
	"time"
)

// run drives connection attempts until ctx is cancelled. Each attempt runs
// synchronously (connect -> spawn -> read loop), then the outcome is classified
// and the next attempt is scheduled according to the reconnect policy.
func (e *Engine) run(ctx context.Context) {
	defer func() {
		e.mu.Lock()
		e.running = false
		e.state = StateDisconnected
		e.stateDetail = "stopped"
		e.lastPing = 0
		e.mu.Unlock()
		e.dispatcher.state(StateDisconnected, "stopped")
		e.dispatcher.log("engine stopped")
	}()

	cfg := e.currentConfig()
	e.logf("starting engine for %s:%d (command=%q delay=%ds)",
		cfg.Host, cfg.Port, cfg.ChatCommand, cfg.CommandDelaySeconds)

	for {
		if ctx.Err() != nil {
			return
		}

		res := e.attempt(ctx)

		if e.reconnectReq.Swap(false) {
			e.drainInterrupt()
			e.logf("reconnect requested, restarting attempt")
			continue
		}
		if ctx.Err() != nil {
			e.logf("stopped during attempt")
			return
		}

		e.setLastError(res.reason)

		switch res.kind {
		case kindKick:
			e.mu.Lock()
			e.tracker.kickFailures++
			attempts := e.tracker.kickFailures
			e.mu.Unlock()
			e.dispatcher.kicked(res.reason)
			if res.zombie {
				// Duplicate login: the server still holds our previous session
				// as online ("zombie"). Any retry before that session expires
				// hits the same rejection, so wait out the server's
				// session-expiry window, then retry automatically.
				delay := cfg.zombieCooldown()
				e.setState(StateConnecting, "old session still online; retrying in "+delay.Round(time.Second).String())
				e.logf("duplicate session (%v); waiting %v for the old session to expire", res.reason, delay)
				if !e.sleepCtx(ctx, delay) {
					return
				}
				continue
			}
			e.setState(StateError, "rejected by server: "+res.reason)
			e.logf("server refused connection (%v); retried %d time(s)", res.reason, attempts)
			if attempts > 1 {
				e.setState(StateError, "rejected by server: "+res.reason+" - tap Reconnect")
				e.logf("waiting for manual Reconnect")
				if e.waitForManual(ctx) {
					continue
				}
				return
			}
			// First kick attempt: retry once after a short pause in case the
			// cause is transient (e.g. a server momentarily reporting full).
			if !e.sleepCtx(ctx, 3*time.Second) {
				return
			}

		case kindAuth:
			e.setState(StateError, "authentication failed: "+res.reason)
			e.logf("auth failure (%v); clearing token", res.reason)
			e.clearCachedToken(cfg)
			e.logf("waiting for manual Reconnect to start a fresh login")
			if e.waitForManual(ctx) {
				continue
			}
			return

		case kindNetwork:
			e.mu.Lock()
			delay := e.tracker.nextBackoff(cfg)
			e.mu.Unlock()
			if res.zombie {
				// A dial/spawn that timed out may still have a live session
				// registered on the server that will reject the next login.
				// Floor the delay at the session-expiry window.
				if delay < cfg.zombieCooldown() {
					delay = cfg.zombieCooldown()
				}
				e.logf("connection lost (%v); possible stale session, retrying in %v", res.reason, delay)
			} else {
				e.logf("connection lost (%v); retrying in %v", res.reason, delay)
			}
			e.setState(StateConnecting, "reconnecting in "+delay.Round(time.Second).String()+": "+res.reason)
			if !e.sleepCtx(ctx, delay) {
				return
			}

		default:
			e.setState(StateDisconnected, res.reason)
			return
		}
	}
}

// drainInterrupt clears any pending manual-reconnect pulse so it does not
// incorrectly short-circuit a later backoff sleep.
func (e *Engine) drainInterrupt() {
	for {
		select {
		case <-e.interrupt:
		default:
			return
		}
	}
}

// waitForManual blocks until the user taps Reconnect or Stop is pressed.
// Returns true if a new connection attempt should start.
func (e *Engine) waitForManual(ctx context.Context) bool {
	for {
		select {
		case <-ctx.Done():
			return false
		case <-e.interrupt:
			e.mu.Lock()
			e.state = StateConnecting
			e.stateDetail = "manual reconnect requested"
			e.mu.Unlock()
			return true
		}
	}
}

// sleepCtx sleeps for d, or until Stop (returns false) or a manual Reconnect
// (returns true immediately).
func (e *Engine) sleepCtx(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-e.interrupt:
		return true
	case <-time.After(d):
		return true
	}
}

// clearCachedToken drops any persisted token so the next attempt re-logins.
func (e *Engine) clearCachedToken(cfg Config) {
	if cfg.TokenCachePath != "" {
		clearToken(cfg)
	}
}