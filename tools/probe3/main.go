package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/sandertv/go-raknet"
)

func main() {
	arg := "donutsmp.net:19132"
	if len(os.Args) > 1 {
		arg = os.Args[1]
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	fmt.Printf("[raknet] dial %s\n", arg)
	start := time.Now()
	conn, err := raknet.DialContext(ctx, arg)
	if err != nil {
		fmt.Printf("[raknet] ERROR after %.1fs: %v\n", time.Since(start).Seconds(), err)
		return
	}
	defer conn.Close()
	fmt.Printf("[raknet] OK after %.1fs (addr=%v)\n", time.Since(start).Seconds(), conn.RemoteAddr())
}