/// Voice Activity Detection (VAD) pause-based chunking state machine.
/// Splits continuous microphone audio into dispatchable speech chunks whenever
/// a pause threshold (> 400ms) is reached, enabling background pre-transcription.

#[derive(Debug, Clone)]
pub struct VadConfig {
    pub sample_rate: usize,          // Typically 16000 Hz
    pub frame_size_ms: usize,        // Typically 30 ms (480 samples @ 16kHz)
    pub silence_threshold_ms: usize, // Typically 400 ms
    pub energy_threshold: f32,       // Energy threshold for basic fallback VAD
}

impl Default for VadConfig {
    fn default() -> Self {
        Self {
            sample_rate: 16000,
            frame_size_ms: 30,
            silence_threshold_ms: 400,
            energy_threshold: 0.015,
        }
    }
}

pub struct SpeechChunk {
    pub samples: Vec<f32>,
    pub is_final: bool,
}

pub struct VadChunker {
    config: VadConfig,
    current_speech: Vec<f32>,
    silent_frames_count: usize,
    is_in_speech: bool,
}

impl VadChunker {
    pub fn new(config: VadConfig) -> Self {
        Self {
            config,
            current_speech: Vec::new(),
            silent_frames_count: 0,
            is_in_speech: false,
        }
    }

    /// Process a frame of 16 kHz PCM float samples (e.g. 480 samples = 30 ms).
    /// Returns `Some(SpeechChunk)` if a pause boundary was detected and a chunk is ready to transcribe.
    pub fn process_frame(&mut self, frame: &[f32], is_voice_detected: bool) -> Option<SpeechChunk> {
        let _frame_samples = self.config.sample_rate * self.config.frame_size_ms / 1000;
        let silent_frames_threshold = self.config.silence_threshold_ms / self.config.frame_size_ms;

        // Fallback to RMS energy if external neural VAD flag is not passed
        let is_speech = if is_voice_detected {
            true
        } else {
            Self::calculate_rms(frame) > self.config.energy_threshold
        };

        if is_speech {
            self.is_in_speech = true;
            self.silent_frames_count = 0;
            self.current_speech.extend_from_slice(frame);
            None
        } else if self.is_in_speech {
            self.silent_frames_count += 1;
            self.current_speech.extend_from_slice(frame);

            if self.silent_frames_count >= silent_frames_threshold {
                // Pause detected! Dispatch the speech chunk for pre-transcription.
                let chunk_samples = std::mem::take(&mut self.current_speech);
                self.is_in_speech = false;
                self.silent_frames_count = 0;

                Some(SpeechChunk {
                    samples: chunk_samples,
                    is_final: false,
                })
            } else {
                None
            }
        } else {
            None
        }
    }

    /// Flushes any remaining audio in the buffer when the user releases the mic button.
    pub fn flush(&mut self) -> Option<SpeechChunk> {
        if self.current_speech.is_empty() {
            None
        } else {
            let chunk_samples = std::mem::take(&mut self.current_speech);
            self.is_in_speech = false;
            self.silent_frames_count = 0;
            Some(SpeechChunk {
                samples: chunk_samples,
                is_final: true,
            })
        }
    }

    fn calculate_rms(frame: &[f32]) -> f32 {
        if frame.is_empty() {
            return 0.0;
        }
        let sum_sq: f32 = frame.iter().map(|&x| x * x).sum();
        (sum_sq / frame.len() as f32).sqrt()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vad_pause_chunking() {
        let mut chunker = VadChunker::new(VadConfig::default());
        let frame_size = 480; // 30ms @ 16kHz

        // 1. Send speech frames
        let speech_frame = vec![0.08f32; frame_size];
        for _ in 0..10 {
            let res = chunker.process_frame(&speech_frame, true);
            assert!(res.is_none());
        }

        // 2. Send silence frames to trigger pause (400ms / 30ms ~ 14 frames)
        let silence_frame = vec![0.001f32; frame_size];
        let mut dispatched_chunk = None;
        for _ in 0..15 {
            if let Some(chunk) = chunker.process_frame(&silence_frame, false) {
                dispatched_chunk = Some(chunk);
                break;
            }
        }

        assert!(dispatched_chunk.is_some(), "Chunk should be dispatched after silence threshold");
    }
}
