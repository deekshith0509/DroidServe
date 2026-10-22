# DroidServe TV — ADB-Powered Device Toolkit (Design)

**Date:** 2026-10-22
**Status:** Draft for review
**Scope decision (from brainstorming):** full power toolkit, implemented in tiers; ADB core via **dadb**, TV-local (`127.0.0.1:5555`, Shizuku/LADB pattern); phone-as-remote half specced as a deferred roadmap tier. Land on `main`.
**Target device:** Xiaomi Fire TV FX, 55" — Fire OS ≈ Android 9 (API 28). ADB-over-network (`adb tcpip 5555`) kept enabled by the user.

---

## 1. Goal

Turn the DroidServe **TV** app from a read-only file browser/player into a self-contained Android-TV **power tool** that can, using the files served by the phone and the TV's own ADB daemon:

1. **Install custom APKs** served by the phone — silently via ADB, with a graceful on-TV system-installer fallback. *(headline ask)*
2. **Manage apps** — list installed packages, launch, uninstall.
3. **Capture the screen** — screenshot the TV, view it in-app.
4. **Run a shell** — arbitrary `adb shell` commands from a 10-foot console (power-user escape hatch).

All operations run **on the TV, against the TV itself** (`localhost:5555`), because the TV is the device the user operates and the device the ADB daemon exposes. The APK *source* is the phone's existing HTTP file server — satisfying "install custom apps from my phone, directly."

### Non-goals (this landing)

- **Phone-as-remote** (phone drives the TV over LAN ADB: remote D-pad/keyboard/power/launch/push). Specced in §11 as the next tier; it introduces reverse discovery + a LAN security/pairing surface and gets its own spec.
- Rooting, Magisk, custom recovery, OTA flashing.
- Changing the phone (`app/`) module. The phone already serves `.apk` with the correct MIME (`FileUtils.kt:52`) and force-download (`?dl=1`); **no phone-side change is required** for this landing.

---

## 2. Prerequisites & assumptions (documented in-app)

- The TV has **Developer Options → ADB debugging** on, and **network ADB** enabled (`adb tcpip 5555`), persisting across the session. The app **does not** and **cannot** enable this itself; it detects absence and shows setup guidance.
- First ADB self-connection triggers the system **"Allow debugging?"** dialog showing our key fingerprint; the user picks **"Always allow."** After that it is silent until keys are cleared.
- For the **fallback** installer: the user allows DroidServe TV to "install unknown apps" once (standard Android/Fire OS per-source grant).
- API 28 target device → no Android-11 wireless-pairing PIN dance; classic TCP ADB auth applies.

---

## 3. Architecture overview

```
            PHONE (app/, unchanged)                    TV (tv/, all new work here)
   ┌─────────────────────────────┐            ┌──────────────────────────────────────────┐
   │ NanoHTTPD file server        │            │ BrowseViewModel (screen state machine)     │
   │  /api/list  /api/info        │  HTTP      │   ├─ DroidServeClient  (+ downloadTo)       │
   │  file bytes (?tok=, ?dl=1)   │◀──────────▶│   ├─ AdbChannel  ── interface (mockable)    │
   │  .apk ⇒ vnd.android.package  │  (browse,  │   │     └─ DadbChannel ⇒ dadb ⇒ 127.0.0.1:5555
   └─────────────────────────────┘   download)│   │                          (TV's own adbd) │
                                               │   └─ PlaybackStore / CredentialStore (exist) │
                                               │ MainActivity (Compose screen switch + intents)│
                                               └──────────────────────────────────────────┘
                                                        AdbChannel powers: install · uninstall ·
                                                        launch · pm list · screencap · shell
```

**Key seam:** `AdbChannel` is an interface. `DadbChannel` is the only production implementation (wraps dadb). The ViewModel depends on the interface, so all routing/outcome logic is unit-testable on the JVM with a `FakeAdbChannel` — no device, no dadb, matching the repo's existing pure-JVM test style (`ClientIntegrationTest`, `RemoteEntryTest`, `LogicTest`).

---

## 4. Components

### 4.1 Dependency: dadb
- Add to `tv/build.gradle.kts`: `implementation("dev.mobile:dadb:<latest 1.2.x on Maven Central>")` (Apache-2.0).
- Pulls okio + a bundled crypto provider for RSA ADB auth. Verify the merged APK builds on `minSdk 21` and run a smoke build before wiring UI. Keep `isMinifyEnabled=false` (current) so no ProGuard rules needed initially; if we later minify, add dadb/okio keep rules.
- **Risk gate:** if dadb fails to run on the device (minSdk/transitive issue), the architecture is unchanged — swap `DadbChannel` for a hand-rolled impl behind the same `AdbChannel` interface (the Approach-B fallback). Everything above the seam is untouched.

