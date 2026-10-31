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

- [ ] **Step 1: Download SmolLM2-135M-Instruct Q4_K_M GGUF**
  Fetch `SmolLM2-135M-Instruct-Q4_K_M.gguf` (~100MB) to `models/weights/intent/smollm2-135m-q4_k_m.gguf`.
- [ ] **Step 2: Configure APK asset packaging in `app/build.gradle.kts`**
  Add `sourceSets { getByName("main") { assets.srcDirs("src/main/assets", "../../models/assets") } }` or symlink/copy model weights into `app/src/main/assets/models/`.
- [ ] **Step 3: Implement `AssetModelExtractor.kt`**
  Implement CRC/size verification so extraction only runs once on cold install and unpacks asynchronously to `context.filesDir/models/`.
- [ ] **Step 4: Update `PhononTranscribeEngine.kt`**
  Ensure it automatically loads from `AssetModelExtractor.getPhononDir(context)`.
- [ ] **Step 5: Verify Task 1 with devrith subagent**
  Dispatch subagent with Devrith to view changes, verify AST, and report diff.

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

- [ ] **Step 1: Add `:core:intent` to `settings.gradle.kts` and `app/build.gradle.kts`**
- [ ] **Step 2: Configure vendor/llama.cpp and `core/intent/src/main/cpp/CMakeLists.txt`**
  Set up CMake with ARM64 NEON optimizations and compile `llama.cpp` + `ggml` with C++17.
- [ ] **Step 3: Implement `intent_jni.cpp`**
  Minimal memory-efficient JNI wrapper: loads GGUF, configures 4 threads, tokenizes prompt template, runs deterministic greedy sampling ($T=0$), and returns repaired string.
- [ ] **Step 4: Verify compilation of `:core:intent`**
  Run `./gradlew :core:intent:assembleDebug` and ensure `libintent_jni.so` and `libllama.so` build cleanly.
- [ ] **Step 5: Verify Task 2 with devrith subagent**
  Dispatch subagent with Devrith to view native CMake changes, check compiler flags, and report diff.

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

- [ ] **Step 1: Write unit tests in `SpeechIntentClassifierTest.kt`**
  Test true corrections (*"at 5pm no at 6"*, *"let's meet Tuesday wait Wednesday"*, *"send to David actually Sarah"*) and false-positive guards (*"No I disagree"*, *"Wait for me"*).
- [ ] **Step 2: Implement `SpeechIntentClassifier.kt`**
  Regex and clause-boundary heuristics to detect speech repairs with high precision and zero latency.
- [ ] **Step 3: Implement `LlamaIntentEngine.kt`**
  Kotlin wrapper for `libintent_jni.so` managing lifecycle, prompt template injection, and coroutine execution on `Dispatchers.Default`.
- [ ] **Step 4: Run unit tests**
  Run `./gradlew :core:intent:testDebugUnitTest` to verify test suite passes.
- [ ] **Step 5: Verify Task 3 with devrith subagent**
  Dispatch subagent with Devrith to check Kotlin AST, lexical scope binding, and report diff.

---

### Task 4: Speech Intent Dispatcher & Text Injection Pipeline

**Files:**
- Create: `core/intent/src/main/kotlin/com/betterwhisper/core/intent/SpeechIntentResolver.kt`
- Modify: `feature/bubble/build.gradle.kts`
- Modify: `feature/bubble/src/main/kotlin/com/betterwhisper/core/bubble/FlowBubbleOverlay.kt`
- Modify: `feature/bubble/src/main/kotlin/com/betterwhisper/core/bubble/FlowAccessibilityService.kt`

**Interfaces:**
- Consumes: `PhononTranscribeEngine`, `TranscriptCleaner`, `LlamaIntentEngine`, `SpeechIntentClassifier`.
- Produces: `SpeechIntentResolver.resolve(rawAsrText: String): String`

- [ ] **Step 1: Implement `SpeechIntentResolver.kt`**
  Dispatches to fast path (Rust cleaner <2ms) when no repair cues exist; dispatches to `LlamaIntentEngine` when repair cues are detected.
- [ ] **Step 2: Add `:core:intent` dependency to `:feature:bubble`**
- [ ] **Step 3: Connect `SpeechIntentResolver` into `FlowBubbleOverlay.kt` / `FlowAccessibilityService.kt`**
  Ensure transcribed voice flows through `SpeechIntentResolver` before calling `injectTextIntoFocusedField`.
- [ ] **Step 4: Verify Task 4 with devrith subagent**
  Dispatch subagent with Devrith to inspect cross-module bindings, verify absence of circular dependencies, and report diff.

---

### Task 5: End-to-End Build & Device Verification on S23 Ultra

**Files:**
- Test / Verify on Target Device: Samsung Galaxy S23 Ultra (`RZCW21BJDWX`)

- [ ] **Step 1: Assemble full APK**
  Run `./gradlew assembleDebug` to produce APK with bundled models and native libraries.
- [ ] **Step 2: Install to device via ADB**
  `adb -s RZCW21BJDWX install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 3: Verify first-launch model unpacking and memory footprint**
  Check `adb logcat` for `AssetModelExtractor` output, verifying Phonon-2 and SmolLM2 weights are mirrored properly.
- [ ] **Step 4: Run live speech repair verification**
  Inject or dictate test utterance *"how about at 5pm no at 6"* and verify the output is cleanly resolved to *"how about at 6pm"*.
- [ ] **Step 5: Verify Task 5 with devrith subagent & finalize session report**
  Run `arch-sidecar report` and dispatch the final verification subagent.
