# Java AFK

An Android app that runs a **Minecraft Java Edition** AFK bot on your phone using an embedded [mineflayer](https://github.com/PrismarineJS/mineflayer) bot powered by a bundled Node.js engine (via JNI).

Keeps you AFK-safe on Java servers: auto-connect, auto-reconnect, chat commands, health/food monitoring, and a clean Material 3 UI.

## Features

- **Embedded Node.js runtime** — no server or PC needed; the bot runs entirely on the device.
- **Multiple servers** — add Java servers (host + port), each with its own engine/session.
- **Start / Stop / Reconnect** — full session lifecycle control, with automatic reconnect on kick/disconnect.
- **Microsoft account auth** — device-code sign-in for online-mode servers, tokens cached on-device.
- **Offline / cracked servers** — switch to offline mode per server.
- **AFK automation** — optional chat command sent after spawning, with a configurable delay.
- **Live status** — state, connection detail, health/food, position, and recent logs.
- **Resource panel** — CPU / RAM / network usage shown in the navigation drawer.
- **Dark mode** — Material 3 theme light/dark toggle, persisted.
- **Server pinging** — reachability and server icon (favicon) fetched per server.

## What it does

1. You add a server (e.g. `donutsmp.net:25565`).
2. Tap **Start**, and the app boots the embedded Node.js runtime running `mineflayer`.
3. The bot connects, (optionally) authenticates with your Microsoft account, spawns, and goes AFK.
4. The app polls the bridge and shows live health/food, connection state, and logs.
5. If the bot is kicked or the connection drops, it reconnects automatically while the session is active.

## Project layout

```
android/            Android app (Kotlin + Compose Material 3 UI)
  app/src/main/java/    UI, engines, DB, service, resource monitoring
  app/src/main/cpp/     JNI bridge that embeds libnode
  app/src/main/jniLibs/ Prebuilt libnode.so per ABI
  app/src/main/assets/nodejs-project/   Bundled bot JS + package.json
nodejs-project/     Source of the mineflayer bot (main.js)
```

The `nodejs-project/main.js` is the bot's bridge: an HTTP server on `127.0.0.1:3000` that the app talks to (`/start`, `/stop`, `/reconnect`, `/chat`, `/status`, `/logs`).

## Building

Requirements:
- JDK 17
- Android SDK (compileSdk 37, NDK 29)
- Node.js installed for dependency installs only (not required to run the app)

```bash
# 1. Install the bot's JS dependencies (do this in both places, they are copied
#    together by the Android build):
#    - nodejs-project/
#    - android/app/src/main/assets/nodejs-project/
npm install

# 2. Build the debug APK
cd android
./gradlew assembleDebug
```

The APK bundles the prebuilt `libnode.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

> Note: `node_modules/` is intentionally gitignored — run `npm install` locally before building.

## Android bridge

The app ships with an included native library (`afkbridge.aar`) that starts and embeds a Node.js instance inside the Android process. `JavaEngine` in Kotlin serializes commands (start/stop/reconnect/chat) against the Node.js HTTP bridge, and a foreground service keeps the session alive.

## License

Source in this repository is provided for personal/educational use.