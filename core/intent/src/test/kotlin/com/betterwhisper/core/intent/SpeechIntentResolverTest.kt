package com.betterwhisper.core.intent

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechIntentResolverTest {

    @Test
    fun testFastPathBypassesWhenNoRepairCues() {
        // Fluent speech with stutters and tech words should be cleanly formatted via fast path
        val raw = "hello i want to check github and and test phonon 2"
        val resolved = SpeechIntentResolver.resolve(raw)
        // Stutter "and and" deduplicated, tech dictionary capitalized, sentence capitalized
        assertEquals("Hello i want to check GitHub and test Phonon-2", resolved)
    }

    @Test
    fun testEmptyInputHandling() {
        assertEquals("", SpeechIntentResolver.resolve(""))
        assertEquals("", SpeechIntentResolver.resolve("   "))
    }
}
