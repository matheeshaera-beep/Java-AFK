package engine

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"golang.org/x/oauth2"
)

var (
	errStopped      = errors.New("stopped")
	errReconnectReq = errors.New("reconnect requested")
)

// attempt performs a single connect->spawn->read cycle. It blocks until the
// connection ends (naturally or forcibly) and returns the outcome.
func (e *Engine) attempt(ctx context.Context) (res result) {
	ctx, cancel := context.WithCancel(ctx)
	e.attemptMu.Lock()
	e.attemptCtx = ctx
	e.cancelAttempt = cancel
	e.conn = nil
	e.attemptMu.Unlock()
	defer func() {
		e.attemptMu.Lock()
		e.attemptCtx = nil
		e.cancelAttempt = nil
		e.conn = nil
		e.attemptMu.Unlock()
		cancel()
	}()

	cfg := e.currentConfig()

	// 1) Login (uses cached token or runs the device-code flow), unless the
	// server is being joined in offline mode.
	var src oauth2.TokenSource
	var err error
	if !cfg.Offline {
		src, err = e.tokenSource(ctx, cfg)
		if err != nil {
			if ctx.Err() != nil {
				return result{kind: kindInternal, reason: errStopped.Error()}
			}
			e.setLastError(err.Error())
			return result{kind: kindAuth, reason: err.Error()}
		}
	} else {
		e.logf("offline mode enabled, skipping Microsoft authentication")
	}

	// 2) Dial the server. Authentication happens inside DialContextNetwork. The
	// dial runs on a helper goroutine: go-raknet's MTU discovery can stall for
	// tens of seconds, and the subsequent login handshake has to reassemble
	// large fragmented packets over what may be a lossy mobile link. We select
	// on dialCtx to enforce the dial budget and on our parent ctx so
	// Stop()/Reconnect() abort promptly instead of blocking on a dial that
	// refuses to finish.
	//
	// gophertunnel-run dials close the underlying UDP socket as soon as the
	// deadline fires (its listen goroutine defers conn.Close()), so the server
	// sees our peer go away and can reap the session sooner rather than later.
	e.setState(StateConnecting, "connecting to "+cfg.address())
	dialTimeout := cfg.dialTimeout()
	dialCtx, dialCancel := context.WithTimeout(ctx, dialTimeout)
	type dialOut struct {
		conn *minecraft.Conn
		err  error
	}
	dialCh := make(chan dialOut, 1)
	go func() {
		conn, err := minecraft.Dialer{TokenSource: src}.DialContextNetwork(dialCtx, raknetNet{}, cfg.address())
		dialCh <- dialOut{conn, err}
	}()

	var conn *minecraft.Conn
	select {
	case <-ctx.Done():
		dialCancel()
		// Claim any conn the dial goroutine may still produce so it does not
		// leak; the goroutine itself exits once its socket deadline fires.
		go func() {
			out := <-dialCh
			if out.conn != nil {
				_ = out.conn.Close()
			}
		}()
		return result{kind: kindInternal, reason: errStopped.Error()}
	case <-dialCtx.Done():
		dialCancel()
		go func() {
			out := <-dialCh
			if out.conn != nil {
				_ = out.conn.Close()
			}
		}()
		if ctx.Err() != nil {
			// Parent was cancelled at the same moment the dial budget expired;
			// the stop is authoritative.
			return result{kind: kindInternal, reason: errStopped.Error()}
		}
		e.setLastError("connection attempt timed out (run may not reach this server on this network)")
		// A dial that timed out is zombie-creating: the login handshake may have
		// been accepted by the server before our side gave up, leaving a stale
		// session that will refuse the next login until it expires.
		return result{kind: kindNetwork, zombie: true, reason: fmt.Sprintf("dial timed out after %s", dialTimeout)}
	case out := <-dialCh:
		dialCancel()
		if out.err != nil {
			e.setLastError(out.err.Error())
			return result{kind: classifyReason(out.err.Error()), reason: out.err.Error()}
		}
		conn = out.conn
	}
	e.attemptMu.Lock()
	e.conn = conn
	e.attemptMu.Unlock()

	// 3) Spawn in the world.
	e.setState(StateConnecting, "spawning in "+cfg.address())
	spawnCtx, spawnCancel := context.WithTimeout(ctx, 60*time.Second)
	spawnErr := conn.DoSpawnContext(spawnCtx)
	spawnCancel()
	if spawnErr != nil {
		_ = conn.Close()
		if ctx.Err() != nil {
			return result{kind: kindInternal, reason: errStopped.Error()}
		}
		e.setLastError(spawnErr.Error())
		// At this point the login succeeded, so the server holds our session
		// even though the client-side spawn stalled.
		kind := classifyReason(spawnErr.Error())
		return result{kind: kind, reason: spawnErr.Error(), zombie: kind == kindNetwork}
	}

	displayName := conn.IdentityData().DisplayName
	if displayName == "" {
		displayName = cfg.Host
	}
	e.setState(StateConnected, "connected to "+cfg.Host+":"+strconv.Itoa(cfg.Port)+" as "+displayName)
	e.logf("spawned and connected as %s (server %s)", displayName, cfg.address())
	e.touchLastPacket()
	e.mu.Lock()
	e.tracker.networkFailures = 0
	e.tracker.kickFailures = 0
	e.mu.Unlock()

	// 4) Keep the session alive. Bedrock servers expect periodic client input;
	// real clients send a PlayerAuthInput every tick, and some anti-cheat and
	// idle-timeout plugins drop connections that never send any. We mirror the
	// position the server last sent us so teleports/knockback stay in sync.
	gd := conn.GameData()
	pos := newPlayerPos(gd)
	go e.keepAlive(ctx, conn, pos)

	// 5) Optional single chat command after the configured delay.
	if cfg.ChatCommand != "" {
		go e.sendCommandAfter(ctx, conn, cfg)
	}

	// 6) Read loop: keep the connection alive and watch for kicks.
	return e.readLoop(ctx, conn, pos)
}

