/* phonon.h -- libphonon, the Phonon-2 speech-to-text C library (ABI 1).
 *
 * Audio in, text out, no PyTorch: PCM at any sample rate goes in, UTF-8 text with segment, word and token times comes
 * out.  One `phonon_model` holds the weights and its own thread pool; `phonon_transcribe` decodes a whole buffer (long
 * audio is cut into 25-35 s windows at pauses), `phonon_stream_*` runs the live session (rolling partial hypotheses,
 * a final per pause).  Dependencies: libc, libm, pthread.
 *
 * Threading: a model (and every stream of it) is used by ONE thread at a time -- the library serialises calls on the
 * same model with a mutex, so concurrent calls are safe but queue.  Different models run concurrently in different
 * threads.  Nothing is process-global except read-only CPU-detection facts.
 *
 * Errors: functions that return int return PHONON_OK (0) or a negative PHONON_E_* code; functions that return a pointer
 * return NULL on failure.  `phonon_last_error()` names the failure (thread-local, never NULL).  The library never calls
 * exit() and writes nothing to stderr unless `phonon_options.verbose` is set.
 *
 * Memory: every pointer inside a `phonon_result` is owned by the result and freed by `phonon_result_free`; strings
 * returned by `phonon_describe`, `phonon_stream_partial`, `phonon_stream_transcript` and `phonon_last_error` are owned
 * by the library and valid until the next call that can change them on the same object (or thread, for last_error).
 *
 * Time conventions: seconds from the start of the buffer (transcribe) or of the stream (stream events).  The decoder
 * works on 80 ms encoder frames (8 x 160 samples at 16 kHz): a token's start is `frame * 0.08`, its end
 * `(frame + duration) * 0.08`, clamped to the audio it came from; the mel front end sees 1 + n // 160 frames of a
 * window of n samples (the pip CPU engine's convention; see docs/ABI.md).  Full contract: docs/ABI.md.
 */
#ifndef PHONON_H
#define PHONON_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) && defined(PHONON_SHARED)
#  ifdef PHONON_BUILD
#    define PHONON_API __declspec(dllexport)
#  else
#    define PHONON_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) && defined(PHONON_BUILD)
#  define PHONON_API __attribute__((visibility("default")))
#else
#  define PHONON_API
#endif

#define PHONON_ABI_VERSION 1

typedef struct phonon_model  phonon_model;      /* weights + thread pool + decode scratch */
typedef struct phonon_stream phonon_stream;     /* one live session on a model */

/* ------------------------------------------------------------------ constants */
enum phonon_error {
    PHONON_OK          =  0,
    PHONON_E_ARG       = -1,   /* a NULL handle, a bad sample format, an unsupported rate */
    PHONON_E_NOMEM     = -2,   /* allocation failed (the handle is left usable) */
    PHONON_E_IO        = -3,   /* model.fermion / config.json missing or unreadable */
    PHONON_E_FORMAT    = -4,   /* the container or config is not the Phonon-2 shape */
    PHONON_E_CPU       = -5,   /* this binary assumes CPU features the processor lacks (or a forced tier it cannot run) */
    PHONON_E_INTERNAL  = -6    /* a kernel refused (reported through phonon_last_error) */
};

enum phonon_pcm_format { PHONON_PCM_F32 = 0,    /* float samples in [-1, 1] */
                         PHONON_PCM_S16 = 1 };  /* int16 samples, scaled by 1/32768 */

/* Kernel tier to force (phonon_options.tier); 0 = the processor's own, highest tier.  Forcing a tier the processor
 * cannot run is refused with PHONON_E_CPU.  The ids are the kernel ids phonon_describe reports. */
enum phonon_tier { PHONON_TIER_AUTO = 0, PHONON_TIER_SCALAR = 1, PHONON_TIER_NEON_DOTPROD = 2, PHONON_TIER_AVX2 = 3,
                   PHONON_TIER_AVX512_VNNI = 4, PHONON_TIER_NEON_I8MM = 5, PHONON_TIER_AVX512BW = 6, PHONON_TIER_AMX = 7,
                   PHONON_TIER_NEON = 8, PHONON_TIER_SSE41 = 9 };

/* Mel frame convention (phonon_options.frontend).  P2CPU = 1 + n // 160 frames, feature statistics over all of them (the
 * pip CPU engine, the identity target).  HF = n // 160 frames (the HF ParakeetFeatureExtractor / Core ML convention). */
enum phonon_frontend { PHONON_FRONTEND_P2CPU = 0, PHONON_FRONTEND_HF = 1 };

typedef struct phonon_options {
    int n_threads;        /* 0 = automatic (servers/desktops: physical big cores, capped at 16; mixed-core phones: the cores at >= half
                             the fastest core's capacity, capped at 4) */
    int tier;             /* PHONON_TIER_AUTO or a lower tier to force */
    int frontend;         /* PHONON_FRONTEND_P2CPU (default) or HF */
    int verbose;          /* 0 = silent; 1 = one-line diagnostics on stderr */
    int cpu_policy;       /* PHONON_CPU_AUTO (0): on processors with cores of different speeds (phones: big.LITTLE / DynamIQ),
                             the library's threads -- the pool's and, for the duration of each decode, the calling thread -- are
                             kept on the n fastest cores; PHONON_CPU_OS (1): leave placement to the OS.  Env PHONON_CPU_POLICY=os
                             overrides AUTO.  Transcripts do not depend on it. */
    int reserved[11];     /* must be zero */
} phonon_options;
#define PHONON_OPTIONS_INIT { 0, 0, 0, 0, 0, { 0 } }
enum phonon_cpu_policy { PHONON_CPU_AUTO = 0, PHONON_CPU_OS = 1 };

