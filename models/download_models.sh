#!/usr/bin/env bash
# Better Whisper — Android asset setup.
#
# Downloads the Phonon-2 English weights (Android build) and wires the real
# libphonon C library into the Gradle modules. Android only: the iOS/CoreML
# target has been archived (see archive/ios) and is no longer built.
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${BASE_DIR}/.." && pwd)"
WEIGHTS_DIR="${BASE_DIR}/weights"

PHONON_REPO="https://huggingface.co/FermionResearch/Phonon-2"
PHONON_ARCHIVE="phonon-2.bps.tar.zst"
MODEL_DIR="${WEIGHTS_DIR}/phonon2"

SDK_DIR="${BASE_DIR}/android-sdk/libphonon-1.1.0-android-arm64-v8a"
TRANSCRIBE_JNI_DIR="${PROJECT_ROOT}/core/transcription/src/main/jniLibs/arm64-v8a"
CLEANER_JNI_DIR="${PROJECT_ROOT}/core/text-cleaning/src/main/jniLibs/arm64-v8a"
NDK_ABI="arm64-v8a"

echo "=========================================================="
echo " Better Whisper: Android asset setup"
echo "=========================================================="

mkdir -p "${WEIGHTS_DIR}"

# 1. Phonon-2 weights -------------------------------------------------------
if [ -f "${MODEL_DIR}/model.fermion" ] && [ -f "${MODEL_DIR}/config.json" ]; then
    echo "--> Phonon-2 weights already extracted in ${MODEL_DIR}"
else
    ARCHIVE_PATH="${WEIGHTS_DIR}/${PHONON_ARCHIVE}"
    if [ ! -f "${ARCHIVE_PATH}" ]; then
        echo "--> Downloading ${PHONON_ARCHIVE} (~160 MB)..."
        curl -L --fail --progress-bar \
            "${PHONON_REPO}/resolve/main/${PHONON_ARCHIVE}" \
            -o "${ARCHIVE_PATH}"
    fi

    echo "--> Extracting weights..."
    mkdir -p "${MODEL_DIR}"
    # The release archive is flat (bps_manifest.json, config.json, model.fermion).
    if command -v zstd >/dev/null 2>&1; then
        tar -I zstd -xf "${ARCHIVE_PATH}" -C "${MODEL_DIR}"
    else
        # bsdtar (macOS / Windows) understands zstd natively.
        tar -xf "${ARCHIVE_PATH}" -C "${MODEL_DIR}"
    fi

    if [ ! -f "${MODEL_DIR}/model.fermion" ] || [ ! -f "${MODEL_DIR}/config.json" ]; then
        echo "ERROR: extraction did not produce model.fermion + config.json." >&2
        echo "       Install zstd (apt install zstd) and re-run." >&2
        exit 1
    fi
fi

# 2. libphonon.so + header --------------------------------------------------
if [ ! -f "${SDK_DIR}/lib/libphonon.so" ]; then
    if [ -f "${BASE_DIR}/android-sdk/libphonon.tar.gz" ]; then
        echo "--> Extracting prebuilt libphonon SDK..."
        tar -xzf "${BASE_DIR}/android-sdk/libphonon.tar.gz" -C "${BASE_DIR}/android-sdk"
    else
        echo "ERROR: ${SDK_DIR}/lib/libphonon.so is missing." >&2
        echo "       Download the phonon android-arm64-v8a release into ${BASE_DIR}/android-sdk/." >&2
        exit 1
    fi
fi

mkdir -p "${TRANSCRIBE_JNI_DIR}"
cp "${SDK_DIR}/lib/libphonon.so" "${TRANSCRIBE_JNI_DIR}/libphonon.so"
# Keep the checked-in header identical to the ABI of the shipped .so.
cp "${SDK_DIR}/include/phonon.h" "${PROJECT_ROOT}/core/transcription/src/main/cpp/phonon.h"

# 3. Rust cleaner -----------------------------------------------------------
if command -v cargo >/dev/null 2>&1; then
    echo "--> Building libbetter_whisper_core.so for Android ${NDK_ABI}..."
    : "${ANDROID_NDK_HOME:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
    LINKER="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
    if [ -x "${LINKER}" ]; then
        rustup target add aarch64-linux-android >/dev/null 2>&1 || true
        (
            cd "${PROJECT_ROOT}/core/text-cleaning/engine"
            CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${LINKER}" \
                cargo build --release --target aarch64-linux-android
        )
        mkdir -p "${CLEANER_JNI_DIR}"
        cp "${PROJECT_ROOT}/core/text-cleaning/engine/target/aarch64-linux-android/release/libbetter_whisper_core.so" \
            "${CLEANER_JNI_DIR}/libbetter_whisper_core.so"
    else
        echo "WARNING: NDK clang not found at ${LINKER}; skipping Rust cross-build." >&2
    fi
else
    echo "WARNING: cargo not found; skipping Rust cross-build." >&2
fi

echo
echo "=========================================================="
echo " Assets ready."
echo
echo " Next: install the app, then copy the weights into app-private"
echo " storage (no root needed, survives reboots):"
echo
echo "   adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo "   adb push ${MODEL_DIR}/. /data/local/tmp/phonon2/"
echo "   adb shell run-as com.betterwhisper mkdir -p files/phonon2"
echo "   adb shell run-as com.betterwhisper cp -r /data/local/tmp/phonon2/. files/phonon2/"
echo "=========================================================="
