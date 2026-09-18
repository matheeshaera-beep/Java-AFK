package engine

import (
	"context"
	"net"

	"github.com/sandertv/go-raknet"
	"github.com/sandertv/gophertunnel/minecraft"
)

// raknetNet is a minecraft.Network that dials RakNet with settings tuned for
// unreliable mobile/WiFi links. MaxMTU caps the probe size during MTU
// discovery so every probe stays inside a single IP datagram. Some routers,
// NATs and mobile carriers drop fragmented UDP packets, which otherwise makes
// the handshake silently fail ("connection lost" before a single packet is
// exchanged). The vendored go-raknet fork already uses large read buffers to
// tolerate oversized datagrams.
type raknetNet struct{}

func (raknetNet) DialContext(ctx context.Context, address string) (net.Conn, error) {
	return raknet.Dialer{MaxMTU: 1400}.DialContext(ctx, address)
}

func (raknetNet) PingContext(ctx context.Context, address string) ([]byte, error) {
	return raknet.Dialer{MaxMTU: 1400}.PingContext(ctx, address)
}

func (raknetNet) Listen(address string) (minecraft.NetworkListener, error) {
	return (minecraft.RakNet{}).Listen(address)
}
