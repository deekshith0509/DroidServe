# DroidServe — HTTP Core Decomposition + Playlist Import Integration Plan

**Date:** 2026-10-25
**Status:** Ready to execute — §8 decisions resolved against the codebase (2026-10-25); §5 updated to match
**Companion to:** `docs/superpowers/specs/2026-10-22-droidserve-media-powerhouse-roadmap.md`
**Scope:** (1) Decompose `HttpServer.kt` into focused, testable units with zero behavior change. (2) Add batch `.m3u`/`.m3u8` import on top of the existing Links pipeline. Both land as independent, revertible phases — the restructuring ships and soaks *before* the new feature starts, so any regression is always attributable to exactly one change, not two at once.

---

## 1. Why these two things are one plan

`HttpServer.kt` is 1,642 lines doing two unrelated jobs — NanoHTTPD routing and Android `Service` lifecycle — and it's about to grow further: the roadmap's E1/E2 add `/thumb`, `/api/resume`, and seek-preview routes to the same file. Splitting first means new work lands on a clean surface instead of becoming line 1,643 of the thing we already agree is too big. Playlist import is the actual answer to "what about IPTV `index.m3u`" — not a channel/EPG platform (out of scope, see §2), just teaching the existing `LinkStore`/`StreamResolver` pipeline to accept many URLs at once instead of one.

## 2. Non-goals (restated from the roadmap because they bound this plan)

- No channel guide, EPG/XMLTV, channel-groups-as-UI, or multi-source playlist federation. `group-title` is parsed as data in this plan, not built into a screen (and per §8.2/§5.1 it is *not even stored* — see there).
- No provider scraping, no DRM/obfuscated-player extraction — imports are URLs the user already has, same line `StreamResolver` already draws.
- No new Gradle module, no new external dependency. Both phases use only what's already in `app/build.gradle.kts`.
- No new Android Service, no change to foreground-service *behavior* — only its file location moves.

## 3. Zero-regression strategy

This is the part that matters most, so it comes before the task breakdown.

### 3.1 The actual gap today
Eight unit test files exist (`ApiJsonTest`, `LinkStoreTest`, `StreamResolverTest`, `CastQueueTest`, `FileUtilsThumbTest`, `ThumbCacheTest`, `ApiJsonThumbTest`, `LogicTest`) and they're good — but none exercises `HttpServer` itself through NanoHTTPD, and `.github/workflows/build.yml` currently only runs `./gradlew assembleDebug`. It never runs the unit tests. Concretely: **every one of those eight files could be broken right now and CI would stay green.** That's the real risk to "no regressions," not the refactor itself — nothing currently proves any change didn't break something. Closing this gap is Phase 0, before any code moves.

### 3.2 Phase 0 — make regressions visible first
1. Add a unit-test step to `build.yml` (`./gradlew testDebugUnitTest`), before the APK build step, failing the build on any red test. One line, immediately raises the floor for everything after it.
2. Add `HttpServerRouteTest.kt`: boot `HttpServer` on an ephemeral port against a temp fixture directory, hit it with real HTTP calls, and assert the routes Phase 1 is about to touch:
   - `GET /` → 200, directory HTML contains the fixture file name
   - `GET /api/list` → 200, JSON shape matches `ApiJson.list` (reuse `ApiJsonTest` fixtures)
   - `GET <file>` with `Range: bytes=0-10` → 206, correct `Content-Range`/`Content-Length`
   - `GET <file>?m3u=1` → 200, `audio/x-mpegurl`, body matches the `#EXTM3U` shape
   - `GET <dir>?zip=1` → 200, `application/zip`
   - No credentials on a password-protected server → 401 with `WWW-Authenticate`; cookie issued on first authenticated request is accepted on the next; `?tok=` works without the header
   - Any path via `OPTIONS` → CORS headers present

   This file is written and passing against the **current, unsplit** `HttpServer.kt` before any extraction begins. It's the regression net for everything in §4.

