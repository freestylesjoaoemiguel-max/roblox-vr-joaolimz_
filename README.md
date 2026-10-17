# Roblox VR Patcher

A Mac app (SwiftUI) and a patcher that turn a stock **Roblox for Android** package into an installable
**Roblox VR** APK: Cardboard stereo view, Joy-Con / Xbox / PlayStation controllers, optional camera hand tracking.
**Alpha 26.3.0** — there are bugs.

> Unofficial project, not affiliated with Roblox Corporation. This repository contains **no Roblox code and no Roblox APK**.
> You supply your own Roblox package. Use at your own risk (modified clients may violate Roblox's terms).

Supported Roblox build: **2.741 / versionCode 3218**, arm64 only (64-bit phones). Other builds need a new profile (`vrkit/profiles/`).

## How it works
1. `vrkit/vrpatch.py` merges a split bundle (`.apk/.apkm/.xapk/.apks/.zip` or a folder) into one APK with APKEditor and decodes it with apktool.
2. Applies the Java hooks (`profiles/3218/smali.patch` + method stubs), renames the package to `com.samrat.roblox.vr`, forces landscape.
3. Patches `libroblox.so` (VR mode switch, frame hook, GLES) using the byte patches in `profiles/3218/profile.json`.
4. Adds the bridge: `phonexr-bridge/src` (Java: head tracking, controllers, settings, loading screen) and `phonexr-bridge/native`
   (`libphonexrvr.so`: Cardboard compositor, per-profile offsets in `native/profiles/`).
5. Rebuilds, zip-aligns and signs the APK.

## Build
Requirements (macOS): Xcode / Swift 5.9+, Python 3, `brew install apktool openjdk`, Android SDK build-tools (zipalign, apksigner),
Android NDK 27 and platform android-35.

```bash
scripts/fetch_deps.sh        # MediaPipe / ARCore / libs, hand model, APKEditor (downloaded, not in git)
vrkit/build_payload.sh       # builds the bridge dex + native libraries into vrkit/payload
vrkit/package_app.sh         # builds dist/RobloxVRPatcher.app and .dmg and installs the app into /Applications
```

Or use the CLI directly:

```bash
python3 vrkit/vrpatch.py Roblox.apks RobloxVR.apk
adb install -r -d RobloxVR.apk
```

`phonexr-bridge/find_offsets.py` helps to port the native patches to another Roblox build.

## Русский
Приложение для Mac и патчер, превращающие обычный Roblox для Android в **Roblox VR** (Cardboard, Joy-Con / Xbox / PlayStation).
Альфа-версия, есть баги. В репозитории нет кода и APK Roblox — файл Roblox нужен свой. Поддерживается сборка 3218, только arm64.

## Português (BR)
App para Mac e patcher que transformam o Roblox para Android em **Roblox VR** (Cardboard, Joy-Con / Xbox / PlayStation).
Versão alfa, com bugs. O repositório não contém código nem APK da Roblox — use o seu próprio arquivo. Suporta o build 3218, apenas arm64.
