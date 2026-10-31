package com.betterwhisper.core.intent

/**
 * High-speed (<0.05ms), zero-allocation heuristic classifier for speech repair and
 * self-correction cues.
 *
 * Distinguishes true speech repairs ("at 5pm no at 6", "Tuesday wait make it Wednesday",
 * "David actually Sarah") from legitimate grammatical uses ("No I don't", "There is no problem",
 * "wait for me", "Can you make it to dinner", "Do you know what I mean", "The server actually crashed").
 */
object SpeechIntentClassifier {

    private val SAY_NO_TO_REGEX = Regex("(?i)\\b(say|said|says|saying)\\s+no\\s+to\\b")
    private val WHAT_I_MEAN_REGEX = Regex("(?i)\\bwhat\\s+i\\s+mean\\b")

    private val EXPLICIT_REPAIR_REGEX = Regex(
        "(?i)\\b(scratch that|never mind|forget that|delete that|sorry i meant|or rather|no wait|wait no|wait make it|wait make that|no make it)\\b"
    )

    private val NO_PREPOSITION_REPAIR_REGEX = Regex(
        "(?i)\\bno\\s+(at|on|to|in|for|from|with|actually|rather)\\b"
    )

    private val TIME_OR_NUMBER_NO_REPAIR_REGEX = Regex(
        "(?i)\\b\\d+(?::\\d+)?(?:am|pm)?\\s+no\\s+\\d+(?::\\d+)?(?:am|pm)?\\b"
    )

    private val WAIT_REPAIR_REGEX = Regex(
        "(?i)\\b(tomorrow|yesterday|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|if|[0-9]+(?:am|pm)?)\\s+wait\\b"
    )

    private val WAIT_CONTINUATION_REGEX = Regex(
        "(?i)\\bwait\\s+(make it|make that|sorry|actually|forget)\\b"
    )

    private val WAIT_VERB_REGEX = Regex(
        "(?i)\\b(please\\s+wait|can't\\s+wait|cannot\\s+wait|wait\\s+for|wait\\s+until|wait\\s+here|wait\\s+there|wait\\s+a\\s+minute|wait\\s+a\\s+second|wait\\s+list)\\b"
    )

    // True conversational "actually" repairs:
    // 1. Explicit markers: "no actually", "wait actually", "or actually", "actually no", "actually to/at/for/in"
    private val ACTUALLY_MARKER_REGEX = Regex(
        "(?i)\\b(no\\s+actually|wait\\s+actually|or\\s+actually|actually\\s+no|actually\\s+(?:to|at|for|in|on|with))\\b"
    )

    // 2. Prepositional entity swaps: e.g. "to David actually Sarah", "at 5 actually 6"
    private val ACTUALLY_PREP_SWAP_REGEX = Regex(
        "(?i)\\b(?:to|at|for|in|on|with)\\s+(\\w+)\\s+actually\\s+(\\w+)\\b"
    )

    // 3. Proper name swaps: e.g. "David actually Sarah"
    private val ACTUALLY_NAME_SWAP_REGEX = Regex(
        "\\b([A-Z][a-z]+)\\s+actually\\s+([A-Z][a-z]+)\\b"
    )

    // 4. Direct object replacement: e.g. "order pizza actually sushi", "eat pizza actually pasta"
    private val ACTUALLY_OBJECT_SWAP_REGEX = Regex(
        "(?i)\\b(?:order|want|get|buy|eat|have)\\s+(\\w+)\\s+actually\\s+(\\w+)\\b"
    )

    // Mid-utterance "I mean": e.g. "twenty five I mean thirty five", "page 42 I mean 43"
    private val I_MEAN_REPAIR_REGEX = Regex(
        "(?i)\\b(\\w+)\\s+i\\s+mean\\s+(\\w+)\\b"
    )

    /**
     * Determines whether [text] contains probable conversational repair cues
     * that justify invoking the SLM Speech Intent engine.
     */
    fun hasRepairCues(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Fast zero-allocation word count check (< 3 words cannot form repair triplet)
        var wordCount = 0
        var inWord = false
        for (i in 0 until trimmed.length) {
            if (!trimmed[i].isWhitespace()) {
                if (!inWord) {
                    wordCount++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        if (wordCount < 3) return false

        if (EXPLICIT_REPAIR_REGEX.containsMatchIn(trimmed)) return true

        if (NO_PREPOSITION_REPAIR_REGEX.containsMatchIn(trimmed) || TIME_OR_NUMBER_NO_REPAIR_REGEX.containsMatchIn(trimmed)) {
            if (!SAY_NO_TO_REGEX.containsMatchIn(trimmed) &&
                !trimmed.startsWith("no ", ignoreCase = true) &&
                !trimmed.startsWith("no,", ignoreCase = true)) {
                return true
            }
        }

        if ((WAIT_REPAIR_REGEX.containsMatchIn(trimmed) || WAIT_CONTINUATION_REGEX.containsMatchIn(trimmed)) &&
            !WAIT_VERB_REGEX.containsMatchIn(trimmed)) {
            return true
        }

        if (I_MEAN_REPAIR_REGEX.containsMatchIn(trimmed)) {
            if (!trimmed.startsWith("i mean", ignoreCase = true) && !WHAT_I_MEAN_REGEX.containsMatchIn(trimmed)) {
                return true
            }
        }

        if (ACTUALLY_MARKER_REGEX.containsMatchIn(trimmed) ||
            ACTUALLY_PREP_SWAP_REGEX.containsMatchIn(trimmed) ||
            ACTUALLY_NAME_SWAP_REGEX.containsMatchIn(trimmed) ||
            ACTUALLY_OBJECT_SWAP_REGEX.containsMatchIn(trimmed)) {
            return true
        }

        return false
    }
}