### 4.2 `AdbChannel` (new interface) + `AdbStatus`
```
enum AdbStatus { Disconnected, Connecting, NeedsAuth, Connected, Unreachable, Error }

interface AdbChannel {
    val status: StateFlow<AdbStatus>
    suspend fun ensureConnected(): Boolean          // idempotent connect + auth
    suspend fun install(apk: File): InstallOutcome   // push + pm install
    suspend fun uninstall(pkg: String): Boolean
    suspend fun shell(cmd: String): ShellOut         // stdout+stderr+exitCode
    suspend fun listThirdPartyPackages(): List<AppInfo>
    suspend fun launch(pkg: String): Boolean
    suspend fun screenshot(dest: File): Boolean      // screencap -p + pull
    fun close()
}
sealed InstallOutcome { Success(pkg: String?) ; Failure(reason: String) ; AdbUnavailable }
data class ShellOut(val out: String, val code: Int)
data class AppInfo(val pkg: String, val label: String? = null, val system: Boolean = false)
```
- All methods `suspend` and internally `withContext(Dispatchers.IO)`; dadb is blocking.
- A single shared `Dadb` connection guarded by a `Mutex` (serialize operations; dadb connections are not concurrency-safe). Transport errors (`AdbException`) → set status, drop the connection, allow retry.

### 4.3 `DadbChannel` (production impl)
- `Dadb.create("127.0.0.1", 5555, keyPair)` lazily on `ensureConnected()`.
- **Keypair:** generate an RSA-2048 adbkey **once** and persist under `context.filesDir/adb/` (private, app-sandboxed); reuse on every launch so the "Always allow" grant sticks. (Use dadb's `AdbKeyPair` generation API; if absent, generate via `KeyPairGenerator("RSA")` in dadb's expected encoding.)
- `install(apk)` → `dadb.install(apk)` → map `InstallResult` → `InstallOutcome`. `pm install` doesn't return the package name, so parse it from the APK via `packageManager.getPackageArchiveInfo(path, 0).packageName` to enable a post-install **Open**.
- `launch(pkg)` → `shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")` (runs as shell UID; sidesteps our own targetSdk-35 package-visibility sandbox, so **no `QUERY_ALL_PACKAGES` permission needed**).
- `listThirdPartyPackages()` → parse `shell("pm list packages -3")` (`package:<name>` lines). Labels deferred (package names are enough for v1; label lookup over ADB is costly).
- `screenshot(dest)` → `shell("screencap -p /sdcard/.ds_shot.png")` then `dadb.pull(dest, "/sdcard/.ds_shot.png")` (avoids binary-over-shell newline corruption), then `shell("rm /sdcard/.ds_shot.png")`.

### 4.4 `DroidServeClient.downloadTo` (new, in existing client)
```
fun downloadTo(absoluteUrl: String, dest: File, onProgress: (done: Long, total: Long) -> Unit = {_,_->})
```
- Streams bytes to `dest` using the existing `open()` helper (carries Basic auth; media URLs already carry `?tok=`). Honors `Content-Length` for progress. Buffered (e.g. 64 KB). No in-memory cap (APKs are large) — write straight to `cacheDir`. Throws `AuthException` on 401 like the other methods.

### 4.5 Models (`Models.kt`)
- `RemoteEntry.isApk: Boolean get() = mime == "application/vnd.android.package-archive" || name.endsWith(".apk", true)`.

### 4.6 New UI screens (`BrowseViewModel.UiScreen` + `MainActivity` composables)
Reuse the existing idiom: sealed `UiScreen` variant + a composable in `MainActivity`'s `when(screen)`, D-pad via `FocusableRow`/`SortChip`/`FocusableChip`/`InputField`, palette constants, `onBack()` handling.