### 3.3 Rules for every extraction in Phase 1
- One responsibility group per commit, pure move, no logic changes riding along. Spot an actual bug while moving code → note it, fix it in a separate later commit.
- After each commit: full suite (the 8 existing tests + `HttpServerRouteTest`) green, plus a manual pass on **both** the web UI and the TV app — the TV app is a second, independent HTTP client and the easiest regression to miss if only the browser gets checked.
- The wire contract (§3.4) is frozen for the whole phase. A change that would alter it is a separate, explicitly-labeled follow-up, not part of this refactor.
- `constantTimeEquals` and the two `synchronized` blocks (`DirectoryCache`, `LinkStore`) keep their exact comparison/locking semantics when moved. A wrapping collaborator class must not quietly introduce a second lock object or a short-circuiting comparison — both are the kind of bug that only shows up under load or in a timing attack, not in a quick manual check.
- Each commit is independently revertible. If step 7 of 10 misbehaves in the field, steps 1–6 and 8–10 stay merged; only step 7 reverts.

### 3.4 Wire-compatibility checklist (identical before/after Phase 1)
- `ApiJson` output byte-for-byte for the same input (keys, nesting, escaping)
- HTTP status codes and reason phrases on every existing branch
- Header names/casing: `Content-Disposition`, `Content-Range`, `Cache-Control`, `ETag`, the `Set-Cookie` name (`COOKIE_NAME`) and its attributes, CORS headers
- MIME strings from `FileUtils.getMimeType` and the ones hardcoded in `HttpServer` (`audio/x-mpegurl`, `application/zip`, etc.)
- All three auth paths still accepted: header, cookie, `?tok=`
- `?dl=1`, `?zip=1`, `?m3u=1`, `?vtt=1` semantics unchanged, including "disabled server-side → ignored" for `dl`/`zip`

Phase 1 is done when this checklist and `HttpServerRouteTest` both pass against the split code with no edits to either.

## 4. Phase 1 — decompose `HttpServer.kt`

Lowest-risk extractions first; auth last, once the pattern is proven on easier cases.

| Order | New unit | Carries (from current `HttpServer.kt`) | Risk |
|---|---|---|---|
| 1 | `ServerForegroundService.kt` | The whole `ServerForegroundService` class (~lines 1199–end) | Lowest — already a clean boundary, no shared state with `HttpServer` |
| 2 | `DirectoryCache.kt` | The `DirectoryCache` object as-is | Lowest — already self-contained |
| 3 | `HttpHelpers.kt` | `cors`, `errJson`, `contentDisposition`, `disposition`, `httpDate` | Low — stateless |
| 4 | `ZipRoutes.kt` | `zipResponse` | Low |
| 5 | `ThumbRoutes.kt` | `thumbResponse`, `noThumb`, `clampThumbW`, `cacheValidators`, `notModified`, `metaResponse` | Medium — touches the `ThumbCache`/`ThumbGen` collaborators |
| 6 | `MusicRoutes.kt` | `buildMusicJson` | Medium |
| 7 | `FileServingRoutes.kt` | `fileResponse`, `fullResponse`, `rangedResponse`, `listFast`, `resolveFast`, `siblingSubtitleUrl` | Medium-high — range-request correctness is easy to subtly break |
| 8 | `PlaylistRoutes.kt` | `m3uResponse`, `vttResponse`, `playlistBaseName` | Medium — `?m3u=`/`?vtt=` file-serving |
| 9 | `ApiRoutes.kt` | `htmlResponse`, `apiInfoResponse`, `apiListResponse`, `jsonResponse`, `serverFacts`, `cachedInterfaces` | Medium |
| 10 | `AuthGate.kt` | `checkAuth`, `constantTimeEquals`, `authNeeded`, `hasValidCookie`, `hasValidTokenParam`, `unauthorized` | Highest — most sensitive, do last |

Each becomes a small class `HttpServer` constructs once and delegates to — not free functions. These are currently `private` instance methods reading `sessionToken`/`passwordBytes`/`thumbCache`/`options`, so a plain cut-paste into a new file won't compile.

```kotlin
class AuthGate(private val passwordBytes:ByteArray?,private val usernameBytes:ByteArray?,private val sessionToken:String){
fun needed()=passwordBytes!=null||usernameBytes!=null
fun check(session:IHTTPSession):Boolean{...}
fun hasValidCookie(session:IHTTPSession):Boolean{...}
fun hasValidTokenParam(session:IHTTPSession):Boolean{...}
fun unauthorized():Response{...}
private fun constantTimeEquals(a:ByteArray,b:ByteArray):Boolean{...}
}
```

