cmake_minimum_required(VERSION 3.22.1)
project(charaly_llama CXX C)

# ---------------------------------------------------------------------------
# Charaly native inference: llama.cpp, compiled into the app process.
#
# There is no server, no subprocess and no network here. The .so is loaded by
# System.loadLibrary("charaly_llama") and called over JNI.
#
# llama.cpp is MIT licensed. Point CHARALY_LLAMA_CPP_DIR at a checkout of
# llama.cpp (see scripts/setup-llama.sh), or drop it at app/src/main/cpp/llama.cpp.
# ---------------------------------------------------------------------------

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp"
    CACHE PATH "Path to a llama.cpp checkout")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_CPP_DIR}.\n"
        "Run scripts/setup-llama.sh to fetch it, or pass "
        "-DLLAMA_CPP_DIR=/path/to/llama.cpp")
endif()

# Build llama.cpp as a static library from its own CMake project. Only CPU
# inference is enabled: it is portable, and GPU backends add large binary
# dependencies for no benefit on the CPU-first path.
#
# The UI takes this seriously rather than merely respecting it: the app reads the
# device list back out of ggml at runtime (LlamaNative.availableBackends) and the model
# library can only render an accelerator name that appears there. So a CPU-only build
# is structurally incapable of displaying "GPU accelerated" - the claim cannot be made
# by forgetting a flag, only by compiling one.
#
# Enabling OpenCL/Adreno or Vulkan is a separate change, to be decided from measured
# CPU-baseline benchmarks rather than before them. See the note on n_gpu_layers below.
set(LLAMA_STANDALONE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# 32-bit armeabi-v7a: llama.cpp's llamafile sgemm uses ARM fp16 vector
# intrinsics (vld1q_f16) that clang only declares for AArch64, so the file
# cannot be compiled for this ABI. Turning the optimisation off keeps the ABI
# in the APK; ggml then uses its generic GEMM path, which is correct on every
# ABI. arm64-v8a keeps the full llamafile path.
if(ANDROID AND ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(charaly_llama SHARED charaly_jni.cpp)

target_include_directories(charaly_llama PRIVATE "${LLAMA_CPP_DIR}/include")
target_link_libraries(charaly_llama PRIVATE llama ggml android log)

# Only the JNI entry points are exported. jni.h already annotates JNIEXPORT with
# __attribute__((visibility("default"))), so hiding everything else is enough.
target_compile_options(charaly_llama PRIVATE -O3 -fvisibility=hidden)
