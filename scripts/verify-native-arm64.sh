#!/bin/bash
# ---------------------------------------------------------------------------
# Verifies Charaly's ARM64 native build on this aarch64 host.
#
# The Android NDK ships an x86_64-only clang, so llama.cpp cannot be compiled
# with the NDK here. This script compiles exactly the translation units the app
# links (ggml + llama + the JNI bridge) with a native arm64 Android clang
# against the NDK sysroot, producing a real arm64-v8a Android shared object.
#
# This is a LOCAL VERIFICATION HARNESS. The normal build path is
#     ./gradlew :app:assembleDebug
# on a host where the NDK's clang can execute.
#
# Usage: scripts/verify-native-arm64.sh [output-dir]
# ---------------------------------------------------------------------------
set -uo pipefail

NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/27.0.12077973}"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
TERMUX_BIN="${TERMUX_BIN:-/data/data/com.termux/files/usr/bin}"
TERMUX_PREFIX="$(cd "$TERMUX_BIN/.." && pwd)"
CXX="$TERMUX_BIN/aarch64-linux-android-clang++"
CC="$TERMUX_BIN/aarch64-linux-android-clang"
AR="$TERMUX_BIN/llvm-ar"
CPP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../app/src/main/cpp" && pwd)"
LLAMA="$CPP_DIR/llama.cpp"
OUT="${1:-/tmp/charaly-native-verify}"
JOBS="${JOBS:-3}"

[ -f "$LLAMA/CMakeLists.txt" ] || { echo "llama.cpp missing; run scripts/setup-llama.sh" >&2; exit 1; }
[ -x "$CXX" ] || { echo "native arm64 Android clang not found at $CXX" >&2; exit 1; }

mkdir -p "$OUT/obj"

COMMON=(
  "--target=aarch64-linux-android26"
  "-isysroot $SYSROOT"
  "-isystem $TERMUX_PREFIX/usr/include/c++/v1"
  -O1 -fPIC -fvisibility=hidden
  -DGGML_USE_CPU -DGGML_BACKEND_DL -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF \
  '-DGGML_VERSION="0.0.1"' '-DGGML_COMMIT="charaly"'
  -Wno-unused-function -Wno-sign-compare -Wno-unused-variable
)

CXXFLAGS=("${COMMON[@]}" -std=c++17
  -I"$LLAMA/include" -I"$LLAMA/ggml/include" -I"$LLAMA/ggml/src" -I"$LLAMA/ggml/src/ggml-cpu"
  -I"$LLAMA/src" -I"$LLAMA/common")

CFLAGS=("${COMMON[@]}" -std=c11
  -I"$LLAMA/ggml/include" -I"$LLAMA/ggml/src" -I"$LLAMA/ggml/src/ggml-cpu")

# Only the backends that actually compile for arm64. The real CMake build picks
# these automatically via GGML_NATIVE/arch detection.
ARM_SRCS=(
  "$LLAMA/ggml/src/ggml.c"
  "$LLAMA/ggml/src/ggml-alloc.c"
  "$LLAMA/ggml/src/ggml-backend.cpp"
  "$LLAMA/ggml/src/ggml-backend-reg.cpp"
  "$LLAMA/ggml/src/ggml-opt.cpp"
  "$LLAMA/ggml/src/ggml-quants.c"
  "$LLAMA/ggml/src/ggml-threading.cpp"
  "$LLAMA/ggml/src/ggml-cpu/ggml-cpu.c"
  "$LLAMA/ggml/src/ggml-cpu/ggml-cpu-impl.c"
  "$LLAMA/ggml/src/ggml-cpu/quants.c"
  "$LLAMA/ggml/src/ggml-cpu/vec.cpp"
  "$LLAMA/ggml/src/ggml-cpu/ops.cpp"
  "$LLAMA/ggml/src/ggml-cpu/arch/arm/quants.c"
)

