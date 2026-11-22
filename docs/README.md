# DroidServe Documentation

DroidServe turns an Android device into a local HTTP file server and ships a native Android TV
companion app. This folder collects the project's reference documentation.

## Contents

- [Architecture](ARCHITECTURE.md) — module layout, data flow, and key components.
- [HTTP API](API.md) — REST endpoints served by the phone.
- [Building](BUILDING.md) — build, test, and release instructions.

## Quick start

1. Clone the repository.
2. Open in Android Studio or build from the command line:
   ```
   ./gradlew assembleDebug
   ```
3. Install `app/build/outputs/apk/debug/*.apk` on a phone and `tv/build/outputs/apk/debug/*.apk` on a TV.
4. Start the server on the phone, then launch the TV app to discover it on the LAN.

## License

MIT. See [LICENSE.txt](../LICENSE.txt).
EOF