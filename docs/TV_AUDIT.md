# TV App Audit

## Build/Release
- CI `Build APK` workflow builds TV debug APK successfully.
- Version bumped to 1.13.6 (versionCode 32).
- Install via `adb install tv-debug.apk` or Android TV sideload.

## Potential gaps found
- `VlcLauncher` only targets VLC/MX package names; devices with other players fall back to chooser.
- `openCurrentExternally()` fixed to use OS chooser instead of duplicating VLC.
- Empty catch blocks in several paths may hide failures; consider logging for diagnostics.
- `leanback` feature is not required, so app can install on non-TV devices (intended for testing).
- Release signing depends on keystore.properties/env; absent → debug-signed release (personal use only).

## Recommendations
- Add in-app diagnostics/log screen for connection/playback failures.
- Add automated UI tests for remote navigation.
- Verify FileProvider paths for APK install on Android 14+.
