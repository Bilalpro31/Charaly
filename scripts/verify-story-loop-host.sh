#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# THE REAL STORY CHAT LOOP, with a real GGUF and real decoded tokens.
#
# Builds the exact JNI translation unit the APK ships (app/src/main/cpp/charaly_jni.cpp)
# against the exact vendored llama.cpp, then runs Charaly's REAL story pipeline against a
# real model file:
#
#   Story Pack -> StoryInstance -> WorldState -> ContextBuilder
#     -> LocalModelResidency -> llama.cpp -> REAL TOKENS
#     -> ProposedActionParser -> ActionValidator -> WorldEvent -> EventEngine
#     -> WorldState mutation -> memory / clock -> next context -> next turn
#
# Nothing in that chain is mocked. Only the *user* types fixed strings.
#
# Usage:
#   scripts/verify-story-loop-host.sh <model-a.gguf> [model-b.gguf]
#
# model-b is optional and only widens the model-switching test.
#
# NOTE ON SCOPE: this is a host verification harness. It exercises the same JNI symbols and
# the same llama.cpp that the Android build uses, on a JVM of the same architecture. It does
# not exercise ART, bionic's allocator, or Android's low-memory killer - those need a device.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT/tools/native-harness"
BUILD="$HARNESS/build"
MODEL_A="${1:-}"
MODEL_B="${2:-}"
JOBS="${JOBS:-$(nproc)}"

[ -n "$MODEL_A" ] || { echo "usage: $0 <model-a.gguf> [model-b.gguf]" >&2; exit 2; }
[ -f "$MODEL_A" ] || { echo "no such model: $MODEL_A" >&2; exit 2; }
[ -f "$ROOT/app/src/main/cpp/llama.cpp/CMakeLists.txt" ] || {
    echo "llama.cpp missing; run scripts/setup-llama.sh" >&2; exit 2
}

echo "==> building host libcharaly_llama.so (same sources as the APK)"
if [ -z "${JAVA_HOME:-}" ]; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
cmake -S "$HARNESS" -B "$BUILD" \
    -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$JAVA_HOME" \
    -DLLAMA_CURL=OFF -DGGML_CURL=OFF >/dev/null
cmake --build "$BUILD" --target charaly_llama -j "$JOBS" >/dev/null
LIB="$BUILD/libcharaly_llama.so"
[ -f "$LIB" ] || { echo "build did not produce $LIB" >&2; exit 1; }
echo "    $(ls -la "$LIB" | awk '{print $5, $9}')"

PROPS=(-PcharalyNativeLib="$LIB" -PcharalyModelFile="$MODEL_A")
[ -n "$MODEL_B" ] && [ -f "$MODEL_B" ] && PROPS+=(-PcharalyModelBFile="$MODEL_B")

echo
echo "==> running the REAL story chat loop against real weights"
cd "$ROOT"
./gradlew :app:testDebugUnitTest --offline --console=plain \
    "${PROPS[@]}" \
    --tests "dev.charaly.app.storyloop.RealStoryChatLoopTest"

echo
echo "==> and the pure story-engine suites (integration boundary: scripted transport only)"
./gradlew :charaly-runtime:test --offline --console=plain \
    --tests "dev.charaly.runtime.integration.*" \
    --tests "dev.charaly.runtime.model.*"

echo
echo "==> OK"