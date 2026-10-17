#!/bin/sh
# Downloads the third-party pieces the bridge is built from (not stored in git):
# MediaPipe / ARCore / support libraries, the hand model and APKEditor.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PX="$ROOT/phonexr-bridge"; A="$PX/build/mediapipe-artifacts"; U="$PX/build/mediapipe-unpacked"
CENTRAL=https://repo1.maven.org/maven2; GOOGLE=https://dl.google.com/dl/android/maven2
mkdir -p "$A" "$U" "$ROOT/vrkit/tools"
get() { # repo path
  f="$A/$(basename "$2")"; [ -s "$f" ] || { echo "  $(basename "$2")"; curl -fsSL "$1/$2" -o "$f"; }
}
get $GOOGLE com/google/mediapipe/tasks-core/0.10.35/tasks-core-0.10.35.aar
get $GOOGLE com/google/mediapipe/tasks-vision/0.10.35/tasks-vision-0.10.35.aar
get $GOOGLE com/google/ar/core/1.46.0/core-1.46.0.aar
get $CENTRAL com/google/guava/guava/27.0.1-android/guava-27.0.1-android.jar
get $CENTRAL com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar
get $CENTRAL com/google/flogger/flogger/0.6/flogger-0.6.jar
get $CENTRAL com/google/flogger/flogger-system-backend/0.6/flogger-system-backend-0.6.jar
get $CENTRAL com/google/protobuf/protobuf-javalite/4.26.1/protobuf-javalite-4.26.1.jar
get $GOOGLE com/google/firebase/firebase-encoders/17.0.0/firebase-encoders-17.0.0.jar
get $GOOGLE com/google/firebase/firebase-encoders-proto/16.0.0/firebase-encoders-proto-16.0.0.jar
get $CENTRAL javax/inject/javax.inject/1/javax.inject-1.jar
get $GOOGLE com/google/firebase/firebase-encoders-json/18.0.0/firebase-encoders-json-18.0.0.aar
get $GOOGLE com/google/android/datatransport/transport-api/3.0.0/transport-api-3.0.0.aar
get $GOOGLE com/google/android/datatransport/transport-backend-cct/3.1.0/transport-backend-cct-3.1.0.aar
get $GOOGLE com/google/android/datatransport/transport-runtime/3.1.0/transport-runtime-3.1.0.aar

unpack() { rm -rf "$U/$2"; mkdir -p "$U/$2"; unzip -qo "$A/$1" -d "$U/$2"; }
unpack tasks-core-0.10.35.aar core
unpack tasks-vision-0.10.35.aar vision
unpack core-1.46.0.aar arcore
unpack firebase-encoders-json-18.0.0.aar firebase-encoders-json-18.0.0
unpack transport-api-3.0.0.aar transport-api-3.0.0
unpack transport-backend-cct-3.1.0.aar transport-backend-cct-3.1.0
unpack transport-runtime-3.1.0.aar transport-runtime-3.1.0

[ -s "$PX/hand_landmarker.task" ] || curl -fsSL https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -o "$PX/hand_landmarker.task"
[ -s "$ROOT/vrkit/tools/APKEditor.jar" ] || curl -fsSL https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar -o "$ROOT/vrkit/tools/APKEditor.jar"
echo "Dependencies ready."