// readLoop consumes packets until the connection closes, watching for
// deliberate disconnects and updating ping/last-packet telemetry.
func (e *Engine) readLoop(ctx context.Context, conn *minecraft.Conn, pos *playerPos) (res result) {
	for {
		pk, err := conn.ReadPacket()
		if err != nil {
			if ctx.Err() != nil {
				if e.reconnectReq.Load() {
					return result{kind: kindInternal, reason: errReconnectReq.Error()}
				}
				return result{kind: kindInternal, reason: errStopped.Error()}
			}
			if e.reconnectReq.Load() {
				return result{kind: kindInternal, reason: errReconnectReq.Error()}
			}
			e.updatePing(conn)
			e.logf("read loop ended: %v", err)
			return result{kind: classifyReason(err.Error()), reason: err.Error(), zombie: isDuplicateLogin(err.Error())}
		}
		e.touchLastPacket()
		e.updatePing(conn)

		switch p := pk.(type) {
		case *packet.Disconnect:
			reason := disconnectText(p)
			_ = conn.Close()
			e.logf("server disconnected us: %s", reason)
			return result{kind: kindKick, reason: reason, zombie: isDuplicateLogin(reason)}
		case *packet.Text:
			if p.TextType == packet.TextTypeChat && p.SourceName != "" {
				e.dispatcher.chat(p.SourceName, p.Message)
			}
		case *packet.NetworkChunkPublisherUpdate:
			// A normal mobile client runs with a moderate render radius.
			// Requesting a radius of 1 is a tell of a headless bot, so use a
			// realistic value that still keeps bandwidth low.
			_ = conn.WritePacket(&packet.RequestChunkRadius{ChunkRadius: 8, MaxChunkRadius: 8})
		case *packet.MovePlayer:
			if p.EntityRuntimeID == pos.entityID {
				pos.update(p.Position, p.Pitch, p.Yaw)
			}
		}
	}
}

// keepAlive periodically sends a PlayerAuthInput packet so the server sees a
// live, in-world client. Bedrock's server-authoritative movement expects the
// client to report its position and input at least once per tick; headless
// bots that never do so are frequently dropped by anti-cheat or idle-timeout
// plugins. A standing player reports its position unchanged, which is exactly
// what we emit.
func (e *Engine) keepAlive(ctx context.Context, conn *minecraft.Conn, pos *playerPos) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p, pitch, yaw := pos.get()
			_ = conn.WritePacket(&packet.PlayerAuthInput{
				Pitch:             pitch,
				Yaw:               yaw,
				HeadYaw:           yaw,
				Position:          p,
				MoveVector:        mgl32.Vec2{},
				InputMode:         packet.InputModeTouch,
				PlayMode:          packet.PlayModeNormal,
				InteractionModel:  packet.InteractionModelTouch,
				InteractPitch:     pitch,
				InteractYaw:       yaw,
				Tick:              pos.nextTick(),
				CameraOrientation: mgl32.Vec3{},
				Delta:             mgl32.Vec3{},
			})
		}
	}
}