`dispatch()` stays in `HttpServer.kt` as the orchestrator — the one place allowed to stay "central," since collapsing it into ten classes would just relocate the god-object problem into a giant `when` block instead of fixing it. The `api/links/*` when-block also stays in `dispatch()` (it is where per-prefix auth/cookie handling lives — see §5.2). Flat package vs. a new `http/` subpackage is **decided: flat** (§8.1); the codebase is flat today and staying flat is correct at this size — don't let that choice stall the split.

**Exit:** `HttpServerRouteTest` and the wire-compatibility checklist pass unmodified; `HttpServer.kt` is down to `dispatch()` plus wiring; every extracted unit has exactly one job, and any of them holding non-trivial logic has its own test.

## 5. Phase 2 — batch `.m3u` import

Starts only after Phase 1 is merged and has run in the field for a few days, so a problem is obviously attributable to one phase or the other, never both at once.

### 5.0 Prep — two pure, shared-util moves (first commits of Phase 2)

Both are driven by Phase 2's reuse needs and are wire-identical pure moves covered by existing/trivial tests, so they open Phase 2 rather than soaking with Phase 1.

- **`LinkPolicy` — single allow/deny policy.** `LINK_SCHEME` (HttpServer.kt:122) and `isAllowedLink` (HttpServer.kt:653) are private to `HttpServer`; the import filter can't reach them. Lift both into a framework-free `object LinkPolicy { fun isAllowed(url: String): Boolean }`. The single-add route (HttpServer.kt:375) and the import filter then call the *same* policy — not two validators that can drift. Add a trivial `LinkPolicyTest`.
- **Promote `readCapped`.** It is `private` inside `object StreamResolver` (StreamResolver.kt:94) — unreachable from the import route. Lift it to a top-level `internal fun readCapped(input: java.io.InputStream, max: Int): String`; `StreamResolver` and the import route both call it. No behavior change (it already returns UTF-8 text, which `.m3u` is); `StreamResolverTest` still covers its use. Never `.readBytes()` the body unbounded.

### 5.1 `PlaylistImport.kt` — pure parser, JVM-testable, same shape as `StreamResolver`

```kotlin
object PlaylistImport {
data class Entry(val url:String,val title:String,val group:String?)
fun parse(text:String):List<Entry>{
val out=ArrayList<Entry>();var title:String?=null;var group:String?=null
for(raw in text.lineSequence()){val line=raw.trim();if(line.isEmpty())continue
when{
line.startsWith("#EXTINF",true)->{group=Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1);title=line.substringAfterLast(',').trim().ifEmpty{null}}
line.startsWith("#")->{}
else->{out.add(Entry(line,title?:line.take(60),group));title=null;group=null}
}}
return out
}
}
```

`group` is captured so the parser is complete and fully testable, then **discarded at the store boundary** (§5.2). Storing it would churn `LinkStore`'s TSV persist/load format (LinkStore.kt:87,96) for data no screen reads (§2). Add a `Link.group` field only when a group UI is actually scoped.

### 5.2 Route — in the `api/links` when-block, delegating to the pure parser

**Why not just loop `LinkStore.add`:** `add` runs `persist()` (full-file rewrite) + `bump()` + `notifyAll()` *per call* (LinkStore.kt:45-47,71-75). Looping it over N entries = N full-file rewrites and N long-poll wake-ups, with `version` jumping by N — O(n²) disk I/O and a notify storm. Import uses a single `LinkStore.addBatch` instead (§8.2): one lock, one evict, one persist, one bump, one wake-up; the TV sees every new channel in one update.

The handler sits in the existing `api/links` when-block (alongside `add`/`remove`/`clear`/`poll`), reusing that block's `cookie()`/auth context — not in `PlaylistRoutes.kt` (which handles the orthogonal `?m3u=`/`?vtt=` file-serving concern). `PlaylistImport` is the pure parser; `LinkPolicy`/`readCapped` the shared utils from §5.0.

