# Roblox VR Patcher — Alpha 26.3.1

A Mac app (SwiftUI) and a patcher that turn a stock **Roblox for Android** package into an installable
**Roblox VR** APK: Cardboard stereo view, Joy-Con / Xbox / PlayStation controllers, optional camera hand tracking, an experimental no-controller mode (pinch = click, fist = walk) and optional ARCore 6DoF.
**Alpha 26.3.1** — there are bugs.

> Unofficial project, not affiliated with Roblox Corporation. This repository contains **no Roblox code and no Roblox APK**.
> You supply your own Roblox package. Use at your own risk (modified clients may violate Roblox's terms).

Supported Roblox builds: **2.741 (3218)** and **2.742 (3222)**, arm64 only (64-bit phones). Other builds need a new native profile (`vrkit/profiles/<versionCode>/`, `phonexr-bridge/native/profiles/`); the Java hooks (`vrkit/hooks.py`) are found by method names and code shape, so they usually carry over.

## How it works
1. `vrkit/vrpatch.py` merges a split bundle (`.apk/.apkm/.xapk/.apks/.zip` or a folder) into one APK with APKEditor and decodes it with apktool.
2. Applies the Java hooks (`vrkit/hooks.py` + method stubs from the profile), renames the package to `com.samrat.roblox.vr`, forces landscape, adds the ARCore markers.
3. Patches `libroblox.so` (VR mode switch, frame hook, GLES) using the byte patches in `profiles/<versionCode>/profile.json` (regenerate with `vrkit/gen_profile_*.py`).
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
Альфа-версия, есть баги. В репозитории нет кода и APK Roblox — файл Roblox нужен свой. Поддерживаются сборки 3218 и 3222, только arm64.

## Português (BR)
App para Mac e patcher que transformam o Roblox para Android em **Roblox VR** (Cardboard, Joy-Con / Xbox / PlayStation).
Versão alfa, com bugs. O repositório não contém código nem APK da Roblox — use o seu próprio arquivo. Suporta os builds 3218 e 3222, apenas arm64.
