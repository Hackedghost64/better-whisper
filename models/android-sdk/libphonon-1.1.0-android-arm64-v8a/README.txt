libphonon 1.1.0 -- android-arm64-v8a (Android 8.0 / API 26 and later)
Phonon speech-to-text as a C library: audio in, text out; bionic libc, libm, libdl only; 16 KB page aligned.
  include/phonon.h   the ABI (docs/ABI.md: call sequence, ownership, threads and cpu placement, frame/time conventions)
  lib/               shared libraries: libphonon.so assumes armv8.2-a+dotprod (i8mm picked at run time),
                     libphonon-base.so armv8.0 NEON (pick by getauxval(AT_HWCAP) & HWCAP_ASIMDDP before loading)
  bin/phonon-cli     example (adb shell): phonon-cli transcribe <model_dir> file.wav [--threads N] [--json]
Model files: model.fermion + config.json -- the directory inside phonon-2.5-en.bps.tar.zst / phonon-2.5.bps.tar.zst /
phonon-2.bps.tar.zst (FermionResearch/Phonon-2.5-English, Phonon-2.5, Phonon-2 on Hugging Face).
Link: clang --target=aarch64-linux-android26 app.c -I include -L lib -lphonon -lm   (ship lib/libphonon.so, or libphonon-base.so, in your APK's jniLibs/arm64-v8a)
