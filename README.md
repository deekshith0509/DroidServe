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
| **Thumbnails & gallery** | Server-rendered image/video thumbnails, a grid/gallery view (toggle, remembered per device), duration/resolution badges, and an in-browser image lightbox — prev/next, zoom, slideshow, swipe, EXIF |
| **Download anything** | Every file row has a ⬇ download button; folders download as ZIP |
| **Music library** | A dedicated 🎵 tab: folder-grouped albums, lazy cover art, search, a folder playlist, and FLAC streaming — in the web UI and on the TV |
| **Send links to the TV** | Paste any video/stream URL on the phone (native field, the Share sheet from any app, or the web 🔗 tab) — DroidServe resolves watch/embed pages to the **direct stream** and it appears on the TV instantly to open in VLC/MX/Kodi. Links that can't be resolved show a ⚠️ badge and open in a browser instead — nothing dead-ends |
| **Playlist import (📋)** | Paste an `.m3u`/`.m3u8` playlist URL or pick a file from disk in the 🔗 tab → Preview shows channel count + sample names → confirm imports all channels into the TV Links screen at once. Supports any EXTM3U source (IPTV, personal playlists, `iptv-org/iptv`) |
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
| **Works when mDNS is blocked** | Many routers block multicast between clients — the TV also does a **single, polite subnet probe** on launch (with an on-screen **🔄 Rescan**) so reachable servers still appear (protected servers included) |
| **Pick, don't guess** | Multiple phones can run servers at once; the TV shows the full list and you choose (no auto-select) |
| **Full D-pad navigation** | Arrow keys move between rows, OK opens folders/files, Back walks up — the file list gets focus by default; search and manual-IP entry are opt-in so text fields never trap the remote |
| **Poster grid** | Thumbnail poster-grid browsing (and list), with video frame-grab / image thumbnails pulled from the server's cached `/thumb` service and a remembered grid/list toggle |
| **Install APKs** | Tap an `.apk` served by the phone → it downloads locally and opens the **system installer** (grant DroidServe "install unknown apps" once). No ADB, no root — the standard sideload flow, in-app |
| **In-app viewers** | Text (incl. `.srt`/`.log`/config), images, and audio open **inside the app** — no dependence on an external viewer that may be missing or broken on old TVs |
| **In-app audio player** | Songs play in-app via the native `MediaPlayer` with the folder as a playlist (Prev/Next, auto-advance, live position) |
| **Native video playback** | Video fires an `ACTION_VIEW` intent so it opens in the device's own player (VLC, MX, Kodi) for hardware decoding and broad codecs — no bundled player |
| **Resume video** | The TV remembers where each video stopped and asks the player to resume there; the resume cache self-prunes if it grows past 100 KB |
| **Subtitles** | Sidecar `.srt`/`.vtt` next to a video are handed to the external player (VLC/MX subtitle extras) |
| **Play on TV (cast)** | Tap 📺 on any video/audio row in the phone web UI; the phone pushes the stream (and any subtitle) to the TV app, which opens it in the native player. The phone becomes the remote |
| **Streaming links (🔗)** | A **Links** screen shows URLs pushed from the phone in real time (long-poll); pick one with the remote to open — direct streams go to your video player, unresolved web pages open in a browser so nothing dead-ends |
| **Music library (🎵)** | A searchable, folder-grouped albums grid with in-app playback (native `MediaPlayer`, folder playlist, live position) |
| **Auth aware** | Password-protected servers prompt for credentials, **remember the password per server** (auto-reconnect next time, re-prompt only if it changed), and stream tokenized media URLs to external players without re-sending the header |
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

## Streaming Links (phone → TV)

Send a web video to the TV without typing anything on the remote. Three ways to add a link on the phone:

- **In-app** — the **📡 Send a link to the TV** card on the main screen: paste (or tap the clipboard icon) and **Send to TV**.
- **Share sheet** — in any app (a browser, a video app, a messenger), **Share → DroidServe → TV**. No need to open DroidServe at all.
- **Web UI** — the **🔗** tab in the browser file UI.

Each link is pushed to the TV's **🔗 Links** screen in real time (a long-poll channel, persisted so it survives restarts). Pick one with the remote and it opens in your player.

