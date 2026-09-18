package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sandertv/gophertunnel/minecraft"
)

// Engine is the headless Minecraft Bedrock client engine. It maintains a
// network connection, handles reconnects and exposes status and callbacks.
// The exported surface is intentionally small so it can be bound to Android
// via gomobile.
type Engine struct {
	dispatcher *dispatcher

	// reconnectReq is set when the user taps Reconnect so the run loop knows a
	// manual attempt is requested.
	reconnectReq atomic.Bool

	mu            sync.Mutex
	running       bool
	cfg           Config
	state         string
	stateDetail   string
	lastPacket    time.Time
	lastError     string
	lastPing      int64
	disconnectMsg string
	tracker       attemptTracker

	attemptMu     sync.Mutex
	attemptCtx    context.Context
	cancelFn      context.CancelFunc
	cancelAttempt context.CancelFunc
	conn          *minecraft.Conn

	// interrupt is pulsed on manual Reconnect for waits between attempts.
	interrupt chan struct{}
}

// New creates an Engine. It is safe to reuse across Start/Stop cycles.
func New() *Engine {
	return newEngine()
}

// newEngine is the internal constructor shared by New and the defensive
// re-initialization in the exported mutators. It guarantees the dispatcher is
// always non-nil so SetEventCallback/Start/Stop can never dereference nil.
func newEngine() *Engine {
	return &Engine{
		dispatcher: newDispatcher(),
		state:      StateDisconnected,
		interrupt:  make(chan struct{}, 1),
	}
}

// ensureInit repairs an engine that was invoked without a valid constructor:
// it guarantees the dispatcher and interrupt channel exist so exported methods
// can never dereference a nil field. It returns the engine itself for chaining
// (or a fresh engine when the receiver itself is nil).
func (e *Engine) ensureInit() *Engine {
	if e == nil {
		return newEngine()
	}
	if e.dispatcher == nil {
		e.dispatcher = newDispatcher()
	}
	if e.interrupt == nil {
		e.interrupt = make(chan struct{}, 1)
	}
	return e
}

// SetEventCallback installs the event sink used for state/log/auth/kick
// events. Pass nil to detach.
func (e *Engine) SetEventCallback(cb Event) {
	if e == nil {
		return
	}
	e.ensureInit().dispatcher.setCallback(cb)
}

// Start begins connecting to the server described by configJSON and keeps the
// connection alive until Stop or a permanent error. It returns immediately;
// progress is reported through the event callback.
func (e *Engine) Start(configJSON string) {
	if e == nil {
		return
	}
	e.ensureInit()

	cfg, err := parseConfig(configJSON)
	if err != nil {
		e.setState(StateError, err.Error())
		e.dispatcher.log(err.Error())
		return
	}

	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		e.dispatcher.log("already running; Stop() first")
		return
	}
	e.running = true
	e.cfg = cfg
	e.state = StateDisconnected
	e.stateDetail = ""
	e.lastError = ""
	e.disconnectMsg = ""
	e.lastPing = 0
	e.tracker = attemptTracker{}
	e.mu.Unlock()

	// Stop() cancels runCtx.
	runCtx, cancel := context.WithCancel(context.Background())
	e.mu.Lock()
	e.cancelFn = cancel
	e.mu.Unlock()

	e.dispatcher.log("engine starting")
	go e.run(runCtx)
}

// Stop cleanly disconnects, cancels any in-flight login and releases the
// connection. Safe to call from any goroutine, including callbacks.
func (e *Engine) Stop() {
	if e == nil {
		return
	}
	e.ensureInit()

	e.mu.Lock()
	cancel := e.cancelFn
	running := e.running
	e.mu.Unlock()
	if !running {
		e.dispatcher.log("engine not running")
		return
	}
	e.disconnectMsg = "stopped by user"
	if cancel != nil {
		cancel()
	}
	e.abortAttempt()
}

// Reconnect cancels the current attempt (or backoff sleep) and immediately
// starts a new connection attempt with fresh backoff. When the connection is
// established it force-closes it to reconnect. It is a no-op when not running.
func (e *Engine) Reconnect() {
	if e == nil {
		return
	}
	e.ensureInit()

	e.mu.Lock()
	running := e.running
	if running {
		e.tracker.networkFailures = 0
		e.tracker.kickFailures = 0
	}
	e.mu.Unlock()
	if !running {
		e.dispatcher.log("not running; Start() the engine first")
		return
	}
	e.dispatcher.log("manual reconnect requested")
	e.reconnectReq.Store(true)
	e.abortAttempt()
	select {
	case e.interrupt <- struct{}{}:
	default:
	}
}

// HasSavedToken reports whether a cached Microsoft token exists on disk.
func (e *Engine) HasSavedToken() bool {
	if e == nil {
		return false
	}
	e.ensureInit()

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cfg.TokenCachePath == "" {
		return false
	}
	return loadCachedToken(e.cfg) != nil
}

// ClearToken wipes the persisted Microsoft token so the next Start runs the
// device-code login flow again.
func (e *Engine) ClearToken() {
	if e == nil {
		return
	}
	e.ensureInit()

	e.mu.Lock()
	cfg := e.cfg
	tokenPath := e.cfg.TokenCachePath
	e.mu.Unlock()
	if tokenPath != "" {
		clearToken(cfg)
		e.dispatcher.log("cleared cached login token")
	}
}

// StatusJSON returns the current connection status as JSON.
func (e *Engine) StatusJSON() string {
	if e == nil {
		return "{}"
	}
	e.ensureInit()

	e.mu.Lock()
	defer e.mu.Unlock()
	lastPacket := int64(0)
	if !e.lastPacket.IsZero() {
		lastPacket = e.lastPacket.UnixMilli()
	}
	st := status{
		State:      e.state,
		Detail:     e.stateDetail,
		Ping:       e.lastPing,
		LastPacket: lastPacket,
		LastError:  e.lastError,
	}
	data, _ := json.Marshal(st)
	return string(data)
}

// status is the JSON schema returned by StatusJSON.
type status struct {
	State      string `json:"state"`
	Detail     string `json:"detail"`
	Ping       int64  `json:"ping"`
	LastPacket int64  `json:"lastPacket"`
	LastError  string `json:"lastError"`
}

// currentConfig returns a copy of the active config.
func (e *Engine) currentConfig() Config {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.cfg
}

// abortAttempt cancels the current attempt context and closes any live conn,
// unblocking the read loop. Used by Stop and Reconnect.
func (e *Engine) abortAttempt() {
	e.attemptMu.Lock()
	if e.cancelAttempt != nil {
		e.cancelAttempt()
	}
	conn := e.conn
	e.attemptMu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
}

// setState updates state + detail and pushes the change to the UI.
func (e *Engine) setState(state, detail string) {
	e.mu.Lock()
	e.state = state
	e.stateDetail = detail
	e.mu.Unlock()
	e.dispatcher.state(state, detail)
}

// setLastError records an error message in the status.
func (e *Engine) setLastError(msg string) {
	e.mu.Lock()
	e.lastError = msg
	e.mu.Unlock()
}

// setPing records the latest latency in ms.
func (e *Engine) setPing(ms int64) {
	e.mu.Lock()
	e.lastPing = ms
	e.mu.Unlock()
}

// touchLastPacket marks the last packet time as now.
func (e *Engine) touchLastPacket() {
	e.mu.Lock()
	e.lastPacket = time.Now()
	e.mu.Unlock()
}

func (e *Engine) logf(format string, args ...any) {
	e.dispatcher.log(fmt.Sprintf(format, args...))
}
