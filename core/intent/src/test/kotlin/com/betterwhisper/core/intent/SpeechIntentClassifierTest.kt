package com.betterwhisper.core.intent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechIntentClassifierTest {

    @Test
    fun testTrueRepairCues() {
        val repairSamples = listOf(
            "how about at 5pm no at 6",
            "meet me at 5 no 6pm",
            "let's meet on Tuesday wait make it Wednesday",
            "tomorrow wait Thursday",
            "send the email to David actually Sarah",
            "let's order pizza actually sushi",
            "that costs twenty five I mean thirty five dollars",
            "page 42 I mean 43",
            "I wanted to ask if wait forget that let's order food",
            "call Bob scratch that call Alice",
            "delete the file never mind keep it",
            "meeting at 3 or rather 4",
            "we need 10 items sorry I meant 12 items",
            "meet at 4 no wait 5"
        )

        for (sample in repairSamples) {
            assertTrue("Expected repair cue detected in: \"$sample\"", SpeechIntentClassifier.hasRepairCues(sample))
        }
    }

    @Test
    fun testFalsePositiveGuards() {
        val nonRepairSamples = listOf(
            "No I don't think we should do that",
            "No that is not correct",
            "There is no problem at all",
            "Say no to junk food",
            "Please wait for me at the front door",
            "I cannot wait to see you tomorrow",
            "Wait here until I get back",
            "That movie was actually incredible",
            "I actually like coffee more than tea",
            "She actually solved the entire puzzle",
            "How about we go to the park at 6pm",
            "Call me tomorrow morning",
            "Send the report to Sarah and David",
            "Hello world this is a test dictation"
        )

        for (sample in nonRepairSamples) {
            assertFalse("Expected NO repair cue in: \"$sample\"", SpeechIntentClassifier.hasRepairCues(sample))
        }
    }

    @Test
    fun testEdgeCases() {
        assertFalse(SpeechIntentClassifier.hasRepairCues(""))
        assertFalse(SpeechIntentClassifier.hasRepairCues("   "))
        assertFalse(SpeechIntentClassifier.hasRepairCues("no"))
        assertFalse(SpeechIntentClassifier.hasRepairCues("wait"))
        assertFalse(SpeechIntentClassifier.hasRepairCues("actually"))
    }
}
