<div align="center">

```
  ╔═══════════════════════════════╗
  ║  📡  D R O I D S E R V E    ║
  ╚═══════════════════════════════╝
```

**Turn your Android device into a blazing-fast HTTP file server.**  
Browse, stream, and download any file on your phone from any device on the same network — no cables, no cloud, no accounts.

![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-38BDF8?style=flat-square)
![No Ads](https://img.shields.io/badge/Ads-None-0F172A?style=flat-square)

</div>

---

## What It Does

DroidServe runs a real HTTP/1.1 server directly on your Android device. Point any browser on your laptop, tablet, or another phone at the displayed URL and you get an instant file browser for whatever folder you chose — with streaming video/audio, range requests, ZIP downloads, and optional password protection.

No internet connection required. No data leaves your local network. No accounts. No telemetry.

---

## Features

| Feature | Detail |
|---|---|
| **Instant file browser** | Clean web UI with live search, grouped sort (folders first), clickable breadcrumbs, and file icons |
| **Light / dark theme** | One-tap theme toggle in the web UI, remembered per device |
| **Stream media** | Video and audio play in-browser with a built-in player overlay (native controls, folder playlist, resume-where-you-left-off); on Android, tapping opens the OS app chooser (VLC/MX) |
| **Subtitles** | Sidecar `.srt`/`.vtt` files (e.g. `movie.en.srt`) are auto-detected and offered as selectable subtitle tracks; SubRip is converted to WebVTT on the fly |
| **Play in your own player** | On desktop/laptop, formats the browser can't decode (mkv/avi/mov) offer "Open in default player" — a one-tap `.m3u` handoff to VLC / Kodi / mpv / MPC-HC on any OS |
| **Inline preview** | Images, PDFs, text, code, and extensionless files render directly in the browser |
| **Download anything** | Every file row has a ⬇ download button; folders download as ZIP |
| **TV & remote friendly** | Arrow-key spatial navigation, visible focus rings, and overscan padding for Android TV / D-pad browsers |
| **Reliable in background** | Foreground service + CPU/Wi-Fi locks + silent keep-alive defeat aggressive OEM freezers (Transsion XOS, MIUI, etc.) so transfers never stall |
| **QR code** | Auto-generated QR — point another phone's camera to connect instantly |
| **Password protection** | Optional HTTP Basic Auth, session cookie, and per-link token so downloads/streams stay authorized across browsers and external players |
| **System restart survival** | Server auto-restarts if Android kills and recreates the service |
| **CORS headers** | All responses include proper CORS headers for cross-origin use |
| **HEAD request support** | Proper HEAD handling for clients that probe before downloading |
| **Adaptive icon** | Fully vector icon — crisp at every size, themed icon support (Android 13+) |

---

## Android TV Companion App

DroidServe ships a native **Android TV app** (the `tv/` module) so you never have to type an
IP address on a remote. Install it on the TV, start the server on your phone, and the TV finds
it automatically.

| TV feature | Detail |
|---|---|
| **Zero-typing discovery** | The phone advertises `_droidserve._tcp` over mDNS/NSD; the TV lists every phone running DroidServe on the LAN |
| **Works when mDNS is blocked** | Many routers block multicast between clients — the TV also does a **single, polite subnet probe** on launch (with an on-screen **🔄 Rescan**) so reachable servers still appear |
| **Pick, don't guess** | Multiple phones can run servers at once; the TV shows the full list and you choose (no auto-select) |
| **Full D-pad navigation** | Arrow keys move between rows, OK opens folders/files, Back walks up — the file list gets focus by default; search and manual-IP entry are opt-in so text fields never trap the remote |
| **Native playback** | Clicking a file fires an `ACTION_VIEW` intent, so it opens in the device's own player (VLC, MX, Kodi, a photo viewer, etc). No bundled player — better codecs and hardware decoding for free |
| **Play on TV (cast)** | Tap 📺 on any video/audio row in the phone web UI; the phone pushes the stream to the TV app, which opens it in the native player. The phone becomes the remote |
| **Auth aware** | Password-protected servers prompt for credentials; tokenized media URLs stream to external players without re-sending the header |
| **Android 5.0+** | Runs on old TVs (min SDK 21) |

**Network-friendly by design.** The TV app shares one Wi-Fi NIC with tools like `atvtools`.
Discovery is built to coexist: mDNS is passive (no polling), the subnet probe uses bounded
concurrency (≤12 sockets) with a cheap TCP liveness check before any HTTP, runs once per
launch rather than continuously, and the Wi-Fi multicast lock is acquired once and released
cleanly (no leak into a permanent high-power state).

```bash
# Build/install the TV app on an Android TV (adb over USB or network)
./gradlew :tv:installDebug
```

---

## Performance Architecture

DroidServe is built around one principle: **eliminate every unnecessary round-trip**.

### SAF Batch Querying
Android's Storage Access Framework (SAF) is slow by default — `DocumentFile.listFiles()` fires one Binder IPC call per file. A folder with 100 files means 100 round-trips to the storage daemon, adding 10–30 seconds of latency.

DroidServe replaces this with a single `ContentResolver.query()` call that returns all children in one batch — the same approach databases use. A folder with 10,000 files takes the same single round-trip as a folder with 10.

### Directory Cache
A TTL-based LRU cache stores directory listings for 3 seconds. Navigating a folder, clicking a file, and hitting back — the listing is served from memory with zero IPC. Cache is automatically cleared when the server stops.

### Aggressive Thread Pool
```
Core threads  = max(8, CPU_cores × 2)
Max threads   = max(32, CPU_cores × 4)
Queue depth   = 512 connections
```
All core threads are pre-warmed at startup so the first request hits a hot pool. Worker threads run at slightly elevated priority. Pool saturation uses CallerRunsPolicy — the accept thread handles overflow rather than dropping connections.

### Zero-Copy File Serving
For file downloads, DroidServe opens a `ParcelFileDescriptor` and wraps it in a `FileInputStream` — this routes through the Linux page cache directly, avoiding the extra ContentResolver abstraction layer that `openInputStream()` introduces.

### Pre-Compiled Statics
- Range request regex: compiled once, shared across all threads
- SAF column projection array: allocated once at class load
- Auth token: pre-encoded at server start, compared in O(n) bytes vs. decoded header
- MIME map: `HashMap` with 40+ types, O(1) lookup
- HTML template: split into static `String` constants, no `.replace()` scanning on every request

---

## Tech Stack

```
Language        Kotlin (100%)
HTTP Server     NanoHTTPD (embedded, no external server process)
UI              Jetpack Compose + Material3
Storage         Android SAF (Storage Access Framework)
Concurrency     Java ThreadPoolExecutor + Kotlin Coroutines
State           Kotlin StateFlow (reactive, thread-safe)
Icon            VectorDrawable + Canvas (no raster images)
Min SDK         Android 7.0 (API 24) · TV app: Android 5.0 (API 21)
Target SDK      Android 15 (API 35)
```

---

## Project Structure

```
app/src/main/java/com/deekshith/droidserve/
├── MainActivity.kt          # Compose UI — folder picker, settings, status, QR
├── HttpServer.kt            # NanoHTTPD server + foreground service
│   ├── HttpServer           # Request routing, file/directory/ZIP serving
│   ├── DirectoryCache       # TTL-LRU cache for SAF listings
│   └── ServerForegroundService  # Lifecycle management, notifications
├── FileUtils.kt             # MIME detection, HTML builder, ZIP streaming
├── FileEntry.kt             # Lightweight SAF record (replaces DocumentFile)
├── IconDrawable.kt          # Programmatic Canvas icon — no raster assets
├── NetworkUtils.kt          # Local IP detection (multi-interface fallback)
└── ServerStateHolder.kt     # Shared reactive state (StateFlow + AtomicInteger)

app/src/main/res/drawable/
├── ic_launcher_foreground.xml   # Broadcast tower — VectorDrawable
├── ic_launcher_background.xml   # Navy gradient — VectorDrawable
├── ic_launcher_round.xml        # Round variant — VectorDrawable
├── ic_launcher.xml              # Adaptive icon manifest
└── ic_launcher_monochrome.xml   # Themed icon (Android 13+)
```

---

## Setup

### Install from source

```bash
# Clone the repo
git clone https://github.com/deekshith/DroidServe.git
cd DroidServe

# Build and install (connected device or emulator)
./gradlew installDebug
```

### Requirements

- Android Studio Hedgehog or newer
- Android Gradle Plugin 8.3+
- Kotlin 1.9+
- Phone app: Android 7.0+ (API 24) · TV app: Android 5.0+ (API 21)

### Dependencies (`app/build.gradle.kts`)

```kotlin
dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")
}
```

---

## How to Use

1. **Open DroidServe** on your Android device
2. Tap **Choose** to select the folder you want to share
3. Set a **port** (default: 8080) and optional **password**
4. Tap **Start Server**
5. The app shows your local IP, URL, and a QR code
6. On any device on the same Wi-Fi: open the URL or scan the QR

### Connecting

| Method | Steps |
|---|---|
| **Browser** | Type `http://192.168.x.x:8080` in any browser |
| **QR Code** | Scan the QR shown in the app with any phone camera |
| **Copy/Share** | Use the Copy or Share buttons to send the URL |

### Downloading & Opening Files

- **Click a video/audio file** → on desktop/laptop it opens in a built-in player overlay (native controls, subtitles, folder playlist, resume). If your browser can't decode the format (mkv/avi/mov), use **Open in default player** to hand it to VLC/Kodi/mpv. On Android, video/audio open in the system app chooser (VLC, MX Player, etc.)
- **Click an image/PDF/text file** → renders inline in the browser
- **Click ⬇** → forces download to your device (hidden when "Allow file downloads" is off)
- **Click ⬇ ZIP** on a folder → downloads entire folder as a `.zip`
- **Subtitles** → drop a `movie.srt` (or `movie.en.srt`) next to `movie.mp4`; it appears as a subtitle track in the player
- **On a TV / with a remote** → use the arrow keys to move between items and press OK to open

### Password Protection

If a password is set, browsers will show a login prompt. Enter any username and the password you configured. The connection is unencrypted (HTTP), so use this on trusted local networks only.

---

## Icon Design

The launcher icon is drawn entirely in code — no PNG, no SVG file imported from a design tool.

```
        ·          ←  Pulse dot (broadcast source)
      ╭───╮
    ╭─────────╮    ←  Three concentric signal arcs
  ╭─────────────╮
        │          ←  Tower mast
      ──┴──        ←  Base with diagonal feet
```

**VectorDrawable files** (`drawable/`) are used for the launcher icon — Android inflates these natively at the exact density needed, zero bitmap scaling.

**`IconDrawable.kt`** provides a `Canvas`-based `Drawable` and `Bitmap` generator for runtime use (notifications, shortcuts, etc.) — same design, rendered programmatically.

---

## Security Notes

- DroidServe only binds to your **local network IP** — it is not accessible from the internet
- No data is logged, stored, or transmitted anywhere
- Password protection uses HTTP Basic Auth — credentials are Base64-encoded, not encrypted; use on trusted networks
- Path traversal attacks (`../`) are blocked at the request parsing layer
- Hidden files (`.dotfiles`, `__system`) are never listed or served

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Required to open a server socket |
| `FOREGROUND_SERVICE` | Keeps the server alive while using other apps |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type for Android 10+ |
| `READ_EXTERNAL_STORAGE` | SAF handles this via URI permissions — no blanket storage access |

DroidServe requests **scoped storage access only** to the folder you explicitly choose. It never requests `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`.

---

## Known Limitations

- **HTTP only** — no HTTPS/TLS. Use on trusted local networks.
- **Single folder** — you share one root folder per server instance
- **No upload** — read-only; the server cannot receive files
- **SAF limitations** — some system folders (Downloads, root) may not be selectable on all devices depending on OEM restrictions

---

## Contributing

PRs welcome. Please keep changes focused — one concern per PR.

```bash
# Run lint
./gradlew lintDebug

# Run unit tests
./gradlew testDebugUnitTest
```

---

## License

```
MIT License

Copyright (c) 2025 Deekshith

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

<div align="center">
Built with Kotlin · Runs on Android · Needs no cloud
</div>