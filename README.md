# Better Whisper (Offline Mobile "Wispr Flow")

A 100% offline, zero-cost, ultra-fast speech dictation engine for mobile devices powered by **Fermion Research's Phonon-2** (164 MB) and an instant Rust-powered **Wispr Flow** post-processing pipeline.

---

## ⚡ Why Phonon-2 Beats Whisper on Mobile

| Feature | **Phonon-2** | **Whisper Large-v3-Turbo** | **Whisper Base / Small** |
| :--- | :--- | :--- | :--- |
| **Model Size** | **164 MB** | ~1.6 GB | ~150 MB – ~460 MB |
| **Inference Speed** | **170x+ real-time** | ~20x–40x real-time | ~30x–60x real-time |
| **Architecture** | FastConformer-TDT (Non-autoregressive) | Encoder-Decoder (Autoregressive) | Encoder-Decoder |
| **Repetition Hallucinations** | **Zero** (TDT architecture prevents infinite loops) | Common on silence, music, or pauses | Common |
| **English Benchmark WER** | **5.21%** | 6.58% | 8% – 12%+ |
| **Mobile Runtimes** | CoreML (Apple Neural Engine) & Android NDK | Whisper.cpp / GGML | Whisper.cpp |
| **Cost & Privacy** | **$0 / 100% On-Device** | High compute / heavy battery drain | Inaccurate on names / technical words |

---

## 🧩 Architecture

```text
better-whisper/
├── core/                                # Shared Rust Flow Cleaning & VAD Engine (C-ABI)
│   ├── src/
│   │   ├── cleaner.rs                   # Disfluency & filler stripping, stutter removal, self-corrections
│   │   ├── dictionary.rs                # Custom vocabulary casing & voice snippet expansion
│   │   ├── vad.rs                       # Silero VAD pause-based pre-transcription chunking
│   │   ├── ffi.rs                       # C-ABI export (better_whisper_clean_text)
│   │   └── lib.rs
│   └── Cargo.toml
├── android/                             # Android Native Integration (NDK + IME + Overlay)
│   ├── app/src/main/cpp/
│   │   ├── phonon_jni.cpp               # JNI bridge connecting Kotlin -> libphonon.so + libbetter_whisper_core.so
│   │   └── CMakeLists.txt
│   └── app/src/main/kotlin/com/betterwhisper/
│       ├── engine/PhononEngine.kt       # Persistent, mmap-backed Phonon-2 manager
│       ├── ime/FlowInputMethodService.kt# System-wide custom voice keyboard
│       └── service/FlowAccessibilityService.kt # Floating overlay pill for instant in-place injection
├── ios/                                 # iOS Native Integration (CoreML + Keyboard Extension)
│   ├── PhononBridge/PhononBridge.swift  # Swift wrapper around Fermion's phonon-coreml (Apple Neural Engine)
│   └── FlowKeyboardExtension/FlowKeyboardExtension.swift # iOS UIInputViewController voice keyboard
├── models/
│   ├── download_models.sh               # Fetches Phonon-2 weights (164 MB) & NDK C headers
│   └── android-ndk/phonon.h             # C header for libphonon.so
└── scripts/
    └── benchmark_flow.py                # End-to-end Python test & latency benchmark suite
```

---

## 🚀 Quick Start & Verification

### 1. Build the Rust Flow Engine
```bash
cd core
cargo test
cargo build --release
```

### 2. Run the Benchmark & Validation Suite
```bash
python3 scripts/benchmark_flow.py
```
**Results:**
- **Filler Word Removal:** Strips *"um"*, *"uh"*, *"er"*, *"ah"* while cleaning up punctuation.
- **Stutter Deduplication:** De-duplicates single words (*"I I"*) and multi-word repetitions (*"we need to we need to"*).
- **Self-Corrections:** Handles verbal corrections like *"scratch that"* and *"no wait [replacement]"*.
- **Context-Aware Casing:** Capitalizes after sentence terminators, lowercases and prefixes leading spaces when continuing an existing sentence.
- **Custom Dictionary:** Enforces formatting on tech terms (e.g. *Phonon-2*, *Wispr Flow*, *iOS*, *macOS*).
- **Throughput Latency:** **~1.5 milliseconds (<0.002s)** per transcription pass.

---

## 📱 Mobile Deployment

### Android (API 26+, `arm64-v8a`)
1. Download the Phonon-2 weights and Android NDK engine:
   ```bash
   bash models/download_models.sh
   ```
2. Place `libphonon.so` and `libbetter_whisper_core.so` into `android/app/src/main/jniLibs/arm64-v8a/`.
3. Build the Android app using Android Studio or Gradle. Enable either:
   - **Flow Keyboard (IME):** Settings $\rightarrow$ System $\rightarrow$ Languages & Input $\rightarrow$ Enable "Better Whisper Keyboard".
   - **Floating Overlay Pill:** Accessibility $\rightarrow$ Enable "Better Whisper Flow Service" to dictate anywhere without switching keyboards.

### iOS (iOS 18+, Apple Neural Engine)
1. In Xcode, add the Swift package: `https://github.com/fermionresearch/phonon-coreml`.
2. Add `Phonon-2-CoreML` to your App Group shared container.
3. Add the `FlowKeyboardExtension` target and enable `RequestsOpenAccess = YES` to access the microphone.
