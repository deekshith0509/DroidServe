# DroidServe — Epic 1: Visual Foundation (Design)

**Date:** 2026-10-22
**Status:** Draft for review → then `writing-plans`
**Parent:** `2026-10-22-droidserve-media-powerhouse-roadmap.md` (Epic E1)
**Target release:** 1.3 · **No ADB** · phone server (`app/`) + web UI + TV app (`tv/`)

---

## 1. Goal & exit criteria

Make browsing **visual**: real thumbnails and media metadata everywhere, a grid/gallery view, and an in-browser image lightbox — the keystone the rest of the program builds on.

**Done when:**
- Browsing a folder of photos/videos/music shows **real thumbnails** in both the web UI and the TV app.
- Web UI has a **grid⇄list** toggle (persisted) with duration/resolution badges.
- Clicking an image on the web opens an **in-browser lightbox** (prev/next, zoom, swipe, slideshow, basic EXIF).
- Thumbnails are generated **lazily, cached on disk, bounded**, and never block or slow directory listings; the server stays responsive on a phone serving a 10 000-file folder.
- Pure logic is JVM-unit-tested; the real Fire TV FX + a desktop browser pass a manual check.

## 2. Decisions (baked from roadmap §9 + approval)
- **Thumbnail format:** WebP, quality ~80 (universal JPEG fallback only if WebP encode fails).
- **Grid:** persisted user toggle (`localStorage`), **default grid**.
- **EXIF:** basic (dimensions, date, camera model) in the lightbox.
- **TV posters:** included in this epic as **Phase 3** (you operate the TV — it ships in E1, sequenced last).
- **Transcoding / HEIC full-view / library / search:** out of scope (E2–E4). HEIC *thumbnails* are in (API 28 decodes natively).

---

## 3. Architecture

```
/api/list row gains thumbUrl (cheap string; no decode) ─┐
                                                         ▼
Web grid  ──lazy <img src="/thumb/<path>?w=320">──▶  HttpServer route "thumb/"
TV grid   ──ThumbLoader (bounded Bitmap LRU)──────▶      │  resolveFast(path) → FileEntry
                                                         ▼
                                              ThumbCache (disk LRU, bounded, pruned)
                                                 miss ▼         ▲ hit (zero decode)
                                              ThumbGen (interface)
                                                 ├─ image: bounds-decode + inSampleSize downscale
                                                 ├─ video: MediaMetadataRetriever.getFrameAtTime
                                                 ├─ audio: embeddedPicture (fallback: note tile)
                                                 └─ (pdf/apk: deferred within E1 — generic icon)
/meta/<path> ──▶ cached {w,h,durationMs,exif?}  (same retriever pass; lazy badges)
```

**Key rule:** directory listing stays as fast as today. `/api/list` only adds a **thumbUrl string** (built from type + path, no media I/O). All decoding happens off the listing path, on first `/thumb` or `/meta` hit, then served from the disk cache.

---

## 4. Components

### 4.1 Server — thumbnail service
- **Route** (in `HttpServer.dispatch`, mirroring the `api/list/` branch): `GET /thumb/<encoded-rel-path>?w=<160|320|480>`.
  - `w` clamped to the allowlist `{160,320,480}` (bounds cache variants). Default 320.
  - Resolve via `resolveFast(sub)` → `FileEntry` (reuses traversal guards + auth already enforced at top of `dispatch`; `?tok=` works for `<img>` when auth is on).
  - 404 for missing/dir/non-thumbnailable; on decode failure return **204** (client shows the type icon) — never 500.
- **`ThumbCache`** (new `ThumbCache.kt`): disk LRU under `context.cacheDir/thumbs/`.
  - Key: `sha1(docId) + "_" + w`; file `<key>.webp`. Validity tied to `mtime+size` encoded in an **ETag** `"<docIdHash>-<mtime>-<size>-<w>"`; honor `If-None-Match` → **304**.
  - Bounded (~64 MB); evict LRU by file mtime; **prune on server start** (mirrors `PlaybackStore.pruneIfLarge`). Persists across restarts (that is the speedup) — never cleared on stop, only size-pruned.
  - Response headers: `Cache-Control: public, max-age=604800, immutable`, `ETag`, `Content-Type: image/webp`.
- **`ThumbGen`** (interface, so cache/routing logic is JVM-testable with a fake; real impl uses Android):
  - `image/*`: `BitmapFactory` bounds-decode (`inJustDecodeBounds`) → compute `inSampleSize` → decode scaled → center-crop/fit to `w` → WebP encode. **Never** full-decode a large bitmap.
  - `video/*`: `openFileDescriptor` → `MediaMetadataRetriever.setDataSource(fd)` → `getScaledFrameAtTime`/`getFrameAtTime` → WebP. Same pass yields duration/width/height for `/meta`.
  - `audio/*`: `getEmbeddedPicture` → decode+scale; fallback to a generated note tile (or 204).
  - **Concurrency guard:** a `Semaphore(3)` + in-flight de-dup map (`path→Deferred`) so a 50-card grid never spawns 50 retrievers or regenerates the same frame.
- **`/meta/<path>`** route → JSON `{"width":_,"height":_,"durationMs":_,"exif":{...}?}` from the same cached pass (images via `ExifInterface`). Lazy; cached in a small persisted index. Powers badges + lightbox EXIF.

### 4.2 Server — listing contract
- `ApiJson.Row` + `apiListResponse`: add optional **`thumbUrl`** (e.g. `"/thumb/<encoded>?w=320&tok=…"`) for thumbnailable types (image/video/audio; pdf/apk later). String-only — no decode, no listing slowdown. Backward compatible (TV app ignores unknown keys / old servers omit it).
- `FileUtils`: add `isThumbnailable(name)` + `thumbUrlFor(relPath, w, tokAmp)` helpers (pure, unit-tested), reused by web HTML and `apiListResponse`.