```kotlin
"api/links/import" -> {                                  // POST, body = .m3u/.m3u8 text
    ServerStateHolder.incrementRequests()
    val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
    if (declared > options.importMaxBytes)
        return errJson(Response.Status.PAYLOAD_TOO_LARGE, "Playlist too large")   // 413 — the one hard error
    val body = readCapped(session.inputStream, options.importMaxBytes)
    val parsed  = PlaylistImport.parse(body)                      // group captured, dropped here
    val allowed = parsed.filter { LinkPolicy.isAllowed(it.url) }
    val kept    = allowed.take(options.importMaxEntries)
    val skipped = parsed.size - kept.size                         // bad-scheme + over-cap, counted not itemized
    val (v, list) = LinkStore.addBatch(kept.map { it.url to it.title })
    return cookie(jsonResponse(ApiJson.importResult(v, list, kept.size, skipped)))
}
```

- New `ServerOptions` fields (beside `allowZip`/`allowDownload`, HttpServer.kt:88-95): `importMaxBytes: Int = 2 * 1024 * 1024`, `importMaxEntries: Int = 500` (≤ `LinkStore.CAP`).
- New `ApiJson.importResult(version, links, added, skipped)` → `{"version":…,"links":[…],"added":…,"skipped":…}`. Its own shape, so §3.4's frozen `api/links` contract is untouched — this is a brand-new route (§8.3).
- `413` (`Response.Status.PAYLOAD_TOO_LARGE` — confirm the constant against the bundled NanoHTTPD) is the only hard error: a whole-request failure the user must see, never a silent truncation. Per-entry drops are silent and surface only as the `skipped` count.
- **Web entry point (in scope):** a minimal control in the existing Links tab — a file picker (or paste box) that POSTs the file to `api/links/import` and shows `added · skipped`. That is the feature's entry point, distinct from the out-of-scope channel-guide UI (§2). The TV side is unchanged — it just reads the one links list it already reads.

### 5.3 Decisions (resolved 2026-10-25)

- **Bounded read — resolved.** `readCapped` promoted to a shared util (§5.0); the route rejects an over-declared `Content-Length` with 413 and caps the actual read at `importMaxBytes` regardless. No unbounded `.readBytes()`.
- **No resolve on import — resolved (keep plan).** `StreamResolver.resolve` is a blocking fetch — 6s connect / 8s read per URL (StreamResolver.kt:88-89); 300 on one request thread is untenable. Import trusts entries are already direct streams; resolve lazily if one fails to play later. If that assumption is wrong for some provider, that's a follow-up, not a Phase-2 blocker.
- **Cap — resolved.** One shared store; `CAP` raised 200→500; `LinkStore.addBatch` evicts once; `importMaxEntries`/`importMaxBytes` in `ServerOptions`. A >CAP import evicts oldest (newest-wins, same policy everywhere) — documented, not fixed; pinning casual links is a future, scoped feature. Full rationale §8.2.
- **One validator — resolved.** `LinkPolicy.isAllowed` (§5.0) is the single allow/deny policy for both single-add and batch import.

### 5.4 Tests
`PlaylistImportTest.kt` — pure, JVM, no Android, mirrors `StreamResolverTest.kt`: well-formed multi-entry file; entries with and without `group-title`; blank lines; comment-only lines; CRLF vs. LF; a title containing a comma; an oversized file (assert the cap kicks in, not a crash); empty or garbage input (assert an empty list, not an exception). Assert `group` is *parsed* into `Entry` but (via the §5.2 route) never reaches the stored `Link`.

`LinkStoreTest.kt` — add `addBatch`: a batch larger than `CAP` evicts to exactly `CAP` with a single version bump; de-dup by URL still holds across the batch; an empty/blank-only batch is a no-op.

`HttpServerRouteTest.kt` — add `api/links/import`: a multi-entry POST → 200, body carries `added`/`skipped` and the links list grew; an over-`importMaxBytes` body (or over-declared `Content-Length`) → 413; a body mixing valid and bad-scheme lines → `skipped` counts the bad ones.

**Exit:** `PlaylistImportTest` + `LinkStoreTest` (addBatch) green; `HttpServerRouteTest` extended with the `api/links/import` cases; a manual pass importing a real multi-entry `.m3u` shows every channel on the TV's existing Links screen with no changes needed on the TV side, and the web UI shows the `added · skipped` summary.