/* ------------------------------------------------------------------ results */
typedef struct phonon_span {
    const char *text;     /* UTF-8; a word (words), a sub-word piece (tokens, leading space = word start) or a segment / window text */
    double start, end;    /* seconds */
} phonon_span;

typedef struct phonon_result {
    const char   *text;          /* the transcript (windows joined with single spaces); "" when nothing was decoded */
    const phonon_span *segments; /* sentence/pause-sized cues built from the word times; their texts joined with " " == text */
    size_t        n_segments;
    const phonon_span *words;    /* word times; texts joined with " " == text */
    size_t        n_words;
    const phonon_span *tokens;   /* decoder pieces with times */
    size_t        n_tokens;
    const phonon_span *windows;  /* the decoded windows (one for audio up to 35 s) */
    size_t        n_windows;
    double        audio_s;       /* seconds of input (after resampling to 16 kHz) */
    double        decode_s;      /* wall seconds spent decoding (front end + encoder + decoder; not resampling) */
    int           n_generated;   /* decoder tokens emitted, every window summed */
} phonon_result;

/* ------------------------------------------------------------------ library */
PHONON_API int         phonon_abi_version(void);            /* PHONON_ABI_VERSION */
PHONON_API const char *phonon_version(void);                /* e.g. "0.2.12" (the kernel/model line this library decodes) */
PHONON_API const char *phonon_last_error(void);             /* thread-local; "" when the last call on this thread succeeded */

/* Load `model_dir/model.fermion` + `model_dir/config.json` (the files the pip package downloads).  `opt` may be NULL
 * (defaults).  Returns NULL with phonon_last_error() set.  Load takes ~0.3-1 s; resident memory ~0.7-0.9 GB. */
PHONON_API phonon_model *phonon_load(const char *model_dir, const phonon_options *opt);
/* JSON (owned by the model): {"abi":1,"version":...,"kernel_id":4,"kernel":"avx512-vnni","threads":8,"frontend":"p2cpu",
 * "host_features":"0x...","build_features":"0x...","encoder_modules":264,"load_s":0.41,"model_dir":"..."} */
PHONON_API const char *phonon_describe(const phonon_model *m);
PHONON_API void        phonon_free(phonon_model *m);          /* streams of this model must be freed first */

/* Decode one buffer.  `pcm` holds `n_samples` samples per channel (interleaved when `channels` > 1; channels are averaged),
 * `sample_rate` any rate (resampled to 16 kHz with the polyphase FIR the pip package uses).  Audio up to 35 s is one
 * decode; longer audio is cut into 25-35 s windows at pauses (segment.plan).  A silent buffer gives an empty result with
 * PHONON_OK.  `*out` is allocated by the library; free it with phonon_result_free. */
PHONON_API int  phonon_transcribe(phonon_model *m, const void *pcm, size_t n_samples, int format, int sample_rate,
                                  int channels, phonon_result **out);
PHONON_API void phonon_result_free(phonon_result *r);

/* ------------------------------------------------------------------ live session (fermion listen / live.py) */
enum phonon_event_kind { PHONON_EVENT_PARTIAL = 1, PHONON_EVENT_FINAL = 2 };
typedef struct phonon_event {
    int         kind;        /* PARTIAL: the current whole hypothesis of the in-flight segment (replaces the last one);
                                FINAL: one finalised segment */
    const char *text;        /* non-empty UTF-8 */
    double      start, end;  /* stream seconds of the segment the text describes (FINAL: the finalised segment;
                                PARTIAL: the in-flight segment so far) */
} phonon_event;

/* Open a session: first partial after 0.35 s of audio in a segment, then one per 0.5 s; a segment finalises after 0.7 s
 * under the energy gate max(0.004, 0.18 x the segment's peak RMS) or at 30 s.  Feed 16 kHz mono blocks of any size
 * (50 ms is the pip cadence; other rates/channels are resampled first).  Each feed returns the events it produced
 * (`*events` valid until the next call on the stream).  `phonon_stream_close` finalises what is in flight and returns
 * its events; after close only free is valid. */
PHONON_API phonon_stream *phonon_stream_open(phonon_model *m);
PHONON_API int   phonon_stream_feed(phonon_stream *s, const void *pcm, size_t n_samples, int format, int sample_rate,
                                    int channels, const phonon_event **events, int *n_events);
PHONON_API const char *phonon_stream_partial(const phonon_stream *s);     /* latest partial text ("" if none) */
PHONON_API int   phonon_stream_close(phonon_stream *s, const phonon_event **events, int *n_events);
PHONON_API const char *phonon_stream_transcript(const phonon_stream *s);  /* finals joined with single spaces */
PHONON_API void  phonon_stream_free(phonon_stream *s);

#ifdef __cplusplus
}
#endif
#endif /* PHONON_H */