CPP_SRCS=(
  "$LLAMA/ggml/src/ggml-cpu/ggml-cpu.cpp"
  "$LLAMA/ggml/src/ggml-cpu/tensor.cpp"
  "$LLAMA/ggml/src/ggml-cpu/repack.cpp"
  "$LLAMA/ggml/src/ggml-cpu/arch/arm/kernels.cpp"
  "$LLAMA/ggml/src/ggml-cpu/arch/arm/wqmmx/kernels.cpp"
  "$LLAMA/ggml/src/ggml-alloc.c"
  "$LLAMA/src/llama.cpp"
  "$LLAMA/src/llama-arch.cpp"
  "$LLAMA/src/llama-batch.cpp"
  "$LLAMA/src/llama-cparams.cpp"
  "$LLAMA/src/llama-context.cpp"
  "$LLAMA/src/llama-impl.cpp"
  "$LLAMA/src/llama-memory.cpp"
  "$LLAMA/src/llama-memory-hybrid.cpp"
  "$LLAMA/src/llama-memory-recurrent.cpp"
  "$LLAMA/src/llama-metadata.cpp"
  "$LLAMA/src/llama-model.cpp"
  "$LLAMA/src/llama-model-loader.cpp"
  "$LLAMA/src/llama-model-saver.cpp"
  "$LLAMA/src/llama-sampling.cpp"
  "$LLAMA/src/llama-vocab.cpp"
  "$LLAMA/src/llama-unicode.cpp"
  "$LLAMA/src/llama-unicode-data.cpp"
  "$LLAMA/src/unicode.cpp"
  "$LLAMA/src/unicode-data.cpp"
  "$LLAMA/src/llama-adapter.cpp"
  "$LLAMA/src/llama-chat.cpp"
  "$LLAMA/src/llama-speculative.cpp"
  "$LLAMA/src/llama-quant.cpp"
  "$LLAMA/src/llama-cparams.h"
  "$CPP_DIR/charaly_jni.cpp"
)

compile_one() {
  local src="$1" obj="$2" cxx="$3" cc="$4"
  [ -f "$src" ] || { echo "  skip (absent) $(basename "$src")"; return 0; }
  mkdir -p "$(dirname "$obj")"
  if [ "${src##*.}" = "c" ]; then
    "$cc" "${CFLAGS[@]}" -c "$src" -o "$obj" 2>"$obj.log"
  else
    "$cxx" "${CXXFLAGS[@]}" -c "$src" -o "$obj" 2>"$obj.log"
  fi
  if [ $? -ne 0 ]; then
    echo "  FAILED $(basename "$src")"; head -8 "$obj.log"; return 1
  fi
  rm -f "$obj.log"
  # An empty output means nothing to link; drop it so the final link stays clean.
  if [ ! -s "$obj" ]; then rm -f "$obj"; echo "  skip (empty) $(basename "$src")"; return 0; fi
  echo "  ok   $(basename "$src")"; return 0
}

echo "==> compiling ${#ARM_SRCS[@]} C + ${#CPP_SRCS[@]} C++ translation units for arm64-v8a"
pids=(); fail=0
for f in "${ARM_SRCS[@]}"; do
  n=$(echo "${f#$LLAMA/}" | tr '/' '_'); n="${n%.*}"
  compile_one "$f" "$OUT/obj/$n.o" "$CXX" "$CC" & pids+=($!)
  [ ${#pids[@]} -ge $JOBS ] && { wait "${pids[0]}" || fail=1; pids=("${pids[@]:1}"); }
done
for f in "${CPP_SRCS[@]}"; do
  case "$f" in
    *.cpp) ;;                       # only C++ sources; a stray .h would emit no object
    *) continue ;;
  esac
  n=$(echo "${f#$LLAMA/}" | tr '/' '_'); n="${n%.*}"
  compile_one "$f" "$OUT/obj/$n.o" "$CXX" "$CC" & pids+=($!)
  [ ${#pids[@]} -ge $JOBS ] && { wait "${pids[0]}" || fail=1; pids=("${pids[@]:1}"); }
done
for p in "${pids[@]}"; do wait "$p" || fail=1; done

[ $fail -eq 0 ] || { echo "==> native verification FAILED" >&2; exit 1; }

echo "==> linking libcharaly_llama.so"
if ! "$CXX" "${CXXFLAGS[@]}" -shared \
  -Wl,--gc-sections \
  -o "$OUT/libcharaly_llama.so" "$OUT"/obj/*.o \
  -llog -lz -lm \
  -L"$TERMUX_PREFIX/usr/lib" -lc++_shared; then
  echo "==> link failed" >&2
  exit 1
fi

echo
echo "==> OK"
ls -la "$OUT/libcharaly_llama.so"
python3 - "$OUT/libcharaly_llama.so" <<'PY'
import struct, sys
d = open(sys.argv[1], "rb").read(64)
machine = struct.unpack_from("<H", d, 18)[0]
names = {3: "x86", 62: "x86_64", 183: "AArch64"}
print("ELF machine =", machine, "->", names.get(machine, "?"))
assert machine == 183, "expected an AArch64 shared object"
PY
