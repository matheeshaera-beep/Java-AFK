// Command bping performs a RakNet ping against Bedrock UDP:19132 hosts to check
// egress reachability.
package main

import (
	"context"
	"flag"
	"fmt"
	"time"

	"github.com/sandertv/go-raknet"
)

func main() {
	timeout := flag.Uint("t", 3, "timeout seconds per host")
	flag.Parse()
	hosts := flag.Args()
	if len(hosts) == 0 {
		hosts = []string{"play.cubecraft.net", "play.hivemc.com", "play.nethergames.org", "mco.mineplex.com", "play.inpvp.net", "play.lbsg.net", "play.galaxite.net"}
	}
	for _, h := range hosts {
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeout)*time.Second)
		resp, err := raknet.PingContext(ctx, h+":19132")
		cancel()
		if err != nil {
			fmt.Printf("%-28s ERR  %v\n", h, err)
			continue
		}
		fmt.Printf("%-28s OK   %q\n", h, string(resp))
	}
}