import 'dart:ffi';
import 'package:ffi/ffi.dart';

// Typedefs for C ABI
typedef CleanTextNative = Pointer<Utf8> Function(
  Pointer<Utf8> input,
  Pointer<Utf8> contextBeforeCursor,
  Bool removeFillers,
  Bool deduplicateStutters,
  Bool handleSelfCorrections,
);

typedef CleanTextDart = Pointer<Utf8> Function(
  Pointer<Utf8> input,
  Pointer<Utf8> contextBeforeCursor,
  bool removeFillers,
  bool deduplicateStutters,
  bool handleSelfCorrections,
);

typedef FreeStringNative = Void Function(Pointer<Utf8> ptr);
typedef FreeStringDart = void Function(Pointer<Utf8> ptr);

class BetterWhisperFlowCore {
  static final BetterWhisperFlowCore instance = BetterWhisperFlowCore._internal();
  late DynamicLibrary _dylib;
  late CleanTextDart _cleanText;
  late FreeStringDart _freeString;
  bool _isLoaded = false;

  BetterWhisperFlowCore._internal() {
    _init();
  }

  void _init() {
    try {
      _dylib = DynamicLibrary.open('libbetter_whisper_core.so');
      _cleanText = _dylib.lookupFunction<CleanTextNative, CleanTextDart>('better_whisper_clean_text');
      _freeString = _dylib.lookupFunction<FreeStringNative, FreeStringDart>('better_whisper_free_string');
      _isLoaded = true;
    } catch (e) {
      _isLoaded = false;
    }
  }

  bool get isLoaded => _isLoaded;

  /// Cleans raw speech transcript using the ultra-fast Rust Wispr Flow engine.
  String cleanText(
    String rawText, {
    String? contextBeforeCursor,
    bool removeFillers = true,
    bool deduplicateStutters = true,
    bool handleSelfCorrections = true,
  }) {
    if (!_isLoaded || rawText.trim().isEmpty) {
      return rawText;
    }

    final inputPtr = rawText.toNativeUtf8();
    final contextPtr = contextBeforeCursor != null ? contextBeforeCursor.toNativeUtf8() : nullptr;

    try {
      final resultPtr = _cleanText(
        inputPtr,
        contextPtr,
        removeFillers,
        deduplicateStutters,
        handleSelfCorrections,
      );

      if (resultPtr == nullptr) {
        return rawText;
      }

      final resultString = resultPtr.toDartString();
      _freeString(resultPtr);
      return resultString;
    } finally {
      malloc.free(inputPtr);
      if (contextPtr != nullptr) {
        malloc.free(contextPtr);
      }
    }
  }
}
