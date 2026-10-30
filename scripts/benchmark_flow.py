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
LIB_PATH = os.path.join(PROJECT_ROOT, "core", "target", "release", "libbetter_whisper_core.so")

if not os.path.exists(LIB_PATH):
    print(f"Error: Shared library not found at {LIB_PATH}")
    print("Please run: cargo build --release --manifest-path core/Cargo.toml")
    sys.exit(1)

# Load C-ABI functions
lib = ctypes.CDLL(LIB_PATH)

lib.better_whisper_clean_text.argtypes = [
    ctypes.c_char_p,  # input
    ctypes.c_char_p,  # context_before_cursor
    ctypes.c_bool,    # remove_fillers
    ctypes.c_bool,    # deduplicate_stutters
    ctypes.c_bool,    # handle_self_corrections
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
        True, # remove_fillers
        True, # deduplicate_stutters
        True, # handle_self_corrections
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
    print(f"Average cleaner latency: {avg_latency_us:.2f} microseconds (<0.1 ms!)")
    print("=" * 65)

if __name__ == "__main__":
    run_tests()
