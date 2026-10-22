# DroidServe — Media Powerhouse Roadmap

**Date:** 2026-10-22
**Status:** Draft roadmap for review (program-level; each epic gets its own design spec + plan)
**Theme:** Make DroidServe the best-in-class, zero-config **file-sharing + streaming** server for a home LAN — gorgeous, visual, plays-anything — across phone, laptop/browser, and the Fire TV companion. **No ADB, no device control** (that's the separate TvToolsPro app).

---

## 1. Vision

Point any device on the Wi-Fi at DroidServe and get an instant, beautiful media experience: thumbnail-rich browsing, a polished in-app/in-browser player that plays virtually anything, correct handling of every file type, and frictionless transfer — with no cloud, no accounts, no telemetry, and no setup beyond picking a folder.

### Non-goals
- ADB / device remote-control / silent APK install / shell → **TvToolsPro's domain**, not here.
- Rooting, OTA, system modification.
- Internet relay / cloud sync. DroidServe stays **LAN-first** (optional self-signed HTTPS for secure local streaming is in scope; public exposure is not).

---

## 2. Principles (preserve the existing ethos)

The codebase has a clear, deliberate style. New work must honor it:

- **Dependency-light.** The app hand-rolls JSON (`ApiJson`), base64, percent-encoding, its own HTTP client on the TV, and a Canvas icon. Every new dependency must earn its place (the one likely-justified exception: a real media player on the TV — see §4.4).
- **Performance-first.** SAF batch queries, TTL-LRU directory cache, zero-copy file serving, pre-compiled statics, pre-warmed thread pool. New endpoints (thumbnails, metadata) must cache aggressively and never add per-request allocation to the hot path.
- **Zero-copy / streaming.** Never buffer whole media in memory; stream and range everything.
- **No telemetry, scoped storage (SAF), LAN-only, graceful degradation.** Old/slow TVs and flaky Wi-Fi must still work; features degrade, never crash.
- **D-pad / 10-foot first.** Everything must be navigable with a remote; focus rings, overscan padding, no focus traps.
- **Unit-testable core.** Pure logic (parsers, MIME, SRT→VTT, JSON) stays framework-free and JVM-tested, like `ApiJsonTest` / `LogicTest`.

---

## 3. Current state (honest inventory)

**Server (`app/`, NanoHTTPD in `HttpServer.kt`)**
- Routes: HTML listing, raw file serve with HTTP **range** support, `?dl=1` force-download, `?zip=1` folder ZIP, `?m3u=1` playlist handoff, `?vtt=1` SRT→WebVTT, `/api/info`, `/api/list`, `/api/cast`, `/api/tv/poll`. Auth: Basic + session cookie + `?tok=` link token. `DirectoryCache` (TTL-LRU), `FileEntry` (lightweight SAF row).
- **MIME map** (`FileUtils.getMimeType`): ~40 types; unknown → `text/plain`. Gaps: HEIC/HEIF, ts/m2ts/mpg/wmv/flv/3gp, opus/wma/aiff/mid, epub, office formats, rar, etc.

**Web UI (`FileUtils.buildHtml`, server-rendered single page)**
- Strong: dark/light theme, current-folder filter, sort, breadcrumbs, D-pad spatial nav, TV detection, in-browser **player overlay** (video/audio, playlist prev/next + auto-advance, subtitle tracks via `?vtt=1`, resume via `localStorage`, `.m3u` fallback for undecodable codecs), Android `intent://` "open in app", "Play on TV" cast, download + ZIP.
- **Gaps:** emoji-icon **list only** — no thumbnails, no grid/gallery, no image lightbox, no media metadata (duration/resolution), folder-only (not recursive) search, undecodable formats only offer download.

**TV app (`tv/`, Compose)**
- Discovery (mDNS + subnet), browse via `/api/list`, in-app text/image/audio viewers, **external** video player (VLC/MX via `ACTION_VIEW`), cast receiver (long-poll), resume positions, saved passwords.
- **Gaps:** list-only (no posters/thumbnails/grid), **no in-app video player**, no continue-watching row, no library view, no APK install.

---

## 4. Shared technical foundations

These underpin multiple epics; build once, reuse.

### 4.1 Thumbnail service — `/thumb?path=<rel>&w=<px>` (cached)
- **Images:** `ContentResolver.openInputStream` → `BitmapFactory` with `inSampleSize` downscale → re-encode WebP/JPEG. HEIC/HEIF decodes natively on API 28+ (our Fire TV) — serving a JPEG thumb also solves browsers that can't display HEIC.
- **Video:** `ContentResolver.openFileDescriptor(uri,"r")` → `MediaMetadataRetriever.setDataSource(fd)` → `getFrameAtTime` (+ `extractMetadata` for duration/width/height/rotation, reused by §4.2).
- **Audio:** `MediaMetadataRetriever.getEmbeddedPicture` → album art (fallback to a generated note tile).
- **PDF:** `PdfRenderer` first page where the provider gives a seekable fd.
- **APK:** copy to cache → `PackageManager.getPackageArchiveInfo` → app icon (lazy/optional; flagged for cost).
- **Cache:** disk LRU in `cacheDir/thumbs` keyed by `(uriHash, mtime, size, w)`; bounded (~64 MB) with startup prune (mirrors `PlaybackStore.pruneIfLarge`). Long `Cache-Control` + `ETag`; in-flight de-dup so N grid cards don't regenerate the same frame. Generation runs on the existing pool, bounded concurrency.

### 4.2 Metadata in `/api/list` (+ web `data-*`)
- Add optional `durationMs`, `width`, `height` to `ApiJson.Row` and the HTML `data-*`. Backward-compatible (consumers ignore unknown keys). Cached alongside thumbnails (same retriever pass). Powers duration badges, resolution labels, poster aspect, and E2 seek-preview.

### 4.3 Web grid/gallery
- CSS-grid card view (thumbnail, name, type/duration badge), **list⇄grid toggle** persisted in `localStorage`, lazy thumbnail loading via `IntersectionObserver`. Reuses existing filter/sort/D-pad nav. No framework.

### 4.4 TV in-app player — **androidx.media3 (ExoPlayer)** *(the one justified dependency)*
- Replaces the external-player punt with a hardware-decoded in-app player that handles mkv/HEVC/AC3/etc. and standard subtitle/audio tracks. `minSdk 21` compatible. This is the pragmatic "play all formats **on the TV**" answer — no server transcoding needed for the companion app. Justified because a correct 10-foot player is otherwise enormous to hand-roll.

### 4.5 PWA (web)
- Web app manifest + service worker for an installable, offline-shell home-screen app on phones/laptops. Pure static additions; no server framework.

---

## 5. Epics

### E1 — Visual foundation *(keystone; build first)*
The single biggest "best-in-class UI" leap, and the substrate E2/E4 require.
1. `/thumb` service (§4.1) + cache.
2. Metadata in `/api/list` and web `data-*` (§4.2).
3. Web **grid/gallery** view + list⇄grid toggle (§4.3); duration/resolution badges.
4. In-browser **image lightbox**: prev/next across folder images, zoom, swipe, slideshow, basic EXIF (from §4.2 metadata).
5. TV app: **poster grid** browse (fetch `/thumb`, bounded bitmap cache), thumbnails in rows.
**Exit:** browsing any folder of photos/videos shows real thumbnails in a grid on web + TV; images open in a lightbox.

### E2 — Advanced streaming
1. TV **in-app video player** via Media3 (§4.4): resume, subtitle + audio **track pickers**, auto-next.
2. **Seek-preview** thumbnails on the scrubber (sprite sheet from §4.1) — web player first, then TV.
3. **"Continue watching"** row — promote resume from per-URL `localStorage` to a server-tracked, cross-device list (new small `/api/resume` state).
4. **Music mode**: album-art view, album/artist grouping, queue, gapless, `.lrc` lyrics.
5. *(Optional, investigated separately)* browser-side **remux/transcode** for codecs no browser decodes. **Risk:** ffmpeg-kit was retired (2025); needs a maintained build or `MediaCodec` remux — deferred, opt-in.
**Exit:** videos play beautifully in-app on the TV with track selection + resume; web player shows seek previews; a continue-watching row appears.

### E3 — Universal file handling + APK install
1. **Complete the MIME map** + inline-preview coverage (HEIC/HEIF, ts/m2ts/mpg/wmv/flv/3gp, opus/wma/aiff/mid, epub, office, rar, …); fix "unknown→text" mis-serves (don't label binaries as text).
2. **Correct APK install** (no ADB): browser → download-local then OS "open" launches the system installer (unknown-sources flow); **TV app** → download to cache + `FileProvider` + `ACTION_VIEW` install intent + `REQUEST_INSTALL_PACKAGES`, with a guided "allow unknown sources" prompt.
3. **Edge cases:** huge folders, zero-byte files, exotic/Unicode names, very long paths, broken/partial media, non-UTF-8 subtitles, symlink/dup handling, concurrent downloads.
**Exit:** every common file type previews or opens correctly; APKs install cleanly from web and TV; a fuzz/edge test pass is green.

### E4 — Outside-the-box power
1. **Media library**: auto-categorize Movies/Shows/Music/Photos with poster walls (heuristics over the shared tree).
2. **Recursive global search** across the whole share (new indexed/streamed search endpoint).
3. **Multi-select** + batch download/ZIP in web + TV.
4. **Share links**: QR + optional expiry, read-only scoped tokens.
5. **PWA install** (§4.5) + keyboard-shortcut polish.
6. *(Optional)* self-signed **HTTPS** for encrypted LAN streaming; **two-way upload/receive**; **DLNA/UPnP** server so appless smart TVs can stream.
**Exit:** the share feels like a product — a searchable, categorized, shareable, installable media hub.

---

## 6. Outside-the-box catalog (tagged by epic)

| Feature | Epic | Note |
|---|---|---|
| Real thumbnails everywhere | E1 | keystone |
| Grid/gallery + list toggle | E1 | best-in-class UI |
| Image lightbox (zoom/slideshow/EXIF) | E1 | |
| Media metadata badges (duration/res) | E1 | |
| TV in-app player (Media3) | E2 | plays all formats on TV |
| Seek-preview sprites | E2 | |
| Continue-watching (cross-device) | E2 | |
| Music mode (art/queue/gapless/lyrics) | E2 | |
| Browser transcode/remux | E2 | optional, ffmpeg risk |
| Complete MIME + previews | E3 | correctness |
| APK install (web + TV, unknown-sources) | E3 | the explicit ask |
| Edge-case hardening + tests | E3 | |
| Auto media library + posters | E4 | |
| Recursive global search | E4 | |
| Multi-select batch zip | E4 | |
| Share links (QR + expiry) | E4 | |
| PWA installable + offline shell | E4 | |
| Self-signed HTTPS | E4 | optional |
| Two-way upload/receive | E4 | optional |
| DLNA/UPnP server | E4 | optional, broad reach |

---

## 7. Sequencing, delivery & versioning

- **Order:** E1 → E2 → E3 → E4. E1 is a hard prerequisite for E2 (seek-preview) and E4 (posters); E3 is independent and can slot earlier if APK install is urgent.
- **Cadence:** one epic per minor version — E1 → **1.3**, E2 → **1.4**, E3 → **1.5**, E4 → **1.6+**. Each epic lands in focused commits with its own design spec + implementation plan; TDD on the pure core (MIME, parsers, cache keys, metadata).
- **Branching:** default to `main` (per your preference) with tagged releases per minor; large epics may use a short-lived branch.
- **Each epic:** own `docs/superpowers/specs/<date>-droidserve-<epic>-design.md` → `writing-plans` → implement → verify on the real Fire TV FX + a browser.

---

## 8. Risks & decisions to settle

| Risk / decision | Resolution path |
|---|---|
| SAF thumbnail generation is slow on first browse of big folders | Aggressive disk cache + lazy load + bounded concurrency; generate on-demand per visible card |
| Thumbnail cache fills TV/phone storage | Bounded LRU (~64 MB) + startup prune |
| Media3/ExoPlayer adds app size + a dependency vs the dependency-light ethos | Accept for the TV module only; it's the standard, minSdk-21 player; keep the phone server dependency-free |
| Browser "play anything" via transcoding — ffmpeg-kit retired (2025) | Treat as optional/deferred; prefer native-player handoff + TV in-app player; investigate MediaCodec remux separately |
| APK install from `http://` can't install remotely | Mandatory download-local → system installer; never attempt install from a URL |
| HEIC not displayable in most browsers | Serve JPEG thumbnail + optional JPEG full-view transcode for HEIC |
| PdfRenderer needs seekable fd (some providers) | Degrade to generic icon where unavailable |

---

## 9. Open questions before the E1 spec

1. **Thumbnail format:** WebP (smaller, needs API ≥ 14 encode — fine) vs JPEG (universal). Lean WebP, JPEG fallback.
2. **Grid as default or opt-in?** Default to grid for image/video-heavy folders, list for document folders (auto-heuristic) — or a simple persisted user toggle. Lean: persisted toggle, remember per session.
3. **TV thumbnails now or defer to E1.5?** Including the TV poster grid in E1 doubles the UI surface; could ship web-grid first (E1a) then TV posters (E1b).
4. **EXIF depth** in the lightbox: basic (date/dimensions/camera) vs full. Lean basic.

---

*Next step after your review: pick/confirm Epic 1 scope (and the §9 answers), then I write the Epic-1 design spec and we implement test-first.*
