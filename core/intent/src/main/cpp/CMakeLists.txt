cmake_minimum_required(VERSION 3.22.1)
project("intent_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
    endif()
endif()

set(LLAMA_BUILD_APP OFF CACHE BOOL "llama: build app" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "llama: openssl" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: llamafile" FORCE)

set(LLAMA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../vendor/llama.cpp")

add_subdirectory("${LLAMA_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/llama_build")

add_library(intent_jni SHARED
    intent_jni.cpp
)

target_include_directories(intent_jni PRIVATE
    "${LLAMA_ROOT}"
    "${LLAMA_ROOT}/include"
    "${LLAMA_ROOT}/common"
    "${LLAMA_ROOT}/ggml/include"
    "${LLAMA_ROOT}/ggml/src"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(intent_jni
    llama
    llama-common
    ${log-lib}
    ${android-lib}
)
