# libphonon ABI 1

`phonon.h` is the whole public surface: 17 functions, 4 structs, 5 enums. C99, no dependencies beyond libc, libm and
pthread. Every exported symbol starts with `phonon_`; everything else in the library has hidden visibility.

## Call sequence

```c
#include <phonon.h>

phonon_options opt = PHONON_OPTIONS_INIT;        /* all zero = defaults: auto threads, natural tier, P2CPU front end, silent */
opt.n_threads = 8;
phonon_model *m = phonon_load("/path/to/model_dir", &opt);   /* model.fermion + config.json inside */
if (!m) { fprintf(stderr, "%s\n", phonon_last_error()); return 1; }
puts(phonon_describe(m));                        /* JSON: kernel tier, threads, features, load time */

phonon_result *r;
int rc = phonon_transcribe(m, pcm, n_samples, PHONON_PCM_S16, 44100, 2, &r);   /* any rate, channels averaged */
if (rc == PHONON_OK) {
    puts(r->text);
    for (size_t i = 0; i < r->n_words; ++i) printf("%.3f-%.3f %s\n", r->words[i].start, r->words[i].end, r->words[i].text);
    phonon_result_free(r);
}
phonon_free(m);
```

Live session (the `fermion listen` loop):

```c
phonon_stream *s = phonon_stream_open(m);
const phonon_event *ev; int n;
while (have_audio) {
    phonon_stream_feed(s, block, 800, PHONON_PCM_F32, 16000, 1, &ev, &n);     /* 50 ms blocks; any size works */
    for (int i = 0; i < n; ++i) printf("%s %s\n", ev[i].kind == PHONON_EVENT_FINAL ? "final" : "partial", ev[i].text);
}
phonon_stream_close(s, &ev, &n);                 /* finalises what is in flight; its events are in ev */
puts(phonon_stream_transcript(s));               /* finals joined with single spaces */
phonon_stream_free(s);                            /* before phonon_free(m) */
```

## Memory ownership

| object | owner | lifetime |
|---|---|---|
| `phonon_model` | caller, via `phonon_free` | streams of the model must be freed first |
| `phonon_describe()` string | the model | until `phonon_free` |
| `phonon_result` and everything it points to | caller, via `phonon_result_free` | independent of the model |
| `phonon_event` array and texts from `feed`/`close` | the stream | until the next call on that stream (`close` events until `free`) |
| `phonon_stream_partial()` / `phonon_stream_transcript()` | the stream | until the next call on that stream |
| `phonon_last_error()` | the library, thread-local | until the next library call on the same thread |
| `pcm` buffers passed in | caller | only during the call (the library copies / converts) |

The library never calls `exit()` or `abort()`; allocation failures return `PHONON_E_NOMEM` and leave the handle usable.
It writes nothing to stderr unless `phonon_options.verbose` is set.

## Threads

- `phonon_load` creates the model's own thread pool (`n_threads` workers, including the caller's thread); `phonon_free` joins it.
  Nothing is process-global except read-only CPU-feature detection and constant tables, so any number of models can exist.
  The pool runs every stage of a call (front end, encoder, projector, decoder), so a model's work occupies `n_threads` cores.
- One model is used by one thread at a time: a mutex inside the model serialises `phonon_transcribe` and every stream call on
  that model. Calling from several threads is safe (calls queue); different models run concurrently.
- `n_threads` 0 = the pip engine's heuristic: physical big cores (SMT siblings excluded on 6+ core parts, AMD and AMX processors;
  macOS: the performance cores), capped at 16. On mixed-core processors (phones; every cpu reports a `cpu_capacity` and they
  differ) it is the number of cores with at least half the fastest core's capacity, capped at 4 (Pixel 10 Pro XL: 4).
- `cpu_policy` (1.1.0; was `reserved[0]`, so 0 keeps old callers valid). `PHONON_CPU_AUTO` (0): on a mixed-core processor the
  model's `n_threads` threads are kept on its `n_threads` fastest cores -- the pool's workers from `phonon_load` on, and the
  calling thread for the duration of each decode (its previous affinity is restored before the call returns). A thread that the
  OS places on a little core runs the encoder 4-6x slower and holds up the whole pool (Pixel 10 Pro XL, one thread per clip:
  382 ms on the X4, 579 ms on an A725, 2,209 ms on an A520). `PHONON_CPU_OS` (1), or the environment variable
  `PHONON_CPU_POLICY=os`: never touch affinity. Placement is best effort (a cpu outside the caller's allowed set is skipped by the
  OS) and has no effect on servers and desktops whose cores are all alike. `phonon_describe` reports the cpus in use (`"cpus"`,
  `"os"` when the OS places the threads). Transcripts are identical under either policy.

