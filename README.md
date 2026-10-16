# Cast — Android app for the office panel

A single full-screen WebView that opens <https://nekruz.xyz/cast/screen.html?app=1>.
All casting logic lives on the website, so site updates never require reinstalling the APK.

**Latest APK:** <https://github.com/nek-yoqubov/cast-android/releases/latest/download/cast.apk>

## What the app does
- Full-screen immersive mode, keeps the screen on, follows the panel's orientation.
- Sound plays without a tap (`mediaPlaybackRequiresUserGesture = false`).
- No network / load error → "Нет подключения, повтор…" and a retry every 5 seconds.
- Back exits only on a double press.
- Opens itself after the panel boots (`BOOT_COMPLETED`). On Android 10+ this needs
  "Display over other apps"; the app asks once on first launch.
- Shows a hint to update Android System WebView if the WebView has no WebRTC.

## Build
GitHub Actions (`.github/workflows/build.yml`) builds a signed release APK on every push to `main`
and publishes it as a GitHub release. `versionCode` = workflow run number, so every build installs
over the previous one.

Repository secrets used for signing (nothing secret is committed):
`CAST_KEYSTORE_BASE64`, `CAST_KEYSTORE_PASSWORD`, `CAST_KEY_ALIAS`, `CAST_KEY_PASSWORD`.
Keep a backup of the keystore: without it, future builds can't update installed copies.
