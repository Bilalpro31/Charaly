cmake_minimum_required(VERSION 3.22)
project(charaly_native_harness CXX C)

# ---------------------------------------------------------------------------
# HOST-SIDE NATIVE HARNESS.
#
# Builds the SAME translation unit the app ships - app/src/main/cpp/charaly_jni.cpp,
# against the SAME vendored llama.cpp - as a host shared object, so the exact JNI
# entry points the Android build exports can be exercised by a real JVM with a real
# GGUF and produce real tokens.
#
# This is a verification harness, not a shipping artifact. The shipping build is
# app/src/main/cpp/CMakeLists.txt; the reason this file exists separately is that the
# Android build links `android` and `log`, which do not exist here. Everything the
# inference chain actually runs is shared verbatim.
# ---------------------------------------------------------------------------

get_filename_component(CHARALY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(LLAMA_CPP_DIR "${CHARALY_ROOT}/app/src/main/cpp/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}; run scripts/setup-llama.sh")
endif()

# Identical flags to the Android build, so the comparison is about the JNI chain and
# not about a different llama.cpp configuration.
set(LLAMA_STANDALONE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# The static llama/ggml archives are linked into a shared object here, so they must be
# position-independent. (Android's NDK toolchain sets this for every target; a host build
# does not, which is why this is stated explicitly rather than assumed.)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp EXCLUDE_FROM_ALL)

# Only jni.h is needed - the harness never links libjvm (the JVM loads *us*). Asking
# CMake's FindJNI for the full package drags in AWT, which a headless builder does not
# have, so the headers are located directly.
find_path(JNI_INCLUDE_DIR jni.h
    HINTS
        "$ENV{JAVA_HOME}/include"
        "${JAVA_HOME}/include"
        "/usr/lib/jvm/default-java/include"
        "/usr/lib/jvm/java-17-openjdk-arm64/include")
if(NOT JNI_INCLUDE_DIR)
    message(FATAL_ERROR "jni.h not found; set JAVA_HOME to a JDK")
endif()

add_library(charaly_llama SHARED "${CHARALY_ROOT}/app/src/main/cpp/charaly_jni.cpp")

target_include_directories(charaly_llama PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${JNI_INCLUDE_DIR}"
    "${JNI_INCLUDE_DIR}/linux")

target_link_libraries(charaly_llama PRIVATE llama ggml)

# Same visibility rule as the app: only the JNI entry points are exported.
target_compile_options(charaly_llama PRIVATE -O2 -fvisibility=hidden)