# Architecture

## Modules

- `:app` — Android phone app. Hosts the NanoHTTPD server, web UI, foreground service, and link management.
- `:tv` — Android TV app. Discovers servers, browses files, plays media, and runs the IPTV channel player.

## Key components (phone)

- `HttpServer` — request routing, file serving, ZIP, thumbnails, and the `/api/*` JSON API.
- `LinkStore` — persisted streaming-link list with versioning and long-poll.
- `StreamResolver` / `LinkClassifier` / `PlaylistImport` / `M3uExport` — pure, framework-free link classification, M3U parsing/export, and watch-page resolution.
- `LinkIngest` — unified entry point for adding a URL (resolves watch pages, expands playlists).

## Key components (TV)

- `BrowseViewModel` — screen state machine and business logic.
- `DroidServeClient` — HTTP client for the phone server.
- `TvPlaylist` — M3U parse/export for the native player.
- `StreamResolver` / `LinkClassifier` / `WebViewResolver` — ported resolution pipeline with WebView fallback.
- `VlcLauncher` — generates header-aware M3U and hands off to VLC/MX.
- `ChannelPlayerScreen` — Media3-based IPTV player with D-pad navigation.

## Data flow

Phone serves files and links over HTTP on the LAN. The TV discovers the phone via mDNS/NSD (with a subnet-probe fallback), then browses and streams directly. Streaming links pushed from the phone appear on the TV in real time via long-poll. All heavy I/O runs on `Dispatchers.IO`; UI state is a single `StateFlow<UiScreen>`.
EOF