**Smart resolving.** Most "watch"/embed links are an HTML page, not a direct stream — handing that to a video player just bounces straight back out. DroidServe fetches the page once and extracts the real media URL (the player's `<source>`/`<video>`, an `og:video` tag, or an HLS/MP4 embed config), so the TV gets something VLC/MX/Kodi can actually stream. The decision is made by the response **`Content-Type`**, never the file extension. If a page genuinely can't be resolved, the link shows a **⚠️ badge** in the 🔗 tab and opens in a browser on the TV — nothing dead-ends. Direct URLs and `magnet:` links pass through untouched.

> Generic and best-effort by design — it is not a per-site scraper (no DRM/obfuscated-player extraction). The server must be running for the TV to receive links.

### Importing an M3U playlist

To load an IPTV channel list (or any `.m3u`/`.m3u8` playlist) onto the TV:

1. Open the **🔗 Links** tab in the web UI
2. Scroll to **Import playlist** at the bottom
3. Paste the playlist URL (e.g. `https://iptv-org.github.io/iptv/index.m3u`) and tap **Preview**
4. Confirm the channel count — tap **Import all**
5. Every channel appears on the TV's **🔗 Links** screen, individually selectable

Or pick a `.m3u` file from your device using the file picker — it's read, parsed, and imported in one step. Up to 500 channels per import (oldest links evicted when the list is full). No outbound calls; no per-site rules — the server imports whatever URLs the playlist contains.

---

## HTTP API Routes

The native-client API used by the TV app and accessible from any HTTP client.

| Route | Method | Description |
|---|---|---|
| `/api/info` | GET | Server facts (name, IP, port, auth status, API version) |
| `/api/list` | GET | Directory listing as JSON for the root folder |
| `/api/list/<path>` | GET | Directory listing for a subfolder |
| `/api/music` | GET | Full recursive music library (folder-grouped albums) |
| `/api/cast?path=<rel>` | GET | Push a file from the server to the TV (cast) |
| `/api/tv/poll` | GET | TV long-poll — waits up to 25 s for the next cast command |
| `/api/links` | GET | Current streaming-links list `{version, links[]}` |
| `/api/links/add?url=<url>` | GET | Add one URL; resolves watch/embed pages to direct stream |
| `/api/links/remove?id=<id>` | GET | Remove one link by ID |
| `/api/links/clear` | GET | Remove all links |
| `/api/links/poll?since=<v>` | GET | Long-poll for the next links-list change |
| `/api/links/import` | **POST** | Import M3U playlist from request body → `{version, links[], added, skipped}` |
| `/api/links/import?url=<url>` | **POST** | Server fetches M3U from URL, imports → same response |
| `/api/links/import/preview?url=<url>` | GET | Preview channel count + sample titles, no write → `{count, sample[]}` |
| `/thumb/<path>?w=160\|320\|480` | GET | Cached WebP thumbnail |
| `/meta/<path>` | GET | Media metadata (dimensions, duration, title, artist) |
| `/<path>` | GET/HEAD | File serving (range requests, `?m3u=1`, `?vtt=1`, `?dl=1`, `?zip=1`) |
| `*` | OPTIONS | CORS preflight |

All responses include `Access-Control-Allow-Origin: *`. Token auth via `?tok=<token>` works on every route for external player compatibility.

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
app/src/main/java/com/deekshith/droidserve/     # Phone server
├── MainActivity.kt          # Compose UI — folder picker, settings, status, QR, live log
├── HttpServer.kt            # NanoHTTPD server + ServerForegroundService
│                            #   request routing, file/dir/ZIP serving, ranges, auth,
│                            #   .m3u + .vtt endpoints, network-change IP refresh
│   ├── DirectoryCache       # TTL-LRU cache for SAF listings
│   └── FileEntry            # Lightweight SAF record (replaces DocumentFile)
├── FileUtils.kt             # MIME, HTML builder + in-browser player + 🔗/🎵 tabs, SRT→VTT
├── ApiJson.kt               # Hand-rolled JSON for the /api/* native-client API
├── CastQueue.kt             # Real-time phone→TV cast channel (library "Play on TV")
├── LinkStore.kt             # Persisted, versioned streaming-links list + long-poll; addBatch for playlist import
├── StreamResolver.kt        # Resolves watch/embed pages to a direct media URL
├── PlaylistImport.kt        # Pure EXTM3U parser — no Android deps, fully JVM-testable
├── IoUtils.kt               # readCapped — bounded InputStream→String, shared across resolvers
├── ShareActivity.kt         # Share-sheet target ("Share → DroidServe → TV")
├── App.kt                   # Application — attaches LinkStore at process start
├── NsdAdvertiser.kt         # mDNS/NSD advertising (_droidserve._tcp)
├── NetworkUtils.kt          # Local IP detection (multi-interface fallback)
├── ServerStateHolder.kt     # Shared reactive state (StateFlow + AtomicInteger)
├── ServerLog.kt             # In-memory request log ring buffer
├── Diagnostics.kt           # Device/app/storage facts for the dashboard
└── Icondrawable.kt          # Programmatic Canvas icon — no raster assets

tv/src/main/java/com/deekshith/droidserve/tv/    # Android TV companion
├── MainActivity.kt          # Compose 10-foot UI + in-app text/image/audio viewers
├── BrowseViewModel.kt       # Screen state machine, discovery, playback routing
├── DroidServeClient.kt      # HTTP client for the /api/* JSON API
├── ServerDiscovery.kt       # mDNS/NSD discovery (multicast lock, serialized resolves)
├── SubnetScanner.kt         # Polite subnet-probe fallback when mDNS is blocked
├── CredentialStore.kt       # Per-server remembered passwords
├── PlaybackStore.kt         # Video resume positions (100 KB self-pruning cache)
├── Models.kt                # DiscoveredServer / RemoteEntry / ServerInfo / CastCommand / StreamLink
└── App.kt                   # Global crash-to-file handler
```

---

## Setup

### Install from source

```bash
# Clone the repo
git clone https://github.com/deekshith0509/DroidServe.git
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

### Sending a link to the TV

With the TV app connected to the phone, push a web video to it without typing on the remote:

1. On the phone, use the **📡 Send a link to the TV** card (paste or tap the clipboard icon → **Send to TV**), **or** Share a link from any app → **DroidServe → TV**, **or** the **🔗** tab in the web UI
2. On the TV, open the **🔗 Links** screen — the link appears instantly
3. Press **OK** to open it; direct streams go to your video player (VLC/MX/Kodi), web pages open in a browser

Watch/embed pages are resolved to their direct stream automatically. The server must be running for the TV to receive links.

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