- **`UiScreen.ApkInstall`** — name, sizeBytes, phase (`Downloading(progress)`, `Installing`, `Done(pkg?)`, `Failed(reason, canFallback)`), + actions: cancel, open-after-install, use-fallback-installer.
- **`UiScreen.PowerMenu`** — entry hub: ADB status chip + "Connect/Authorize", and rows → Apps, Screenshot, Shell. Reached from a new **"⚡ Power"** `SortChip` in `BrowseScreen`'s toolbar row.
- **`UiScreen.Apps`** — list of `AppInfo` (D-pad rows); per row Launch / Uninstall; loading/error.
- **`UiScreen.Shell`** — scrollback + opt-in `InputField` (like Search) → run → append `ShellOut`. Behind Power menu (footgun kept off the main path).
- Screenshot reuses a local-file variant of `RemoteImage` (decode `File` → `Bitmap` off-main).

### 4.7 Manifest + resources (`tv/`)
- `tv/src/main/AndroidManifest.xml`:
  - `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` (fallback installer).
  - `<provider androidx.core.content.FileProvider authorities="${applicationId}.fileprovider" exported=false grantUriPermissions=true>` + `@xml/file_paths`.
- `tv/src/main/res/xml/file_paths.xml`: `<cache-path name="cache" path="." />` (mirror the phone's).
- No new INTERNET/network perms (localhost socket covered by existing `INTERNET`; cleartext already allowed).

---

## 5. Data flows

### 5.1 Install an APK (headline)
```
tap .apk in Browse
  → VM.onEntryClick: entry.isApk ⇒ UiScreen.ApkInstall(Downloading)
  → DroidServeClient.downloadTo(entry.url, cacheDir/apk/<name>, onProgress)        [phase: Downloading]
  → adb.ensureConnected()
       Connected    ⇒ adb.install(file)                                            [phase: Installing]
                        Success(pkg) ⇒ Done(pkg)  [offer Open ⇒ adb.launch(pkg)]
                        Failure(r)   ⇒ Failed(r, canFallback=true)
       !Connected   ⇒ Failed("ADB unavailable", canFallback=true)
  → fallback (user taps, or auto when AdbUnavailable):
       FileProvider.getUriForFile(cacheFile) → ACTION_VIEW type=vnd.android.package-archive
       + FLAG_GRANT_READ_URI_PERMISSION; if !canRequestPackageInstalls()
         → ACTION_MANAGE_UNKNOWN_APP_SOURCES first.
  → cleanup: delete cached APK on success; on fallback keep until the intent is consumed,
    prune cacheDir/apk on next app start.
```

### 5.2 Apps manager
`PowerMenu → Apps`: `adb.listThirdPartyPackages()` → list. Launch → `adb.launch(pkg)`. Uninstall → confirm → `adb.uninstall(pkg)` → refresh.

### 5.3 Screenshot
`PowerMenu → Screenshot`: `adb.screenshot(cacheDir/shot.png)` → decode locally → show (Fit) → Back.

### 5.4 Shell
`PowerMenu → Shell`: type command → `adb.shell(cmd)` → append `out`/exit code to scrollback.

---

## 6. ADB status & error handling

- `AdbChannel.status` is the single source of truth; `PowerMenu` renders it and gates actions.
- **Connection refused / timeout** ⇒ `Unreachable` ⇒ UI: "Can't reach ADB on this TV. Enable Developer Options → ADB debugging, then `adb tcpip 5555`." + Retry. (This is the expected state when the user hasn't set ADB up.)
- **Auth pending** ⇒ `NeedsAuth` ⇒ "Approve the debugging prompt on your TV (Always allow)." Retry after approval.
- **`AdbException` mid-op** ⇒ drop connection, status `Error(msg)`, operation returns a failure value (never throws to UI). Next action reconnects.
- **`InstallOutcome.Failure(reason)`** ⇒ show reason, offer fallback. Common reasons surfaced verbatim (e.g. `INSTALL_FAILED_OLDER_SDK`, signature mismatch).
- Preserve the app's existing safety nets: everything off-main-thread; the global crash-to-file handler (`App.kt`) stays the backstop.

---

## 7. Security

- ADB client connects **only to `127.0.0.1`** — nothing is exposed on the LAN by this landing. The adbkey lives in app-private storage.
- The shell console is powerful but local to whoever is physically operating the TV (same trust level as a USB-connected laptop running `adb shell`).
- Enabling ADB tcpip is the user's explicit choice; the app never enables it.
- The deferred phone-as-remote tier (§11) is where LAN exposure appears — it will require an explicit pairing/allow handshake and is intentionally **not** in this landing.

---

## 8. Testing strategy (TDD)

Write the pure-logic tests first (JVM, no device, no dadb), using a `FakeAdbChannel`:

- **`RemoteEntry.isApk`** across mimes/filenames (incl. `.APK`, octet-stream+`.apk`).
- **`pm list packages -3` parser** → `List<AppInfo>` (blank lines, `package:` prefix, CRLF).
- **ViewModel routing:** tapping an `.apk` entry ⇒ `ApkInstall` state; download-progress → phase transitions; `Success` ⇒ `Done`; `AdbUnavailable` ⇒ `Failed(canFallback=true)`.
- **Fallback decision** mapping (`AdbStatus`/`InstallOutcome` → ADB path vs intent path).
- **`downloadTo`** against a loopback stub server (follow whatever the existing `ClientIntegrationTest` already uses for the client's HTTP tests).

**On-device checklist (real Fire TV FX, manual — TVs can't always be `adb`-attached while under test):**
first-connect auth dialog → silent install → launch → uninstall → screenshot → shell → fallback installer with ADB off → ADB-off messaging.

No test asserts against live dadb; the interface seam keeps CI pure-JVM.

---

## 9. Build order (tiers) — each a focused commit, TDD per tier

| Tier | Deliverable | Files |
|---|---|---|
| **T0 Core** | dadb dep + smoke build; `AdbChannel`/`AdbStatus`/`DadbChannel` + keypair; `DroidServeClient.downloadTo`; `RemoteEntry.isApk`; TV `FileProvider` + `REQUEST_INSTALL_PACKAGES` + `file_paths.xml` | `tv/build.gradle.kts`, new `AdbChannel.kt`/`DadbChannel.kt`, `DroidServeClient.kt`, `Models.kt`, manifest, `res/xml/` |
| **T1 Install** *(headline)* | `.apk` tap → `ApkInstall` screen → ADB install + intent fallback + post-install Open | `BrowseViewModel.kt`, `MainActivity.kt` |
| **T2 Apps** | `PowerMenu` entry + ADB status UI; `Apps` screen (list/launch/uninstall) | `BrowseViewModel.kt`, `MainActivity.kt` |
| **T3 Power** | Screenshot viewer + Shell console | `BrowseViewModel.kt`, `MainActivity.kt` |
| **Finalize** | Bump TV `versionCode`/`versionName`; README "TV power toolkit" section | `tv/build.gradle.kts`, `README.md` |

Ordering rule: T0 must smoke-build with dadb on the real TV before T1+ UI work (de-risks the one external dependency early).

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| dadb incompatible with minSdk 21 / transitive dep bloat | T0 smoke-build gate; interface seam lets us swap to hand-rolled ADB (Approach B) without touching UI |
| Fire OS suppresses/relocates the ADB authorize dialog | Fallback system-installer path needs no ADB; document the `adb connect`/authorize-from-laptop one-time alternative |
| ADB tcpip resets on reboot | Detect `Unreachable`, show re-enable steps; user already keeps it on |
| Large APK fills TV cache | Stream to `cacheDir`, delete post-install, prune `cacheDir/apk` on startup (pattern mirrors `PlaybackStore.pruneIfLarge`) |
| targetSdk 35 package visibility blocks launch/Open | Launch via ADB `monkey` (shell UID) — avoids `QUERY_ALL_PACKAGES` entirely |
| Shell console footgun | Kept off the main browse path, behind Power menu; local-trust only |

---

## 11. Roadmap — phone-as-remote (deferred, next spec)

Same `AdbChannel` interface, but the **phone** (`app/`) hosts a `DadbChannel` targeting `<tv-ip>:5555`, turning the phone into a full TV remote: D-pad/text/keyboard input (`input keyevent`/`input text`), power, launch apps, push files to the TV. Requires: reverse discovery (phone finds TV — today discovery is TV→phone only), a phone-side control UI, and an explicit **pairing/allow** step (LAN ADB is a real security surface). Scoped separately because its threat model and discovery work are independent of this TV-local landing.

---

## 12. Summary of changes (this landing)

- **New files (tv):** `AdbChannel.kt`, `DadbChannel.kt`, `res/xml/file_paths.xml`.
- **Edited (tv):** `build.gradle.kts` (dadb dep, version bump), `AndroidManifest.xml` (perm + FileProvider), `Models.kt` (`isApk`), `DroidServeClient.kt` (`downloadTo`), `BrowseViewModel.kt` (routing + new `UiScreen`s + actions), `MainActivity.kt` (new composables + fallback install intent).
- **Edited (root):** `README.md` (toolkit section).
- **Phone (`app/`):** none.
