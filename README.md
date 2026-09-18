# Java AFK

Android app that keeps your Minecraft **Java Edition** account online (AFK) on any
server — backed by [mineflayer](https://github.com/PrismarineJS/mineflayer)
running on an embedded Node.js runtime, plus a Go
[gophertunnel](https://github.com/Sandertv/gophertunnel)-based Bedrock engine
for headless/CLI use.

## Features

- **Server list** — add, edit and delete servers (name, host, port).
- **Online + offline mode** — Microsoft device-code sign-in with saved token,
  or offline usernames.
- **Up to 2 bots at once**, each with its own session screen.
- **Live connection stages** — bridge boot → connecting → authenticating →
  logging in → connected, with an "Online as X · auth · host:port" summary.
- **Live logs + chat** — bridge log stream, per-server chat send/receive.
- **AFK timer + data usage** — per-server counters that reset on Stop.
- **Health + hunger** logged every 10 s while connected.
- **Foreground service** — persistent notification shows the running server(s)
  with a force-stop action; per-server wake lock.
- **Resource monitor** — CPU / RAM drawer panel, dark mode.

## How to use

1. Install the APK from the
   [v2.0 release](https://github.com/matheeshaera-beep/java-afk-app/releases/tag/v2.0).
2. Open the app, tap **+**, enter a server (e.g. Name `Donut SMP`,
   Host `donutsmp.net`, Port `25565`).
3. Open the server, tap **Start**.
4. If it uses online mode, tap **Open browser** in the sign-in dialog, log in
   with your Microsoft account and enter the shown code, then tap **Done**.
5. The state turns **Connected** (`Online as <you>`) and the bot idles on the
   server. Optional: set a chat command + delay in the ⚙ settings — it runs
   after every spawn.
6. **Stop** disconnects (timer + data reset). The ✕ action on the notification
   force-stops the service and all sessions.

Tips:

- The log tab shows everything the bridge reports (connect, auth, kicks,
  errors, reconnects).
- If Microsoft asks again later, use **Clear token** in the server settings
  and Start again.
- Two servers can run at the same time; a third Start is refused with a message.

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 37, NDK 29), Go 1.27+ for the
engine/CLI.

```sh
cd android
./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/
go build ./...                      # Go engine + CLI (repo root)
```

Notes for developers:

- `android/` embeds Node.js v18 (`jniLibs/arm64-v8a`) through a small JNI
  bridge (`native-lib.cpp` — `node::Start` runs exactly once per process).
- The mineflayer bot project lives in
  `android/app/src/main/assets/nodejs-project/` (vendored `node_modules`,
  including the full `minecraft-data` Java protocol payload the bot needs).
  On first Start the app copies it to internal storage (stamp-guarded).
- The local HTTP bridge defaults to `http://127.0.0.1:3001`
  (`NodeRuntime.baseUrl`, `BRIDGE_PORT` in `main.js`).
- `engine/` is the standalone Bedrock client (RakNet handshake, Xbox auth,
  spawn, keep-alive, reconnect policy); `cmd/afkcli` is its CLI.

## Donate

If this app keeps your farms chunk-loaded, consider supporting it:

**[buymeacoffee.com/matheeshaex](https://buymeacoffee.com/matheeshaex)**

## License

Personal project — all rights reserved unless stated otherwise.
