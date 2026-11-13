# AI Camera Android app (v1.6)

Native Android client built with Kotlin, Jetpack Compose and CameraX.

## Build

Requirements: JDK 17, Android SDK (platform 34), and Gradle 8.7.

```bash
gradle --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Every push to `main` builds an Actions artifact named `AI-Camera-v1.6-APK`. Pushing a `v*` tag also attaches the APK to a GitHub Release.

This app is the client for the companion AI Camera PC/server. Start that server and ensure its pairing code or public URL is reachable from the phone.
