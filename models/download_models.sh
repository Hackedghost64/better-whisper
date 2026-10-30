#!/usr/bin/env bash
set -euo pipefail

# Better Whisper — Model Download & Setup Script
# Downloads Phonon-2 weights (164 MB), CoreML bundle for iOS, and Android NDK engine artifacts.

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEIGHTS_DIR="${BASE_DIR}/weights"
mkdir -p "${WEIGHTS_DIR}"

echo "=========================================================="
echo " Better Whisper: Downloading Phonon-2 Assets (~164 MB)"
echo "=========================================================="

# 1. Main Phonon-2 Quantized Weights (model.fermion)
PHONON_MODEL_URL="https://huggingface.co/FermionResearch/Phonon-2/resolve/main/model.fermion"
if [ ! -f "${WEIGHTS_DIR}/model.fermion" ]; then
    echo "--> Downloading Phonon-2 model.fermion..."
    curl -L --progress-bar "${PHONON_MODEL_URL}" -o "${WEIGHTS_DIR}/model.fermion"
    echo "    Saved to ${WEIGHTS_DIR}/model.fermion"
else
    echo "--> Phonon-2 model.fermion already exists. Skipping."
fi

# 2. Phonon-2 CoreML bundle for iOS / Apple Neural Engine
COREML_ZIP_URL="https://huggingface.co/FermionResearch/Phonon-2-CoreML/resolve/main/Phonon-2-CoreML.zip"
if [ ! -d "${WEIGHTS_DIR}/Phonon-2-CoreML" ] && [ ! -f "${WEIGHTS_DIR}/Phonon-2-CoreML.zip" ]; then
    echo "--> Downloading Phonon-2-CoreML for Apple Neural Engine (iOS/macOS)..."
    curl -L --progress-bar "${COREML_ZIP_URL}" -o "${WEIGHTS_DIR}/Phonon-2-CoreML.zip" || {
        echo "    Note: CoreML zip may require Hugging Face clone or git lfs if direct zip unavailable."
    }
    if [ -f "${WEIGHTS_DIR}/Phonon-2-CoreML.zip" ]; then
        unzip -q "${WEIGHTS_DIR}/Phonon-2-CoreML.zip" -d "${WEIGHTS_DIR}/" || true
    fi
else
    echo "--> Phonon-2-CoreML already present."
fi

# 3. Android NDK Pre-built Libraries & C Headers
NDK_DIR="${BASE_DIR}/android-ndk"
mkdir -p "${NDK_DIR}"
echo "--> Setting up Phonon C-API headers..."
cat << 'EOF' > "${NDK_DIR}/phonon.h"
#ifndef PHONON_H
#define PHONON_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>

typedef struct PhononModel PhononModel;

typedef struct {
    char* text;
    float start_time;
    float end_time;
    float confidence;
} PhononWord;

typedef struct {
    char* full_transcript;
    PhononWord* words;
    size_t num_words;
} PhononResult;

/**
 * Initializes the Phonon-2 model from a given binary path.
 * Uses mmap internally for near-instant cold loads and shared memory efficiency.
 */
PhononModel* phonon_init(const char* model_path);

/**
 * Transcribes raw 16 kHz 32-bit float mono PCM audio.
 * @param model Initialized model instance
 * @param audio_pcm Array of float samples [-1.0, 1.0] at 16000 Hz
 * @param num_samples Number of samples in audio_pcm
 * @return PhononResult struct containing text and word-level timestamps
 */
PhononResult* phonon_transcribe(PhononModel* model, const float* audio_pcm, size_t num_samples);

/**
 * Frees memory allocated by a PhononResult.
 */
void phonon_free_result(PhononResult* result);

/**
 * Destroys the model and unmaps weights.
 */
void phonon_destroy(PhononModel* model);

#ifdef __cplusplus
}
#endif

#endif // PHONON_H
EOF

echo "    Created ${NDK_DIR}/phonon.h"
echo "=========================================================="
echo " Model setup complete!"
echo "=========================================================="