## Errors

`PHONON_OK` 0; `PHONON_E_ARG` -1 (NULL handle, unknown format, non-positive rate/channels, bad option); `PHONON_E_NOMEM` -2;
`PHONON_E_IO` -3 (files missing/unreadable); `PHONON_E_FORMAT` -4 (not a Phonon-2 container/config); `PHONON_E_CPU` -5 (the
binary assumes ISA features this processor lacks, or `tier` forces a kernel it cannot run); `PHONON_E_INTERNAL` -6 (a kernel
refused; the text says which). `phonon_last_error()` is `""` after a successful call on that thread.

## Audio

Input: `PHONON_PCM_F32` (float in [-1, 1]) or `PHONON_PCM_S16` (int16, scaled by 1/32768), `n_samples` frames of `channels`
interleaved samples at `sample_rate`. Channels are averaged (as the pip `read_audio` does); a rate other than 16000 is
resampled with the polyphase FIR of `scipy.signal.resample_poly(up, down)` (Kaiser 5.0 window, half length 10·max(up, down)),
computed in double and rounded to float. 16 kHz mono float input is used as is.

Long audio: a buffer of at most 35 s is one decode. Longer audio is cut into 25-35 s windows at the longest pause inside the
band (50 ms RMS blocks, gate max(0.004, 0.18·peak), ties toward 30 s, 30 s when the band has no pause), exactly `segment.plan`
of the pip package; each window is decoded on its own and the window texts are joined with single spaces. A silent window
(peak < 1e-4) is skipped.

## Frames and times

- Mel front end: pre-emphasis 0.97, 512-point STFT, hop 160, Hann 400 centred in 512, 128 slaney mel bands, `log(x + 2^-24)`,
  per-band mean / unbiased-std normalisation. **Frame count = 1 + n // 160** for a window of n samples, statistics over all of
  those frames (`PHONON_FRONTEND_P2CPU`, the pip CPU engine's convention and the identity target of this library). The
  HuggingFace / Core ML convention (n // 160 frames, `PHONON_FRONTEND_HF`) is available as an option; it is not the default and
  was not accuracy-gated in this release (SCOPE §4: a separate change that would align CPU, Core ML and HF).
- The encoder subsamples by 8: one output frame = 8 hops = 1280 samples = 80 ms. Encoder frames for T mel frames:
  `((T-1)/2+1)` applied three times.
- The decoder emits a token at an encoder frame `f` with a predicted duration `d` in frames (0..4). Token start =
  `f * 8 / 16000 * 160` s (that expression order, so the millisecond printed matches the pip engines), token end =
  start + `d * 0.08`, clamped to the window length; a word's end is never earlier than its start.
- Words: tokens are joined into words by the SentencePiece marker (a piece that starts with a space starts a word, except a
  punctuation-only piece, which attaches to the word before it); word `start` = first token start, `end` = last token end,
  shifted by the window offset and rounded to the millisecond. Word texts joined with single spaces equal `text`.
- Segments: the words of each window grouped into sentence/pause-sized cues (sentence end, pauses >= 0.8 s, at most 7 s and two
  42-character lines, merges below 1 s), never across a window boundary, made monotone; their texts joined with single spaces
  equal `text`. When a window's words do not join back to its text, that window is one segment.
- Windows: `(start, end, text)` per decoded window; `start/end` rounded to the millisecond.
- Times in a `phonon_result` are seconds from the start of the buffer; in a `phonon_event`, seconds from the start of the stream.
  A FINAL event spans the finalised segment (first sample to one past its last, including the trailing quiet that closed it);
  a PARTIAL spans the in-flight segment so far.

## Live session

Blocks are appended whole to the in-flight segment. The first partial decode happens once >= 0.35 s of audio is in the segment
and something above the gate has been heard; the next partial is scheduled 0.5 s of audio after the buffer position at which the
previous decode returned (self-clocking). The segment is finalised after >= 0.7 s of audio under the gate (`rms <= gate`, gate =
max(0.004, 0.18·peak RMS of the segment so far)) once voiced, or at 30 s; 30 s of nothing but room tone keeps the last second
and drops the rest. Every partial and final re-decodes the whole segment with `phonon_transcribe`'s single-window decode, so a
`--wav` run over a single-utterance clip gives the batch transcript byte for byte.

## Not in ABI 1

Hotword biasing (the decoder entry point exists inside the library; the automaton port is a v2 item), batch decoding of several
buffers in one call, per-call thread counts, a Windows/MSVC build (mingw is the expected path; the sources are GCC/Clang C).
