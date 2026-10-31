package com.betterwhisper.core.intent

/**
 * High-speed (<0.05ms), zero-allocation heuristic classifier for speech repair and
 * self-correction cues.
 *
 * Distinguishes true speech repairs ("at 5pm no at 6", "Tuesday wait make it Wednesday",
 * "David actually Sarah", "How about at six? No, seven.", "for four pm. No, actually five pm")
 * from legitimate grammatical uses ("No I don't", "There is no problem", "wait for me",
 * "Can you make it to dinner", "Do you know what I mean", "The server actually crashed").
 */
object SpeechIntentClassifier {

    private val SAY_NO_TO_REGEX = Regex("(?i)\\b(say|said|says|saying)\\s+no\\s+to\\b")
    private val WHAT_I_MEAN_REGEX = Regex("(?i)\\bwhat\\s+i\\s+mean\\b")

    // Punctuation-tolerant explicit repairs: "scratch that", "no, wait", "wait, no", "sorry, I meant", "or, rather", "no, make it"
    private val EXPLICIT_REPAIR_REGEX = Regex(
        "(?i)\\b(scratch\\s+that|never\\s+mind|forget\\s+that|delete\\s+that|sorry[, ]+i\\s+meant|or[, ]+rather|no[, ]+wait|wait[, ]+no|wait[, ]+make\\s+it|wait[, ]+make\\s+that|no[, ]+make\\s+it)\\b"
    )

    // No followed by preposition, adverb, or correction marker: 'no, actually', 'no, at', 'no, on', 'no, rather'
    private val NO_PREPOSITION_REPAIR_REGEX = Regex(
        "(?i)\\bno[, ]+(at|on|to|in|for|from|with|actually|rather|instead)\\b"
    )

    // Mid-utterance 'X, no, Y' where X and Y are numbers/times/days/entities
    // e.g. 'at six? No, seven', 'at 5pm no at 6', 'at 5, no, 6', 'Monday, no, Tuesday', 'for 4 p.m. No, 5 p.m.'
    private const val DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow|yesterday"
    private const val NUM_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|twenty|thirty|forty|fifty"
    private const val TIME_OR_NUM = "(?:\\d+(?::\\d+)?(?:\\s*[ap]\\.?m\\.?)?|$NUM_WORDS|$DAYS)"

    private val MID_UTTERANCE_NO_SWAP_REGEX = Regex(
        "(?i)\\b$TIME_OR_NUM\\b[?,;.]*\\s+no[, ]+$TIME_OR_NUM\\b"
    )

    private val WAIT_REPAIR_REGEX = Regex(
        "(?i)\\b(tomorrow|yesterday|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|if|[0-9]+(?:\\s*[ap]\\.?m\\.?)?)\\s*[,.]*\\s+wait\\b"
    )

    private val WAIT_CONTINUATION_REGEX = Regex(
        "(?i)\\bwait[, ]+(make\\s+it|make\\s+that|sorry|actually|forget)\\b"
    )

    private val WAIT_VERB_REGEX = Regex(
        "(?i)\\b(please\\s+wait|can't\\s+wait|cannot\\s+wait|wait\\s+for|wait\\s+until|wait\\s+here|wait\\s+there|wait\\s+a\\s+minute|wait\\s+a\\s+second|wait\\s+list)\\b"
    )

    // Actually repairs (punctuation-tolerant: handles commas inserted by ASR)
    private val ACTUALLY_MARKER_REGEX = Regex(
        "(?i)\\b(?:(?:no|wait|or)[, ]+actually|actually[, ]+(?:no|to|at|for|in|on|with))\\b"
    )

    private val ACTUALLY_PREP_SWAP_REGEX = Regex(
        "(?i)\\b(?:to|at|for|in|on|with)\\s+(\\w+)[, ]+actually[, ]+(\\w+)\\b"
    )

    private val ACTUALLY_NAME_SWAP_REGEX = Regex(
        "\\b([A-Z][a-z]+)[, ]+actually[, ]+([A-Z][a-z]+)\\b"
    )

    private val ACTUALLY_OBJECT_SWAP_REGEX = Regex(
        "(?i)\\b(?:order|want|get|buy|eat|have)\\s+(\\w+)[, ]+actually[, ]+(\\w+)\\b"
    )

    private val I_MEAN_REPAIR_REGEX = Regex(
        "(?i)\\b(\\w+)[, ]+i\\s+mean[, ]+(\\w+)\\b"
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

        if (NO_PREPOSITION_REPAIR_REGEX.containsMatchIn(trimmed) || MID_UTTERANCE_NO_SWAP_REGEX.containsMatchIn(trimmed)) {
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
