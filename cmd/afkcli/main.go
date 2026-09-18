// Command afkcli is a plain CLI harness for exercising the engine outside of
// Android: it connects with device-code auth, reports events to stdout, and
// optionally triggers Reconnect/Stop on timers for deterministic testing of the
// reconnect logic.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/mstheesha/bedrock-afk/engine"
)

type printer struct {
	state string
}

func (p *printer) OnStateChanged(state, detail string) {
	p.state = state
	line := fmt.Sprintf("[state] %s", state)
	if detail != "" {
		line += "  (" + detail + ")"
	}
	fmt.Println(line)
}

func (p *printer) OnLog(line string) {
	fmt.Printf("[log]   %s\n", line)
}

func (p *printer) OnAuthRequired(codeURL, userCode string) {
	fmt.Printf("\n*** LOGIN REQUIRED ***\nOpen: %s\nEnter code: %s\nThen wait here.\n\n", codeURL, userCode)
}

func (p *printer) OnKicked(reason string) {
	fmt.Printf("*** KICKED: %s\n", reason)
}

func (p *printer) OnChat(author, message string) {
	fmt.Printf("*** %s: %s\n", author, message)
}

func main() {
	host := flag.String("host", "", "server host")
	port := flag.Int("port", 19132, "server port")
	command := flag.String("command", "", "post-spawn chat command")
	delay := flag.Int("delay", 5, "delay before command (seconds)")
	tokenDir := flag.String("tokendir", "./token", "directory to persist the Microsoft token")
	offline := flag.Bool("offline", false, "join unauthenticated (server must be offline-mode)")
	autostop := flag.Int("autostop", 0, "stop the engine after this many seconds (0 = run forever)")
	reconnectAt := flag.Int("reconnect-at", 0, "trigger a manual Reconnect after this many seconds (0 = none)")
	timeout := flag.Uint("timeout", 0, "exit after this many seconds regardless (0 = wait forever)")
	flag.Parse()

	if *host == "" {
		fmt.Fprintln(os.Stderr, "-host is required")
		os.Exit(2)
	}

	if err := os.MkdirAll(*tokenDir, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	eng := engine.New()
	cb := &printer{}
	eng.SetEventCallback(cb)

	cfg := map[string]any{
		"host":                *host,
		"port":                *port,
		"chatCommand":         *command,
		"commandDelaySeconds": *delay,
		"tokenCachePath":      *tokenDir,
		"offline":             *offline,
	}
	cfgJSON, _ := json.Marshal(cfg)
	fmt.Printf("starting: %s\n", cfgJSON)
	eng.Start(string(cfgJSON))

	var exitAt <-chan time.Time
	if *timeout > 0 {
		exitAt = time.After(time.Duration(*timeout) * time.Second)
	}

	var stopAt <-chan time.Time
	if *autostop > 0 {
		stopAt = time.After(time.Duration(*autostop) * time.Second)
	}

	var rcAt <-chan time.Time
	if *reconnectAt > 0 {
		rcAt = time.After(time.Duration(*reconnectAt) * time.Second)
	}

	for {
		select {
		case <-stopAt:
			fmt.Println("autostop: calling Stop()")
			eng.Stop()
		case <-rcAt:
			fmt.Println("reconnect-at: calling Reconnect()")
			eng.Reconnect()
		case <-exitAt:
			fmt.Println("status at exit:", eng.StatusJSON())
			fmt.Println("has saved token:", eng.HasSavedToken())
			return
		case <-time.After(2 * time.Second):
			// Quietly check status every couple seconds so the log stays
			// readable; prints only on state changes via the callback.
			fmt.Printf("  (status: %s)\n", statusHuman(eng.StatusJSON()))
		}
	}
}

func statusHuman(s string) string {
	var st struct {
		State      string `json:"state"`
		Detail     string `json:"detail"`
		Ping       int64  `json:"ping"`
		LastPacket int64  `json:"lastPacket"`
		LastError  string `json:"lastError"`
	}
	_ = json.Unmarshal([]byte(s), &st)
	return fmt.Sprintf("state=%s ping=%dms detail=%q lastError=%q", st.State, st.Ping, st.Detail, st.LastError)
}