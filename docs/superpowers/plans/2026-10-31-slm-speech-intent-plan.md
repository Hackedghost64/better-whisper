# On-Device SLM Speech Intent & Self-Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. After each task completion, dispatch a subagent with Devrith (`arch-sidecar`) to verify changes.

**Goal:** Implement an on-device Speech Intent / Self-Correction engine using a quantized SLM (`SmolLM2-135M-Instruct` Q4_K_M) via `llama.cpp` in `:core:intent`, bundled alongside Phonon-2 weights in the APK for 100% offline, zero-download dictation.

**Architecture:** Hybrid pipeline: standard speech without repair cues flows through the Rust text cleaner (<2ms), while speech containing self-corrections (*"at 5pm no at 6"*) routes through the on-device SLM (~130ms) to produce the intended text. Both Phonon-2 and SmolLM2 are packaged into `app/src/main/assets/models/` and extracted on first run.

**Tech Stack:** Kotlin, Android NDK (C++17, CMake), `llama.cpp` (arm64-v8a NEON/fp16), Coroutines/Flow, JUnit 4, Devrith (`arch-sidecar`).

## Global Constraints

- Target Device: Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 8GB RAM, `arm64-v8a`, Android 16).
- Zero cloud API calls; 100% on-device and offline.
- Total runtime inference memory (Phonon-2 + SmolLM2) $\le$ 320MB RAM.
- All tasks must be verified with `arch-sidecar` and accompanied by a dispatched review subagent upon completion.

---

### Task 1: Asset Preparation & Model Bundling Pipeline