## 6. Minor suggestions and advancements

Small, each justified against a principle already in the roadmap doc — none of this is new scope.

| Suggestion | Principle it serves | Why |
|---|---|---|
| Run unit tests in CI | Unit-testable core | Currently decorative — 8 tests exist, 0 run in CI. Highest-leverage single-line change in this whole plan. |
| Cap import body size + entry count *before* parsing | Performance-first, graceful degradation | Stops one bad paste from tying up a request thread or growing the heap; reject (413) on declared size before parse, not after. |
| `LinkStore.addBatch` (one lock/evict/persist/bump) | Performance-first | Avoids the O(n²) rewrite + N-deep notify storm a per-entry `add` loop would cause on import (§8.2). |
| `importMaxBytes`/`importMaxEntries` as `ServerOptions` fields | Consistency with existing `allowZip`/`allowDownload` toggles | Same feature-gating pattern already in use — no reason to invent a second convention. |
| `LinkPolicy.isAllowed` reused for every imported URL | No new attack surface | One allow/deny policy instead of two that can drift apart. |
| No outbound calls on import itself | No telemetry, LAN-first | Import is parse-and-store; nothing is contacted until the user tries to play something, same as today. |
| Document `api/links/import` in the README's route table | Matches existing documentation habit | The README already tables every route; this is the one new one to add. |
| Treat Phase 1 as droppable if it stalls | Precedent already set: `d2a0d39 Drop ADB-toolkit spec` | You've already cut a direction that wasn't paying off once — same standard applies here. |
| `group-title` parsed but **not stored** | Non-goals (§2) | Keeps the parser complete/testable without churning `LinkStore`'s TSV format for data no screen reads; add a `Link.group` field only when a group UI is scoped. |

## 7. Sequencing summary

| Phase | Deliverable | Gate to move on |
|---|---|---|
| 0 | CI runs unit tests; `HttpServerRouteTest` written against current code | Both green |
| 1 | `HttpServer.kt` split into focused units, one commit per unit | Wire-compatibility checklist + full suite pass; soak a few days |
| 2 | `LinkPolicy` + `readCapped` prep; `PlaylistImport.kt`; `LinkStore.addBatch`; `api/links/import` + web entry point + tests | `PlaylistImportTest` + `LinkStoreTest`(addBatch) + extended `HttpServerRouteTest` green; manual TV + web check |

## 8. Resolved decisions (were open questions)

Resolved 2026-10-25 against the code at HEAD; the resolutions are folded into §4–§6 above.

### 8.1 Package layout → flat (decided)
The entire app is one flat package (`com.deekshith.droidserve`, 17 files, zero subpackages). The extracted units are collaborators `HttpServer` constructs and reads private state through; a subpackage buys no encapsulation (Kotlin's module-default visibility is already public-within-module) and would force either `import` churn or widening visibility — both cut against Phase 1's no-contract-change goal. The `*Routes.kt` / `AuthGate.kt` / `HttpHelpers.kt` names *are* the namespace. Revisit only as cosmetics if the flat list ever gets genuinely unwieldy — never as a blocker.

### 8.2 LinkStore cap → one shared store, CAP 200→500, add `addBatch` (decided)
Rejected a separate import namespace: it doubles persistence + the version/long-poll machinery + `ApiJson.links` + TV-side consumption, and directly breaks this plan's own exit criterion (every channel on the TV's existing Links screen, no TV change). One store is the contract the TV already speaks.

`add` persists/notifies/bumps the version *per call* (LinkStore.kt:45-47,71-75), so the naïve per-entry import loop is O(n²) full-file writes plus an N-deep notify storm with `version` jumping by N. `addBatch` (one lock / one `removeAll`-dedup per entry / one evict / one `persist` / one `bump` / one `notifyAll`) is the real fix — this is why the answer isn't merely "raise the cap":

```kotlin
fun addBatch(items: List<Pair<String, String?>>): Pair<Int, List<Link>> = synchronized(lock) {
    for ((url, label) in items) {
        val clean = sanitize(url); if (clean.isEmpty()) continue
        links.removeAll { it.url == clean }
        val lbl = sanitize(label.orEmpty()).ifEmpty { sanitize(labelFor(clean)) }.ifEmpty { clean.take(60) }
        links.add(0, Link(nextId(), clean, lbl, System.currentTimeMillis(), true))
    }
    while (links.size > CAP) links.removeAt(links.size - 1)
    bump()                                  // ONE persist + ONE notifyAll + version+1
    version to ArrayList(links)
}
```