// playerPos tracks the player's last known position and rotation (as last told
// by the server) together with the client tick used for keep-alive
// acknowledgements. It is shared between the read loop (writer) and the
// keep-alive goroutine (reader).
type playerPos struct {
	mu         sync.Mutex
	pos        mgl32.Vec3
	pitch, yaw float32
	entityID   uint64
	tick       uint64
}

func newPlayerPos(gd minecraft.GameData) *playerPos {
	return &playerPos{
		pos:      gd.PlayerPosition,
		pitch:    gd.Pitch,
		yaw:      gd.Yaw,
		entityID: gd.EntityRuntimeID,
	}
}

func (p *playerPos) update(pos mgl32.Vec3, pitch, yaw float32) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.pos, p.pitch, p.yaw = pos, pitch, yaw
}

func (p *playerPos) get() (mgl32.Vec3, float32, float32) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.pos, p.pitch, p.yaw
}

// nextTick advances and returns the client tick. Bedrock runs at 20 ticks per
// second; we send one keep-alive per second, so we advance by 20 each time to
// keep the reported tick in the same ballpark as real client time.
func (p *playerPos) nextTick() uint64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.tick += 20
	return p.tick
}

// sendCommandAfter waits the configured delay then sends the single chat
// command. It is best-effort: connection loss aborts it.
func (e *Engine) sendCommandAfter(ctx context.Context, conn *minecraft.Conn, cfg Config) {
	delay := cfg.commandDelay()
	select {
	case <-ctx.Done():
		return
	case <-time.After(delay):
	}
	err := conn.WritePacket(&packet.Text{
		TextType: packet.TextTypeChat,
		Message:  cfg.ChatCommand,
	})
	if err != nil {
		e.logf("failed to send chat command: %v", err)
		return
	}
	e.logf("sent chat command %q after %v", cfg.ChatCommand, delay)
}

// SendChat sends a chat message (or a slash command) from the connected
// session. It is a no-op when the engine is not connected.
func (e *Engine) SendChat(message string) {
	if e == nil {
		return
	}
	e.ensureInit()

	e.attemptMu.Lock()
	conn := e.conn
	e.attemptMu.Unlock()
	if conn == nil {
		e.dispatcher.log("cannot send chat: not connected")
		return
	}
	err := conn.WritePacket(&packet.Text{
		TextType: packet.TextTypeChat,
		Message:  message,
	})
	if err != nil {
		e.logf("failed to send chat: %v", err)
		return
	}
	e.logf("sent chat: %s", message)
}

// updatePing samples the connection latency (rolling RTT/2 average).
func (e *Engine) updatePing(conn *minecraft.Conn) {
	defer func() {
		if r := recover(); r != nil {
			e.setPing(0)
		}
	}()
	e.setPing(int64(conn.Latency().Milliseconds()))
}

// disconnectText builds a user-facing reason from a Disconnect packet.
func disconnectText(p *packet.Disconnect) string {
	if p.Message != "" {
		return p.Message
	}
	if txt := reasonForCode(p.Reason); txt != "" {
		return txt
	}
	return "disconnected by server"
}

// reasonForCode maps a few common Disconnect reason codes to text. Codes are
// the sentinel reasons servers attach to the reason field; most real kicks
// arrive with a human-readable message instead.
func reasonForCode(code int32) string {
	switch int32(code) {
	case packet.DisconnectReasonServerFull:
		return "server full"
	case packet.DisconnectReasonServerNotFound:
		return "server not found"
	case packet.DisconnectReasonOutdatedServer:
		return "server is outdated for this client"
	case packet.DisconnectReasonOutdatedClient:
		return "client is outdated for this server"
	case packet.DisconnectReasonLoggedInOtherLocation:
		return "logged in from another location"
	case packet.DisconnectReasonBannedSkin:
		return "banned skin"
	default:
		return ""
	}
}