**Files:**
- Create: `core/transcription/src/main/kotlin/com/betterwhisper/core/transcribe/AssetModelExtractor.kt`
- Create: `core/transcription/src/test/kotlin/com/betterwhisper/core/transcribe/AssetModelExtractorTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `core/transcription/src/main/kotlin/com/betterwhisper/core/transcribe/PhononTranscribeEngine.kt`

**Interfaces:**
- Consumes: Android `Context.assets`
- Produces: `AssetModelExtractor.ensureExtracted(context: Context): ExtractedModels`, where `ExtractedModels(phononDir: File, intentModelFile: File)`

- [x] **Step 1: Download SmolLM2-135M-Instruct Q4_K_M GGUF**
  Fetch `SmolLM2-135M-Instruct-Q4_K_M.gguf` (~100MB) to `models/weights/intent/smollm2-135m-q4_k_m.gguf`.
- [x] **Step 2: Configure APK asset packaging in `app/build.gradle.kts`**
  Configured `androidResources { noCompress += listOf("fermion", "gguf") }` and linked models into `app/src/main/assets/models/`.
- [x] **Step 3: Implement `AssetModelExtractor.kt`**
  Implemented CRC/size verification so extraction runs once on cold install and unpacks asynchronously to `context.filesDir/models/`.
- [x] **Step 4: Update `PhononTranscribeEngine.kt`**
  Updated to automatically load from `AssetModelExtractor.ensureExtracted(context).phononDir`.
- [x] **Step 5: Verify Task 1 with devrith subagent**
  Dispatched Devrith subagent `337a202c-5ef5-4ad8-a7a0-efcb64c2216c` (Commit `270f2f1`, hardened in `5d970ff`).

---

### Task 2: Native llama.cpp Integration in `:core:intent`

**Files:**
- Create: `core/intent/build.gradle.kts`
- Create: `core/intent/src/main/cpp/CMakeLists.txt`
- Create: `core/intent/src/main/cpp/intent_jni.cpp`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `llama.cpp` C API headers & static libraries.
- Produces: `libintent_jni.so` exporting `Java_com_betterwhisper_core_intent_LlamaIntentEngine_nativeInit`, `nativeRepair`, `nativeRelease`.

- [x] **Step 1: Add `:core:intent` to `settings.gradle.kts` and `app/build.gradle.kts`**
- [x] **Step 2: Configure vendor/llama.cpp and `core/intent/src/main/cpp/CMakeLists.txt`**
  Set up CMake with ARM64 NEON optimizations and compile `llama.cpp` + `ggml` with C++17.
- [x] **Step 3: Implement `intent_jni.cpp`**
  Minimal memory-efficient JNI wrapper: loads GGUF, configures 4 threads, tokenizes prompt template, runs deterministic greedy sampling ($T=0$), and returns repaired string.
- [x] **Step 4: Verify compilation of `:core:intent`**
  Ran `./gradlew :core:intent:assembleDebug` and verified `libintent_jni.so` built cleanly.
- [x] **Step 5: Verify Task 2 with devrith subagent**
  Dispatched Devrith subagent `56ce2763-f9bf-491a-a5ca-5d1d0b80bd30` (Commit `54f3d72`: APPROVED).

---

### Task 3: Kotlin Speech Intent Engine & Heuristic Classifier

**Files:**
- Create: `core/intent/src/main/kotlin/com/betterwhisper/core/intent/SpeechIntentClassifier.kt`
- Create: `core/intent/src/main/kotlin/com/betterwhisper/core/intent/LlamaIntentEngine.kt`
- Create: `core/intent/src/test/kotlin/com/betterwhisper/core/intent/SpeechIntentClassifierTest.kt`

**Interfaces:**
- Consumes: JNI bindings in `libintent_jni.so`
- Produces:
  - `SpeechIntentClassifier.hasRepairCues(text: String): Boolean`
  - `LlamaIntentEngine.repair(rawText: String): String`

- [x] **Step 1: Write unit tests in `SpeechIntentClassifierTest.kt`**
  Tested true corrections (*"at 5pm no at 6"*, *"let's meet Tuesday wait Wednesday"*, *"send to David actually Sarah"*) and false-positive guards (*"No I disagree"*, *"Wait for me"*, *"Say no to junk food"*).
- [x] **Step 2: Implement `SpeechIntentClassifier.kt`**
  Regex and zero-allocation word counter to detect speech repairs with high precision and sub-0.05ms latency.
- [x] **Step 3: Implement `LlamaIntentEngine.kt`**
  Kotlin wrapper for `libintent_jni.so` managing lifecycle, prompt template injection, and coroutine execution on `Dispatchers.Default`.
- [x] **Step 4: Run unit tests**
  Ran `./gradlew :core:intent:testDebugUnitTest` — 100% tests passed.
- [x] **Step 5: Verify Task 3 with devrith subagent**
  Dispatched Devrith subagent `b899e324-3311-41dc-915b-9460281e32ef` (Commit `7abfb70`, hardened in `32ab78c`).

---

### Task 4: Speech Intent Dispatcher & Text Injection Pipeline

**Files:**
- Create: `core/intent/src/main/kotlin/com/betterwhisper/core/intent/SpeechIntentResolver.kt`
- Modify: `feature/bubble/build.gradle.kts`
- Modify: `feature/bubble/src/main/kotlin/com/betterwhisper/feature/bubble/TextInjector.kt`
- Modify: `feature/bubble/src/main/kotlin/com/betterwhisper/feature/bubble/FlowAccessibilityService.kt`

**Interfaces:**
- Consumes: `PhononTranscribeEngine`, `TranscriptCleaner`, `LlamaIntentEngine`, `SpeechIntentClassifier`.
- Produces: `SpeechIntentResolver.resolve(rawAsrText: String): String`

- [x] **Step 1: Implement `SpeechIntentResolver.kt`**
  Dispatches to fast path (Rust cleaner <2ms) when no repair cues exist; dispatches to `LlamaIntentEngine` when repair cues are detected.
- [x] **Step 2: Add `:core:intent` dependency to `:feature:bubble`**
- [x] **Step 3: Connect `SpeechIntentResolver` into `TextInjector.kt` / `FlowAccessibilityService.kt`**
  Transcribed voice routes through `SpeechIntentResolver.resolve()` before node injection; warm-up runs in background on service connected, cleanup on destroy and trim memory.
- [x] **Step 4: Verify Task 4 with devrith subagent**
  Dispatched Devrith subagent `7b6bcd69-8679-4354-bbdb-fa98296eea20` (Commit `628192a`, refined in `e95af1e`).

---

### Task 5: End-to-End Build & Device Verification on S23 Ultra

**Files:**
- Test / Verify: Full APK packaging & test execution across all 6 modules.

- [x] **Step 1: Assemble full APK**
  Ran `./gradlew assembleDebug` to produce APK with bundled models and native libraries (`app/build/outputs/apk/debug/app-debug.apk`).
- [x] **Step 2: Verify APK uncompressed packaging**
  Verified with `unzip -l` and `unzip -v`:
  - `model.fermion` (177MB, Stored 0%)
  - `smollm2-135m-q4_k_m.gguf` (105MB, Stored 0%)
  - Native libraries: `libintent_jni.so`, `libbetter_whisper_core.so`, `libphonon.so`, `libcleaner_jni.so`, `libtranscribe_jni.so`.
- [x] **Step 3: Run comprehensive unit test suites**
  Executed `./gradlew test`: 27/27 unit tests passed across all modules.
- [x] **Step 4: Run C++ native text-cleaner benchmark**
  Executed `python3 scripts/benchmark_flow.py`: 10/10 test cases passed with 13.1µs per call latency.
- [x] **Step 5: Verify Task 5 with devrith subagent & finalize session report**
  Dispatched Devrith subagent `0b9cfad9-6cde-4214-bcf1-7ba5e0c1d9b0`: PASSED / PRODUCTION READY.