### 4.3 Web UI (`FileUtils.buildHtml`)
- **Grid markup:** each file card carries `data-thumb` (the thumb URL) + existing `data-*`. New CSS `.grid` (responsive `grid-template-columns`) vs current `.list`. **Toggle** chip (⊞ Grid / ☰ List) persisted in `localStorage`, default grid; re-uses existing sort/filter/D-pad nav.
- **Lazy thumbs:** native `<img loading="lazy" src=data-thumb>` with the type emoji as the placeholder/onerror fallback (zero extra JS framework). Duration/resolution badge fetched lazily via `/meta` per visible card (IntersectionObserver), cached client-side.
- **Lightbox** (new overlay, sibling to the existing player overlay): opens on image click (intercept before the Android `intent://` path for images), prev/next across the folder's images, zoom (CSS transform + wheel/pinch), swipe (touch), slideshow timer, basic EXIF from `/meta`. D-pad: ←/→ navigate, Enter zoom, Back/Esc close. Reuses palette + focus idiom.
- Keep the Android `intent://` "open in app" available for non-image files; images now prefer the in-browser lightbox (best-in-class, no app-switch).

### 4.4 TV app (`tv/`) — Phase 3
- `Models.RemoteEntry`: parse `thumbUrl` (+ `isThumbnailable`).
- **`ThumbLoader`** (new): bounded in-memory `Bitmap` LRU (e.g. `LruCache` sized to ~1/8 heap) + off-main decode via the existing `HttpURLConnection` pattern (no image dependency — matches the dep-light ethos). De-dup in-flight.
- `BrowseScreen`: a **poster grid** (`LazyVerticalGrid`) mode + list⇄grid toggle chip (persisted via a tiny pref), poster cards with thumbnail, name, duration badge, D-pad focus ring; thumbnails also shown as leading art in list rows.
- No new dependency, no new permission.

---

## 5. Performance, memory, security
- **Listings unchanged:** no media decode in `/api/list` or `buildHtml`; only string URLs added.
- **Memory:** bounds-decode + `inSampleSize` so a 48 MP photo never fully decodes; WebP output is small; `Semaphore(3)` caps concurrent native decoders.
- **Disk:** thumb cache bounded ~64 MB, LRU-pruned on start; survives restarts (persistent speedup).
- **Security:** `/thumb` + `/meta` sit behind the same `checkAuth` gate as everything else (top of `dispatch`), accept `?tok=` for `<img>`, and resolve through `resolveFast` (per-segment `..`/hidden guards). No new permissions; LAN-only unchanged.

## 6. Edge cases
- Non-thumbnailable / unknown type → type icon (no request or 204).
- Corrupt/partial media, retriever throws → 204 + icon fallback (never 500).
- 0-byte / missing size → skip thumb.
- Huge images → always downscaled via bounds-decode.
- Provider without seekable fd (video/pdf) → 204 fallback.
- Auth on → thumb/meta URLs carry `tok`; off → plain.
- Old server / new client (or vice-versa) → `thumbUrl` optional; both degrade cleanly.

## 7. Testing (TDD — write first)
**JVM unit (`app/src/test`, no device):**
- `ThumbCache` key + ETag generation (stable across calls; changes with mtime/size/w).
- Prune threshold logic (evicts oldest past the cap).
- `/thumb` `w` clamping + path/param parsing + non-thumbnailable rejection (with a fake `ThumbGen`).
- `FileUtils.isThumbnailable` + `thumbUrlFor` (types, encoding, tok suffix).
- `apiListResponse` emits `thumbUrl` only for thumbnailable rows (extend `ApiJsonTest`).

**JVM unit (`tv/src/test`):** `RemoteEntry.isThumbnailable` + `thumbUrl` parse; `ThumbLoader` LRU bound (pure cache logic behind an interface).

**Manual (real Fire TV FX + desktop browser):** grid renders thumbs; lazy-load scroll; lightbox prev/next/zoom/slideshow; big-folder responsiveness; auth-on thumbs; TV poster grid + focus nav.

## 8. Phasing (each a focused commit; TDD per phase)
1. **P1 — server thumb/meta + listing `thumbUrl`.** `ThumbCache`, `ThumbGen`, routes, concurrency, prune; `ApiJson`/`FileUtils` helpers. Exit: `GET /thumb/<img>` and `/thumb/<video>` return cached WebP; `/meta` returns dims/duration; units green.
2. **P2 — web grid + lightbox.** Grid/list toggle, lazy thumbs, badges, image lightbox. Exit: visual grid + lightbox in a browser.
3. **P3 — TV posters.** `ThumbLoader`, grid mode, row art. Exit: poster grid on the Fire TV.

## 9. Files
- **New:** `app/.../ThumbCache.kt` (+ `ThumbGen` iface/impl, maybe `MediaMeta.kt`); tests under `app/src/test` + `tv/src/test`.
- **Edit (app):** `HttpServer.kt` (routes + prune-on-start hook), `ApiJson.kt` (+`thumbUrl`), `FileUtils.kt` (helpers + web grid/CSS/JS + lightbox).
- **Edit (tv):** `Models.kt`, `DroidServeClient.kt`, `BrowseViewModel.kt`, `MainActivity.kt` (+ `ThumbLoader`).
- **Edit (root):** `README.md` (thumbnails/grid), `tv/build.gradle.kts` + `app/build.gradle.kts` version bump to 1.3 at epic end.
- **No new dependencies, no new permissions.**

## 10. Out of scope (later epics)
In-app TV video player / seek-preview / continue-watching (E2); full MIME completion + APK install + edge-case fuzz (E3); media library, recursive search, share links, PWA (E4).
