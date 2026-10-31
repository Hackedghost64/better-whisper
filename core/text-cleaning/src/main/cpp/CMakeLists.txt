cmake_minimum_required(VERSION 3.22.1)
project("cleaner_jni")

add_library(better_whisper_core SHARED IMPORTED)
set_target_properties(better_whisper_core PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libbetter_whisper_core.so"
    IMPORTED_SONAME "libbetter_whisper_core.so"
)

add_library(cleaner_jni SHARED cleaner_jni.cpp)

target_link_libraries(
    cleaner_jni
    better_whisper_core
)
