#!/usr/bin/env python3
"""
Better Whisper: End-to-end Flow Post-Processing Benchmark & Validation Script.
Validates that text cleaning, filler removal, stutter deduplication, and
context casing run in sub-millisecond time.
"""

import ctypes
import os
import sys
import time

# Locate the compiled Rust core shared library
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
LIB_PATH = os.path.join(
    PROJECT_ROOT, "core", "text-cleaning", "engine", "target", "release",
    "libbetter_whisper_core.so",
)

if not os.path.exists(LIB_PATH):
    print(f"Error: Shared library not found at {LIB_PATH}")
    print("Please run: cargo build --release --manifest-path core/text-cleaning/engine/Cargo.toml")
    sys.exit(1)

# Load C-ABI functions
lib = ctypes.CDLL(LIB_PATH)

lib.better_whisper_clean_text.argtypes = [
    ctypes.c_char_p,  # input
    ctypes.c_char_p,  # context_before_cursor
    ctypes.c_int,     # remove_fillers (int32_t, not C bool)
    ctypes.c_int,     # deduplicate_stutters
    ctypes.c_int,     # handle_self_corrections
    ctypes.c_int,     # aggressive_discourse
]
lib.better_whisper_clean_text.restype = ctypes.c_void_p

lib.better_whisper_free_string.argtypes = [ctypes.c_void_p]
lib.better_whisper_free_string.restype = None

def clean_text(raw_text: str, context: str = None) -> str:
    raw_bytes = raw_text.encode("utf-8")
    context_bytes = context.encode("utf-8") if context is not None else None

    ptr = lib.better_whisper_clean_text(
        raw_bytes,
        context_bytes,
        1, # remove_fillers
        1, # deduplicate_stutters
        1, # handle_self_corrections
        1, # aggressive_discourse
    )
    if not ptr:
        return ""
    try:
        result_str = ctypes.cast(ptr, ctypes.c_char_p).value.decode("utf-8")
        return result_str
    finally:
        lib.better_whisper_free_string(ptr)

def run_tests():
    print("=" * 65)
    print(" Better Whisper: Flow Post-Processing Test Suite")
    print("=" * 65)

    test_cases = [
        {
            "name": "Filler word removal & comma cleanup",
            "input": "Um, I think we should, uh, deploy the new model today.",
            "context": None,
            "expected": "I think we should deploy the new model today."
        },
        {
            "name": "Single & multi-word stutter deduplication",
            "input": "I I want to to see if we need to we need to push now.",
            "context": None,
            "expected": "I want to see if we need to push now."
        },
        {
            "name": "Self-correction via 'scratch that'",
            "input": "Draft an email to Bob scratch that message Alice on Slack.",
            "context": None,
            "expected": "Message Alice on Slack."
        },
        {
            "name": "Self-correction via 'no wait'",
            "input": "Let's schedule the meeting for 2 PM no wait 3 PM.",
            "context": None,
            "expected": "Let's schedule the meeting for 3 PM."
        },
        {
            "name": "Context awareness: sentence continuation (lowercase + space)",
            "input": "that this offline model is super fast.",
            "context": "I was thinking",
            "expected": " that this offline model is super fast."
        },
        {
            "name": "Context awareness: new sentence (capitalize)",
            "input": "it works completely offline with zero latency.",
            "context": "We just deployed the app! ",
            "expected": "It works completely offline with zero latency."
        },
        {
            "name": "Custom dictionary tech replacements",
            "input": "We tested phonon 2 and whisper flow on ios.",
            "context": None,
            "expected": "We tested Phonon-2 and Wispr Flow on iOS."
        },
        {
            "name": "Contextual filler + discourse removal",
            "input": "It's basically like really good, you know, and honestly fine.",
            "context": None,
            "expected": "It's really good, and honestly fine."
        },
        {
            "name": "Literal 'like' is preserved",
            "input": "I like pizza and it looks like rain.",
            "context": None,
            "expected": "I like pizza and it looks like rain."
        },
        {
            "name": "Literal 'kind of' is preserved",
            "input": "What kind of car is that?",
            "context": None,
            "expected": "What kind of car is that?"
        }
    ]

    all_passed = True
    for i, tc in enumerate(test_cases, 1):
        output = clean_text(tc["input"], tc["context"])
        passed = (output == tc["expected"])
        status = "PASSED" if passed else "FAILED"
        if not passed:
            all_passed = False
        print(f"[{status}] Test {i}: {tc['name']}")
        if not passed:
            print(f"   Input:    '{tc['input']}'")
            print(f"   Context:  '{tc['context']}'")
            print(f"   Expected: '{tc['expected']}'")
            print(f"   Actual:   '{output}'")

    print("-" * 65)
    if all_passed:
        print("All test cases PASSED!")
    else:
        print("Some tests failed.")

    # Latency / Throughput Benchmark
    print("\nRunning Latency Benchmark (10,000 iterations)...")
    sample_sentence = "Um, I I really think that phonon 2 is, uh, faster than whisper flow."
    warmup = clean_text(sample_sentence)
    
    start_time = time.perf_counter()
    iterations = 10000
    for _ in range(iterations):
        _ = clean_text(sample_sentence, "Previous sentence. ")
    total_time = time.perf_counter() - start_time
    avg_latency_us = (total_time / iterations) * 1_000_000

    print(f"Total time for {iterations} runs: {total_time:.4f} seconds")
    print(f"Average cleaner latency: {avg_latency_us:.2f} microseconds ({avg_latency_us / 1000:.2f} ms)")
    if avg_latency_us >= 1000:
        print("Note: this is above the 1.5 ms design target; check for a debug build.")
    print("=" * 65)

if __name__ == "__main__":
    run_tests()
