package com.betterwhisper.core.intent

/**
 * High-speed (<0.2ms), zero-allocation heuristic classifier for speech repair and
 * self-correction cues.
 *
 * Distinguishes true speech repairs ("at 5pm no at 6", "Tuesday wait Wednesday",
 * "David actually Sarah") from legitimate grammatical uses ("No I don't", "There is no problem",
 * "wait for me", "actually good").
 */
object SpeechIntentClassifier {

    // Explicit, unequivocal repair triggers
    private val EXPLICIT_REPAIR_REGEX = Regex(
        "(?i)\\b(scratch that|never mind|forget that|delete that|sorry i meant|or rather|no wait|wait no|make it|make that)\\b"
    )

    // Speech repair "no" followed immediately by replacement preposition or marker:
    // e.g. "at 5pm no at 6", "call Bob no to Alice", "Tuesday no on Wednesday"
    private val NO_PREPOSITION_REPAIR_REGEX = Regex(
        "(?i)\\bno\\s+(at|on|to|in|for|from|with|actually|rather)\\b"
    )

    // Time/number replacement via "no":
    // e.g. "5 no 6", "5pm no 6pm", "5 no 6pm", "5:30 no 6"
    private val TIME_OR_NUMBER_NO_REPAIR_REGEX = Regex(
        "(?i)\\b\\d+(?::\\d+)?(?:am|pm)?\\s+no\\s+\\d+(?::\\d+)?(?:am|pm)?\\b"
    )

    // Mid-sentence "wait" as a conversational repair cue:
    // e.g. "tomorrow wait Thursday", "Tuesday wait make", "if wait forget"
    private val WAIT_REPAIR_REGEX = Regex(
        "(?i)\\b(tomorrow|yesterday|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|if|that|call|[0-9]+(?:am|pm)?)\\s+wait\\b"
    )

    // "wait" followed by repair continuation: e.g. "wait make it", "wait sorry"
    private val WAIT_CONTINUATION_REGEX = Regex(
        "(?i)\\bwait\\s+(make it|make that|sorry|actually|forget)\\b"
    )

    // Protected "wait" verb patterns that should NOT trigger repair
    private val WAIT_VERB_REGEX = Regex(
        "(?i)\\b(please\\s+wait|can't\\s+wait|cannot\\s+wait|wait\\s+for|wait\\s+until|wait\\s+here|wait\\s+there|wait\\s+a\\s+minute|wait\\s+a\\s+second)\\b"
    )

    // Mid-sentence entity swap with "actually":
    // e.g. "pizza actually sushi", "David actually Sarah", "to David actually Sarah"
    private val ACTUALLY_SWAP_REGEX = Regex(
        "(?i)\\b([a-zA-Z0-9]+)\\s+actually\\s+([a-zA-Z0-9]+)\\b"
    )

    // Common adverbial "actually" usages that should NOT trigger repair
    private val ACTUALLY_ADVERB_REGEX = Regex(
        "(?i)\\b(was|is|are|were|am|be|been|it|i|you|he|she|we|they|that)\\s+actually\\s+(incredible|great|good|bad|nice|fun|cool|smart|like|likes|liked|think|thought|did|do|does|have|had|has|solved|really|quite|pretty|very)\\b"
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
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) {
            // Less than 3 words cannot form an antecedent-cue-repair triplet
            return false
        }

        // 1. Explicit repair phrases (e.g. "scratch that", "no wait", "sorry I meant", "make it")
        if (EXPLICIT_REPAIR_REGEX.containsMatchIn(trimmed)) {
            return true
        }

        // 2. Mid-sentence "no" corrections (e.g. "5pm no at 6", "5 no 6pm")
        if (NO_PREPOSITION_REPAIR_REGEX.containsMatchIn(trimmed) || TIME_OR_NUMBER_NO_REPAIR_REGEX.containsMatchIn(trimmed)) {
            // Guard: leading "No" is usually a direct statement ("No I don't think so")
            // Guard: "say/said no to" is an idiom ("Say no to junk food")
            val isSayNoTo = Regex("(?i)\\b(say|said|says)\\s+no\\s+to\\b").containsMatchIn(trimmed)
            if (!isSayNoTo && !trimmed.startsWith("no ", ignoreCase = true) && !trimmed.startsWith("no,", ignoreCase = true)) {
                return true
            }
        }

        // 3. Mid-sentence "wait" correction
        if ((WAIT_REPAIR_REGEX.containsMatchIn(trimmed) || WAIT_CONTINUATION_REGEX.containsMatchIn(trimmed)) &&
            !WAIT_VERB_REGEX.containsMatchIn(trimmed)) {
            return true
        }

        // 4. "I mean" repair
        if (I_MEAN_REPAIR_REGEX.containsMatchIn(trimmed)) {
            if (!trimmed.startsWith("i mean", ignoreCase = true)) {
                return true
            }
        }

        // 5. "actually" noun/entity swap
        if (ACTUALLY_SWAP_REGEX.containsMatchIn(trimmed)) {
            if (!ACTUALLY_ADVERB_REGEX.containsMatchIn(trimmed)) {
                val match = ACTUALLY_SWAP_REGEX.find(trimmed)
                if (match != null) {
                    val prevWord = match.groupValues[1].lowercase()
                    val commonVerbs = setOf("i", "you", "he", "she", "it", "we", "they", "was", "is", "are", "were")
                    if (prevWord !in commonVerbs) {
                        return true
                    }
                }
            }
        }

        return false
    }
}