`CAP` stays a compile-time const (bumped 200→500) rather than config-threaded — keeps `LinkStore` framework-free and its unit tests deterministic. `importMaxEntries` (≤ CAP) / `importMaxBytes` live in `ServerOptions` beside `allowZip`/`allowDownload`. Residual, documented not fixed: a >500 import still evicts oldest casual links (newest-wins, the same policy everywhere); protecting them is a future pin/favorite feature, explicitly out of scope.

### 8.3 Report rejections → aggregate count, new response shape, 413 for over-size (decided)
Found in the code: the web UI already swallows the single-add 400 silently. `api()` (FileUtils.kt:1031) is `r.ok ? r.json() : null` → `if (d) render(d)`, and the input is cleared *before* the call (FileUtils.kt:1033). So the server returns a 400 on a rejected URL, but the user sees nothing — "fail silently as the single-add path does today" is true only because the client drops the server's error, not because the server is silent.

Import is the one place silent dropping genuinely confuses (paste 300, land 212 — "did it break?"). Decision: import returns its own shape `{version, links, added, skipped}` — a brand-new route, so §3.4's frozen `api/links` contract is untouched. The Links tab shows "added A · skipped S"; `skipped` aggregates bad-scheme + over-cap drops and is **not** itemized (per-URL reasons are noise, need UI the app lacks, and the single-add path doesn't do it either). An over-`importMaxBytes` body is the single hard error → **413**, never a silent truncation that would drop the tail of the user's playlist with no signal.

## 9. Follow-on scope (not in this plan, recorded here so they don't get lost)

### 9.1 M3U URL auto-detect in `api/links/add`
When a user pastes a URL like `https://iptv-org.github.io/iptv/index.m3u` into the single-add field, the natural expectation is that the individual channels appear in the Links list — not that one link to the M3U file itself is stored (which then opens as a playlist in VLC/MX, bypassing the per-channel UI entirely).

The detection logic is a trivial Phase 2 add-on — it reuses everything already built:
```
api/links/add?url=<m3u-url>
→ if HEAD response Content-Type contains "mpegurl"  OR  url ends with .m3u/.m3u8
→ fetch body (bounded by importMaxBytes), pass to PlaylistImport.parse(), addBatch, return importResult shape
→ else: current single-link path unchanged (StreamResolver.resolve → LinkStore.add)
```
No new routes, no TV changes. Lands in Phase 2 alongside `api/links/import`, after `PlaylistImport` and `addBatch` exist. The two paths (file upload via POST body, URL-based fetch) share the parse+store logic; only the fetch is new.

### 9.2 StreamResolver — WebView-based extraction (smarter than static regexes)
The current `extractMediaUrl()` misses JavaScript-rendered embed players (Dailymotion, Streamtape, etc.) because it does static HTML analysis. The static regexes are best-effort and miss the majority of real-world watch pages.

**The right answer on Android is WebView + network request interception:**
- Load the watch page URL in a WebView
- Attach a `WebViewClient.shouldInterceptRequest()` override
- Capture the first request that has a video/audio Content-Type (or a `Range:` header, which media players emit immediately)
- Return that URL; close the WebView

This runs the real JavaScript exactly as the browser would, and is entirely on-device (no outbound LLM API call). The constraint: WebView needs the UI thread. The implementation is a `StreamResolverActivity` with no visible UI (the existing `serve()` coroutine dispatches to the main thread, waits for a `CompletableDeferred<String>` that the WebView callback completes, then resumes). Falls back to the current static-regex path if WebView yields nothing within a timeout.

**Better UX in the short term (Phase 2, cheap):** add `"resolved":false` to `ApiJson.Link` when `StreamResolver.resolve()` returns `playable=false`. The Links tab shows a warning badge on those entries ("couldn't find direct stream — opens in browser"). Small change, makes the current silent fallback legible to the user without any WebView work. WebView extraction is a follow-on, separate scope.
