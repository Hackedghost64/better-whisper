package com.betterwhisper.core.cleaner

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowTextCleanerTest {

    private fun clean(input: String, context: String? = null) =
        FlowTextCleaner.clean(input, context)

    @Test
    fun `removes disfluencies and the comma that framed them`() {
        assertEquals(
            "I think we should deploy the new model today.",
            clean("Um, I think we should, uh, deploy the new model today.")
        )
    }

    @Test
    fun `removes empty discourse markers`() {
        assertEquals(
            "It's really good, and honestly fine.",
            clean("It's basically like really good, you know, and honestly literally fine.")
        )
    }

    @Test
    fun `keeps literal like`() {
        assertEquals(
            "I like pizza and it looks like rain.",
            clean("I like pizza and it looks like rain.")
        )
        assertEquals("Would you like to go?", clean("Would you like to go?"))
        assertEquals("What kind of car is that?", clean("What kind of car is that?"))
    }

    @Test
    fun `removes filler like`() {
        assertEquals("It's really cold outside.", clean("It's like really cold outside."))
    }

    @Test
    fun `deduplicates single and multi word stutters`() {
        assertEquals(
            "I want to see if we need to push now.",
            clean("I I want to to see if we need to we need to push now.")
        )
    }

    @Test
    fun `handles scratch that`() {
        assertEquals(
            "Message Alice on Slack.",
            clean("Draft an email to Bob scratch that message Alice on Slack.")
        )
    }

    @Test
    fun `handles no wait corrections`() {
        assertEquals(
            "Let's schedule the meeting for 3 PM.",
            clean("Let's schedule the meeting for 2 PM no wait 3 PM.")
        )
    }

    @Test
    fun `respects cursor context`() {
        assertEquals(
            " that this offline model is fast.",
            clean("that this offline model is fast.", "I was thinking")
        )
        assertEquals(
            "It works offline.",
            clean("it works offline.", "We deployed it! ")
        )
    }

    @Test
    fun `recapitalizes after a removed filler`() {
        assertEquals("Done. Let's move on.", clean("Done. Um, let's move on."))
    }

    @Test
    fun `conservative mode keeps discourse markers`() {
        val out = FlowTextCleaner.clean(
            rawText = "It's kind of weird but okay.",
            aggressiveDiscourse = false
        )
        assertEquals("It's kind of weird but okay.", out)
    }

    @Test
    fun `applies default dictionary casing`() {
        val cleaned = FlowTextCleaner.clean("we tested phonon 2 and whisper flow on ios.")
        assertEquals(
            "We tested Phonon-2 and Wispr Flow on iOS.",
            FlowTextCleaner.applyDictionary(cleaned)
        )
    }

    @Test
    fun `does not strip actually as a self correction`() {
        assertEquals(
            "I actually went there yesterday.",
            clean("I actually went there yesterday.")
        )
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", clean("   "))
        assertEquals("", clean("um uh"))
    }
}
