package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/sandertv/gophertunnel/minecraft/auth"
	"golang.org/x/oauth2"
)

const tokenFileName = "ms_token.json"

// tokenCache returns the path of the token file inside the given cache dir.
func tokenCachePath(cfg Config) string {
	return filepath.Join(cfg.TokenCachePath, tokenFileName)
}

// loadCachedToken reads a previously persisted oauth2.Token, or nil if none
// exists or it cannot be read.
func loadCachedToken(cfg Config) *oauth2.Token {
	data, err := os.ReadFile(tokenCachePath(cfg))
	if err != nil {
		return nil
	}
	t := &oauth2.Token{}
	if err := json.Unmarshal(data, t); err != nil {
		return nil
	}
	if t.AccessToken == "" && t.RefreshToken == "" {
		return nil
	}
	return t
}

// saveToken persists the oauth2.Token to app-private storage. Called only by
// the engine after a successful login; the token JSON is never logged.
func saveToken(cfg Config, t *oauth2.Token) error {
	data, err := json.Marshal(t)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(cfg.TokenCachePath, 0o700); err != nil {
		return err
	}
	tmp := tokenCachePath(cfg) + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, tokenCachePath(cfg))
}

// clearToken removes any persisted token.
func clearToken(cfg Config) {
	_ = os.Remove(tokenCachePath(cfg))
}

// tokenSource builds an oauth2.TokenSource for the Dialer. If a cached token
// exists it is used directly (auto-refreshed by the auth package). Otherwise
// the Microsoft device-code flow runs: codeURL/UserCode are reported through
// the event callback and the flow polls until the user completes login or ctx
// is cancelled.
func (e *Engine) tokenSource(ctx context.Context, cfg Config) (oauth2.TokenSource, error) {
	if tok := loadCachedToken(cfg); tok != nil {
		e.logf("using cached Microsoft token")
		return auth.AndroidConfig.TokenSource(ctx, tok), nil
	}

	e.setState(StateAuthenticating, "starting Microsoft device-code login")
	e.logf("no cached token found, starting device-code login")

	d, err := auth.AndroidConfig.DeviceAuth(ctx)
	if err != nil {
		return nil, fmt.Errorf("start device auth: %w", err)
	}

	e.dispatcher.auth(d.VerificationURI, d.UserCode)
	e.logf("authenticate at %s using code %s (do not close the app)", d.VerificationURI, d.UserCode)

	e.setState(StateAuthenticating, "waiting for browser login")

	token, err := auth.AndroidConfig.DeviceAccessToken(ctx, d)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return nil, err
		}
		return nil, fmt.Errorf("device login: %w", err)
	}

	if err := saveToken(cfg, token); err != nil {
		e.logf("warning: could not persist token: %v", err)
	}
	e.logf("login successful, token cached")
	return auth.AndroidConfig.TokenSource(ctx, token), nil
}