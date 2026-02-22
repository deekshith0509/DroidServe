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
| **Instant file browser** | Clean dark web UI with search, sort, file icons |
| **Stream media** | Video and audio play directly in the browser via HTTP range requests |
| **Download anything** | Every file has a ⬇ download button; folders download as ZIP |
| **QR code** | Auto-generated QR — point another phone's camera to connect instantly |
| **Password protection** | Optional HTTP Basic Auth to lock access |
| **Foreground service** | Server keeps running while you use other apps |
| **System restart survival** | Server auto-restarts if Android kills and recreates the service |
| **CORS headers** | All responses include proper CORS headers for cross-origin use |
| **HEAD request support** | Proper HEAD handling for clients that probe before downloading |
| **Adaptive icon** | Fully vector icon — crisp at every size, themed icon support (Android 13+) |

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
Min SDK         Android 5.0 (API 21)
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
- Device running Android 5.0+

### Dependencies (`app/build.gradle`)

```groovy
dependencies {
    implementation 'fi.iki.elonen:nanohttpd:2.3.1'
    implementation 'androidx.documentfile:documentfile:1.0.1'
    implementation 'androidx.compose.material3:material3:1.2.0'
    implementation 'com.google.zxing:core:3.5.3'
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

### Downloading Files

- **Click a filename** → opens/streams in browser (images, video, audio, PDF)
- **Click ⬇** → forces download to your device
- **Click ⬇ ZIP** on a folder → downloads entire folder as a `.zip`

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