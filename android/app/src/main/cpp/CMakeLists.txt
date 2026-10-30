cmake_minimum_required(VERSION 3.22.1)
project("better_whisper_jni")

# Import pre-built libphonon.so from Fermion Research
add_library(phonon SHARED IMPORTED)
set_target_properties(phonon PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libphonon.so"
)

# Import compiled libbetter_whisper_core.so from Rust
add_library(better_whisper_core SHARED IMPORTED)
set_target_properties(better_whisper_core PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libbetter_whisper_core.so"
)

# Build our JNI bridge
add_library(phonon_jni SHARED phonon_jni.cpp)

find_library(log-lib log)

target_link_libraries(
    phonon_jni
    phonon
    better_whisper_core
    ${log-lib}
)
