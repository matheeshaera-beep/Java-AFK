package engine

import (
	"sync"
)

// Event is the callback interface implemented by the Android (Kotlin) side and
// invoked from the engine. All methods are called on a single dispatcher
// goroutine; the Kotlin side is responsible for hopping to the main thread.
type Event interface {
	// OnStateChanged reports a change in connection state. detail carries a
	// human-readable message (last error, kick reason, etc.).
	OnStateChanged(state, detail string)
	// OnLog reports a log line for the on-screen log.
	OnLog(line string)
	// OnAuthRequired is emitted when the Microsoft device-code login flow
	// starts. The user must open codeURL in a browser and enter userCode.
	OnAuthRequired(codeURL, userCode string)
	// OnKicked reports a deliberate server-side disconnect (kick/ban/full).
	OnKicked(reason string)
	// OnChat reports an incoming chat message from another player. author is
	// the display name of the sender; message is the chat text.
	OnChat(author, message string)
}

const (
	StateDisconnected   = "disconnected"
	StateConnecting     = "connecting"
	StateAuthenticating = "authenticating"
	StateConnected      = "connected"
	StateError          = "error"
)

// eventMsg is one queued callback invocation.
type eventMsg struct {
	fn func()
}

// dispatcher serialises event callbacks onto a single goroutine and drops
// events if the consumer (the app) is not keeping up.
type dispatcher struct {
	ch   chan eventMsg
	cbMu sync.RWMutex
	cb   Event
}

func newDispatcher() *dispatcher {
	d := &dispatcher{ch: make(chan eventMsg, 256)}
	go d.loop()
	return d
}

func (d *dispatcher) loop() {
	for msg := range d.ch {
		msg.fn()
	}
}

func (d *dispatcher) setCallback(cb Event) {
	d.cbMu.Lock()
	d.cb = cb
	d.cbMu.Unlock()
}

// emit enqueues fn for delivery to the current callback, dropping when full.
func (d *dispatcher) emit(fn func()) {
	select {
	case d.ch <- eventMsg{fn: fn}:
	default:
	}
}

func (d *dispatcher) state(state, detail string) {
	d.emit(func() {
		if c := d.currentCallback(); c != nil {
			c.OnStateChanged(state, detail)
		}
	})
}

func (d *dispatcher) log(line string) {
	d.emit(func() {
		if c := d.currentCallback(); c != nil {
			c.OnLog(line)
		}
	})
}

func (d *dispatcher) auth(codeURL, userCode string) {
	d.emit(func() {
		if c := d.currentCallback(); c != nil {
			c.OnAuthRequired(codeURL, userCode)
		}
	})
}

func (d *dispatcher) kicked(reason string) {
	d.emit(func() {
		if c := d.currentCallback(); c != nil {
			c.OnKicked(reason)
		}
	})
}

func (d *dispatcher) chat(author, message string) {
	d.emit(func() {
		if c := d.currentCallback(); c != nil {
			c.OnChat(author, message)
		}
	})
}

func (d *dispatcher) currentCallback() Event {
	d.cbMu.RLock()
	defer d.cbMu.RUnlock()
	return d.cb
}

func (d *dispatcher) close() {
	close(d.ch)
}