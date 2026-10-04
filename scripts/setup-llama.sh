#!/usr/bin/env bash
# Fetches the llama.cpp source that Charaly compiles into its native library.
#
# Charaly does NOT download a model and does NOT run a server: llama.cpp is
# compiled into the app's own .so and inference happens in-process.
#
# Usage:  scripts/setup-llama.sh [version-or-commit]
#
# The checkout is placed at app/src/main/cpp/llama.cpp, which is where
# app/src/main/cpp/CMakeLists.txt looks for it by default.
set -euo pipefail

REF="${1:-b6392}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${ROOT}/app/src/main/cpp/llama.cpp"

if [[ -d "${TARGET}/.git" ]]; then
  echo "==> updating existing llama.cpp checkout at ${TARGET}"
  git -C "${TARGET}" fetch --depth 1 origin "${REF}"
  git -C "${TARGET}" checkout FETCH_HEAD
else
  echo "==> cloning llama.cpp @ ${REF}"
  rm -rf "${TARGET}"
  git clone --depth 1 --branch "${REF}" https://github.com/ggerganov/llama.cpp "${TARGET}" || {
    echo "!! '${REF}' is not a branch or tag; trying it as a commit sha" >&2
    rm -rf "${TARGET}"
    mkdir -p "${TARGET}"
    git init -q "${TARGET}"
    git -C "${TARGET}" remote add origin https://github.com/ggerganov/llama.cpp
    git -C "${TARGET}" fetch --depth 1 origin "${REF}"
    git -C "${TARGET}" checkout FETCH_HEAD
  }
fi

echo
echo "llama.cpp is ready at ${TARGET}"
echo
echo "Next:"
echo "  1. Import a GGUF model on the device (Charaly -> Models -> Import)."
echo "  2. Build the app:  ./gradlew :app:assembleDebug"
echo
echo "Charaly runs inference locally. No server, no network, no API key."
