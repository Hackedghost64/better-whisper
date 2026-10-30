import Foundation
import PhononCoreML

// C ABI from better_whisper_core (Rust static library)
@_silgen_name("better_whisper_clean_text")
func better_whisper_clean_text(
    _ input: UnsafePointer<CChar>?,
    _ contextBeforeCursor: UnsafePointer<CChar>?,
    _ removeFillers: Bool,
    _ deduplicateStutters: Bool,
    _ handleSelfCorrections: Bool
) -> UnsafeMutablePointer<CChar>?

@_silgen_name("better_whisper_free_string")
func better_whisper_free_string(_ ptr: UnsafeMutablePointer<CChar>?)

public class PhononBridge {
    public static let shared = PhononBridge()
    private var transcriber: Transcriber?

    private init() {}

    /// Initializes Phonon-2 CoreML bundle from the shared App Group directory.
    public func initialize(modelURL: URL) throws {
        self.transcriber = try Transcriber(bundle: modelURL)
    }

    /// Transcribes an audio file or PCM buffer and runs the Wispr Flow post-processing cleaner.
    public func transcribeAndClean(audioURL: URL, contextBeforeCursor: String? = nil) throws -> String {
        guard let transcriber = self.transcriber else {
            throw NSError(domain: "BetterWhisper", code: 1, userInfo: [NSLocalizedDescriptionKey: "Transcriber not initialized"])
        }

        // 1. CoreML ANE Transcription via Phonon-2
        let result = try transcriber.transcribe(url: audioURL)
        let rawText = result.text

        // 2. High-speed Wispr Flow cleaner pass (<2 ms)
        let cleaned = rawText.withCString { rawPtr in
            if let context = contextBeforeCursor {
                return context.withCString { contextPtr in
                    if let outPtr = better_whisper_clean_text(rawPtr, contextPtr, true, true, true) {
                        defer { better_whisper_free_string(outPtr) }
                        return String(cString: outPtr)
                    }
                    return rawText
                }
            } else {
                if let outPtr = better_whisper_clean_text(rawPtr, nil, true, true, true) {
                    defer { better_whisper_free_string(outPtr) }
                    return String(cString: outPtr)
                }
                return rawText
            }
        }

        return cleaned
    }
}
