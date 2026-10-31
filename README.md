# Better Whisper

An offline, on-device dictation app for Android: a floating "Flow" bubble that
transcribes speech with **Phonon-2** and injects clean text into any app.

> **Android only.** The iOS/CoreML target was never buildable (the keyboard
> extension was a stub) and now lives in [`archive/ios`](archive/ios) for
> reference. No iOS build is supported.

---

## Why Phonon-2

| Feature | **Phonon-2** | Whisper large-v3-turbo | Whisper base / small |
| :--- | :--- | :--- | :--- |
| Model size | **~164 MB** | ~1.6 GB | ~150–460 MB |
| Architecture | FastConformer-TDT (non-autoregressive) | Encoder–decoder | Encoder–decoder |
| Repetition loops | **Effectively none** (TDT) | Common on silence/music | Common |
| Runtime | Android NDK (arm64-v8a) | whisper.cpp / GGML | whisper.cpp |
| Cost / privacy | **$0, 100% on-device** | On-device, heavy battery | On-device |

---

## Architecture

```text
better-whisper/
├── app/                          # Android shell: Compose setup screen, permissions, entry point
│   └── src/main/kotlin/com/betterwhisper/
│       ├── MainActivity.kt
│       ├── ui/Theme.kt
│       ├── ui/SetupScreen.kt
│       ├── ui/PermissionItem.kt
│       └── util/Permissions.kt
│
├── core/                         # Reusable, UI-free engine modules
│   ├── audio/                    # Capture: 16 kHz AudioRecord + RMS level for the visualizer
│   ├── transcription/            # Speech → text: JNI bridge to libphonon.so (Phonon-2)
│   └── text-cleaning/            # Text → clean text: Kotlin reference + JNI bridge
│       ├── src/main/kotlin/com/betterwhisper/core/cleaner/
│       │   ├── FlowTextCleaner.kt    # reference rules (unit tested)
│       │   └── TranscriptCleaner.kt
│       └── engine/                   # Rust C-ABI fast path (release build)
│           └── src/{cleaner,dictionary,vad,ffi}.rs
│
├── feature/                      # User-facing features built on the core modules
│   └── bubble/                   # Floating bubble + accessibility service
│       └── src/main/kotlin/com/betterwhisper/feature/bubble/
│           ├── FlowBubbleOverlay.kt      # window, mode state machine, snap animation
│           ├── BubblePlacement.kt        # pure geometry (unit tested)
│           ├── BubbleDragController.kt   # latched touch state machine (unit tested)
│           ├── FlowAccessibilityService.kt
│           ├── TextInjector.kt           # field resolution + safe insertion
│           └── DictationEventBus.kt
│
├── models/                       # download/setup script + local assets
└── scripts/benchmark_flow.py     # functional + latency suite for the Rust engine
```

### Modules

| Module | What it does | Depends on |
| :--- | :--- | :--- |
| `:app` | UI shell, setup/permission flow, app entry point | `:feature:bubble`, all `:core:*` |
| `:core:audio` | Records 16 kHz mono PCM and exposes an RMS level for the visualizer | — |
| `:core:transcription` | Wraps Phonon-2 in `libphonon.so` behind a Kotlin API | — |
| `:core:text-cleaning` | Removes fillers/stutters, fixes casing; Kotlin + Rust parity | — |
| `:feature:bubble` | Drives capture → transcribe → clean → inject from a floating bubble | all `:core:*` |

### Native libraries

Both `.so` files are gitignored; `models/download_models.sh` builds/copies them
to the right module:

| Library | Destination |
| :--- | :--- |
| `libphonon.so` | `core/transcription/src/main/jniLibs/arm64-v8a/` |
| `libbetter_whisper_core.so` | `core/text-cleaning/src/main/jniLibs/arm64-v8a/` |

---

## Text cleaning

The cleaner runs twice-over: `FlowTextCleaner.kt` is the readable reference and
`core/text-cleaning/engine/src/cleaner.rs` is the fast path; both implement the
same rules.

