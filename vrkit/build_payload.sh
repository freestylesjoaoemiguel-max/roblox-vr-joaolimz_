#!/bin/sh
# Builds the version-independent bridge payload into vrkit/payload (dex, libs, assets)
# and one libphonexrvr per supported Roblox profile.
set -eu
KIT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PX="$KIT/../phonexr-bridge"
P="$KIT/payload"; B="$PX/build"
SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
BT=$(ls -d "$SDK"/build-tools/35.0.0 "$SDK"/build-tools/3* 2>/dev/null | head -1)
AJAR="$SDK/platforms/android-35/android.jar"
NDK="$SDK/ndk/27.0.12077973"
CLANG="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang"
MP="$B/mediapipe-unpacked"
rm -rf "$P"; mkdir -p "$P/dex" "$P/lib" "$P/assets" "$B/classes" "$B/cbobj"

# --- Java -> dex
rm -rf "$B/classes"; mkdir -p "$B/classes"
javac -source 8 -target 8 -cp "$AJAR:$MP/core/classes.jar:$MP/vision/classes.jar:$MP/arcore/classes.jar" -d "$B/classes" \
  "$PX/stubs/com/roblox/engine/jni/NativeGLInterface.java" "$PX/stubs/com/roblox/engine/jni/NativeInputInterface.java" \
  "$PX"/src/com/phonexr/roblox/*.java
jar cf "$B/bridge.jar" -C "$B/classes" com/phonexr/roblox
A="$B/mediapipe-artifacts"
"$BT/d8" --release --min-api 26 --output "$P/dex" "$B/bridge.jar" \
  "$MP/core/classes.jar" "$MP/vision/classes.jar" "$MP/arcore/classes.jar" \
  "$A/guava-27.0.1-android.jar" "$A/failureaccess-1.0.1.jar" "$A/flogger-0.6.jar" "$A/flogger-system-backend-0.6.jar" \
  "$A/protobuf-javalite-4.26.1.jar" "$A/firebase-encoders-17.0.0.jar" "$A/firebase-encoders-proto-16.0.0.jar" "$A/javax.inject-1.jar" \
  "$MP/firebase-encoders-json-18.0.0/classes.jar" "$MP/transport-api-3.0.0/classes.jar" \
  "$MP/transport-backend-cct-3.1.0/classes.jar" "$MP/transport-runtime-3.1.0/classes.jar"

# --- third-party natives + assets
cp "$MP/core/jni/arm64-v8a/libmediapipe_tasks_jni.so" "$MP/arcore/jni/arm64-v8a/libarcore_sdk_c.so" "$MP/arcore/jni/arm64-v8a/libarcore_sdk_jni.so" "$P/lib/"
cp "$PX/hand_landmarker.task" "$PX/assets/MaterialSymbolsRounded.ttf" "$P/assets/"

# --- libphonexrvr per profile
CB="$PX/native/cardboard"
for prof in "$PX"/native/profiles/*.h; do
  v=$(basename "$prof" .h); O="$B/cbobj/$v"; rm -rf "$O"; mkdir -p "$O"
  for src in "$CB/head_tracker.cc" "$CB"/sensors/*.cc "$CB"/sensors/android/*.cc "$CB"/util/*.cc "$PX/native/cardboard_head.cc"; do
    "${CLANG}++" -c -fPIC -O2 -std=c++17 -I "$CB" -o "$O/$(basename "$src" .cc).o" "$src"
  done
  "$CLANG" -c -fPIC -O2 -I "$PX/native" -DPXR_PROFILE_H="\"profiles/$v.h\"" -o "$O/cardboard_swap.o" "$PX/native/cardboard_swap.c"
  "${CLANG}++" -shared -fPIC -O2 -static-libstdc++ -Wl,-soname,libphonexrvr.so -o "$P/lib/libphonexrvr_$v.so" "$O"/*.o -lEGL -lGLESv2 -landroid -llog -ldl
done
ls -la "$P" "$P/lib" "$P/dex"
