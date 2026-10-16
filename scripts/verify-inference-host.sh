#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# REAL GGUF -> REAL llama.cpp -> REAL TOKENS, on this machine.
#
# Builds the exact JNI translation unit the APK ships (app/src/main/cpp/charaly_jni.cpp)
# against the exact vendored llama.cpp, links it as a host shared object, and drives the
# exported `Java_dev_charaly_app_inference_LlamaNative_*` entry points from a real JVM with
# a real GGUF model file.
#
# This is the strongest proof of the model chain that is obtainable without an Android
# device: the same symbols the app calls, the same sampler loop, the same GGUF parser in
# llama.cpp. What it does NOT prove is ART/Android specifics (bionic malloc behaviour under
# LMKD, the NDK build of the same file). Those need a device - see scripts/verify-native-arm64.sh
# for the Android-targeted build check and the APK inspection for packaging.
#
# Usage:
#   scripts/verify-inference-host.sh <model-a.gguf> [model-b.gguf]
#
# Exit code is non-zero if ANY case fails, and "PASS" is only printed for a case that saw
# real decoded tokens come back through the JNI callback.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT/tools/native-harness"
BUILD="$HARNESS/build"
CLASSES="$HARNESS/classes"
MODEL_A="${1:-}"
MODEL_B="${2:-$MODEL_A}"
JOBS="${JOBS:-$(nproc)}"

[ -n "$MODEL_A" ] || { echo "usage: $0 <model-a.gguf> [model-b.gguf]" >&2; exit 2; }
[ -f "$MODEL_A" ] || { echo "no such model: $MODEL_A" >&2; exit 2; }
[ -f "$ROOT/app/src/main/cpp/llama.cpp/CMakeLists.txt" ] || {
    echo "llama.cpp missing; run scripts/setup-llama.sh" >&2; exit 2
}
command -v javac >/dev/null || { echo "javac is required for the harness" >&2; exit 2; }

echo "==> configuring host build of charaly_llama (same sources as the APK)"
# Locate the JDK so jni.h is found. The harness is loaded BY the JVM and links nothing
# from it, so headers are all that is required.
if [ -z "${JAVA_HOME:-}" ]; then
    JAVAC_BIN="$(readlink -f "$(command -v javac)")"
    export JAVA_HOME="$(dirname "$(dirname "$JAVAC_BIN")")"
fi
echo "    JAVA_HOME=$JAVA_HOME"
cmake -S "$HARNESS" -B "$BUILD" \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DLLAMA_CURL=OFF \
    -DGGML_CURL=OFF \
    >/dev/null

echo "==> building (this compiles llama.cpp + the JNI bridge)"
cmake --build "$BUILD" --target charaly_llama -j "$JOBS" >/dev/null

LIB="$BUILD/libcharaly_llama.so"
[ -f "$LIB" ] || { echo "build did not produce $LIB" >&2; exit 1; }
echo "    $(ls -la "$LIB" | awk '{print $5, $9}')"

echo "==> compiling the JNI harness (mirrors the Kotlin externals exactly)"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"
# UTF-8 explicitly: the harness carries Turkish prompts on purpose, and a platform-default
# US-ASCII javac would either refuse them or - worse - mangle them into "?" and then blame
# the unicode handling in charaly_jni.cpp.
javac -encoding UTF-8 -d "$CLASSES" "$HARNESS"/src/dev/charaly/app/inference/*.java

echo "==> running the model chain against real GGUF files"
echo
java -Dcharaly.native.lib="$LIB" \
    -Dfile.encoding=UTF-8 \
    -Dsun.stdout.encoding=UTF-8 \
    -cp "$CLASSES" \
    dev.charaly.app.inference.Harness "$MODEL_A" "$MODEL_B"