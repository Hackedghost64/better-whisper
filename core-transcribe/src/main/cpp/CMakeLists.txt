cmake_minimum_required(VERSION 3.22.1)
project("transcribe_jni")

add_library(phonon SHARED IMPORTED)
set_target_properties(phonon PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libphonon.so"
)

add_library(transcribe_jni SHARED transcribe_jni.cpp)

find_library(log-lib log)

target_link_libraries(
    transcribe_jni
    phonon
    ${log-lib}
)