- **Disfluencies** (`um`, `uh`, `er`, `ah`, `hmm`) are always removed.
- **Empty discourse markers** (`basically`, `literally`) are removed.
- **Ambiguous markers are contextual, not blacklisted:**
  - `like` is removed when it is flanked by commas or used as a filler
    (`"it's like really cold"` → `"it's really cold"`), and kept when it is a
    verb or comparison (`"I like pizza"`, `"looks like rain"`,
    `"would like to"`).
  - `you know`, `I mean`, `kind of`, `sort of` are removed when they do not
    carry meaning (`"what kind of car"` is preserved).
  - `actually` is **not** treated as a self-correction; only `scratch that`,
    `never mind`, `delete that`, `no wait`, `sorry I meant`, and `or rather`
    are.
- **Stutters** are de-duplicated across 1-, 2-, and 3-word repeats.
- **Sentence casing** is restored after a removed filler.
- **Cursor context** decides capitalization and the leading space.

Set `aggressiveDiscourse = false` to only remove comma-delimited markers.

---

## Bubble reliability

The old bubble could freeze mid-screen and stop responding during fast typing
or app switches. Fixes:

- `BubbleDragController` **latches** the drag flag, tracks a single pointer, and
  treats `ACTION_CANCEL` / pointer handoff as a cancel (not a tap).
- `FlowBubbleOverlay` has an explicit `Mode` state machine; the service will not
  remove the window while a gesture, snap, recording, or decode is in progress.
- Placement math lives in `BubblePlacement` and accounts for `Gravity.BOTTOM`
  (`y` grows upward), the status bar, the navigation bar, and the current IME
  height.
- Layout failures are logged and mark the window detached so the next `show()`
  re-attaches instead of animating a stale window token.
- Injection is refused if the user switched apps while recording; the transcript
  is copied to the clipboard instead of being typed into the wrong app.
- Password fields are never dictated into.

---

## Building

### 1. Fetch assets

```bash
bash models/download_models.sh   # weights + libphonon.so + Rust .so
```

### 2. Verify the Rust cleaner

```bash
cd core/text-cleaning/engine && cargo test && cargo build --release
cd - && python3 scripts/benchmark_flow.py
```

The benchmark runs 10 functional cases and a 10,000-iteration latency loop.
On a desktop CPU it is currently **~15 µs per call (~0.02 ms)**; on-device
numbers are reported by `adb logcat` (`Phonon-2 decoded in …ms`).

### 3. Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Put the model on the device

The engine only reads app-private storage, so copy the weights into the app's
internal files dir (no root required):

```bash
adb push models/weights/phonon2/. /data/local/tmp/phonon2/
adb shell run-as com.betterwhisper mkdir -p files/phonon2
adb shell run-as com.betterwhisper cp -r /data/local/tmp/phonon2/. files/phonon2/
```

### 5. Grant permissions

Open the app and complete all four items:

1. **Display over other apps** — the overlay window.
2. **Accessibility service** — focus detection and text injection.
3. **Microphone** — 16 kHz capture.
4. **Unrestricted battery** — keeps the service alive.

Then tap the bubble in any text field. `✓` transcribes and inserts, `✕`
cancels.

---

## Tests

```bash
cd core/text-cleaning/engine && cargo test       # Rust rules + FFI
./gradlew :core:text-cleaning:testDebugUnitTest   # Kotlin cleaner parity
./gradlew :feature:bubble:testDebugUnitTest       # bubble placement maths
./gradlew :app:assembleDebug                      # full Android build
```

---

## Requirements

- Android 8.0+ (`minSdk 26`), `arm64-v8a`
- Android SDK 35 and NDK (for the JNI modules)
- Rust toolchain with the `aarch64-linux-android` target
- `zstd` (for extracting the model archive)

## License

Phonon-2 weights are licensed CC-BY-4.0 by Fermion Research. See
`models/android-sdk/libphonon-1.1.0-android-arm64-v8a/LICENSE`.
