package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/sandertv/gophertunnel/minecraft"
)

func main() {
	arg := "donutsmp.net:19132"
	if len(os.Args) > 1 {
		arg = os.Args[1]
	}
	timeout := 15 * time.Second
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	fmt.Printf("[probe2] offline dial %s (timeout %s)\n", arg, timeout)
	start := time.Now()
	d := &minecraft.Dialer{}
	conn, err := d.DialContext(ctx, "raknet", arg)
	if err != nil {
		fmt.Printf("[probe2] dial result: ERROR after %.1fs: %v\n", time.Since(start).Seconds(), err)
		return
	}
	defer conn.Close()
	fmt.Printf("[probe2] dial result: OK after %.1fs\n", time.Since(start).Seconds())
}