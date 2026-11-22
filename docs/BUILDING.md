# Building

## Prerequisites

- JDK 17
- Android SDK (platform 35, build-tools)
- Gradle (wrapper included)

## Build

```
./gradlew assembleDebug          # debug APKs (phone + TV)
./gradlew assembleRelease        # minified release APKs
./gradlew testDebugUnitTest      # unit tests
```

## Release

Push a version tag to trigger the release workflow:

```
git tag v1.2.3 && git push origin v1.2.3
```

Release APKs are signed with the shared debug keystore committed under `keystore/` so updates
install without uninstalling first.
EOF