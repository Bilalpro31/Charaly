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
set(LLAMA_STANDALONE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(charaly_llama SHARED charaly_jni.cpp)

target_include_directories(charaly_llama PRIVATE "${LLAMA_CPP_DIR}/include")
target_link_libraries(charaly_llama PRIVATE llama ggml android log)

target_compile_options(charaly_llama PRIVATE -O3 -fvisibility=hidden)

# JNI entry points must be exported even though everything else is hidden.
target_compile_definitions(charaly_llama PRIVATE JNIEXPORT=__attribute__((visibility("default"))))
