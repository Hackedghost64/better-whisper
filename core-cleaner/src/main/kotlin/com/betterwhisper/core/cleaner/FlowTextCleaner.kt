package com.betterwhisper.core.cleaner

/**
 * Pure-Kotlin transcription cleaner.
 *
 * This is the reference implementation of the Flow post-processing pipeline.
 * It has no Android dependencies so it can be unit-tested on the JVM, and it is
 * kept behaviourally identical to `core/src/cleaner.rs` (the Rust fast path).
 *
 * Filler handling is context-aware rather than a blind word blacklist:
 *  - disfluencies ("um", "uh", "hmm") are always dropped;
 *  - empty discourse markers ("basically", "literally") are always dropped;
 *  - ambiguous markers ("like", "you know", "I mean", "kind of", "sort of")
 *    are only dropped when they are not syntactically load-bearing
 *    ("looks like rain", "would like to", "what kind of car" are preserved).
 */
object FlowTextCleaner {

    private val UNCONDITIONAL_FILLERS = setOf(
        "um", "umm", "uh", "uhh", "er", "erm", "ah", "ahh", "hmm", "hm", "mm", "mmm"
    )

    /** Words that carry no meaning in dictated text and are safe to drop. */
    private val ALWAYS_DISCOURSE = setOf("basically", "literally")

    /** Two-word discourse markers handled together. */
    private val SOFT_MARKERS = setOf("you know", "i mean", "sort of", "kind of")

    /** Previous word after which "like" is a real word, not a filler. */
    private val LIKE_PROTECTED_PREV = setOf(
        "look", "looks", "looked", "looking",
        "sound", "sounds", "sounded", "sounding",
        "feel", "feels", "felt", "feeling",
        "seem", "seems", "seemed", "seeming",
        "taste", "tastes", "tasted", "taste",
        "smell", "smells", "smelled", "smelt",
        "appear", "appears", "appeared",
        "such", "would", "should", "could",
        "do", "does", "did", "don't", "doesn't", "didn't",
        "more", "much", "just", "something", "anything", "nothing"
    )

    /** Previous word that commonly introduces a filler "like". */
    private val LIKE_FILLER_PREV = setOf(
        "is", "are", "was", "were", "am", "be", "been",
        "it's", "that's", "really", "very", "pretty",
        "so", "and", "but", "then", "actually", "basically", "literally"
    )

    /** Next word that starts a clause, so "like" was probably a quotative. */
    private val CLAUSE_STARTERS = setOf(
        "i", "you", "we", "they", "he", "she", "it", "it's",
        "this", "that", "that's", "these", "those", "there",
        "what", "who", "why", "how", "when", "where", "if", "because", "cause"
    )

    private val DETERMINERS = setOf(
        "a", "an", "the", "my", "your", "his", "her", "its", "their", "our"
    )

    /** Previous word that makes "kind of" / "sort of" / "you know" literal. */
    private val SOFT_PROTECTED_PREV = setOf(
        "do", "does", "did", "don't", "doesn't", "didn't",
        "what", "which", "this", "that", "these", "those",
        "any", "some", "every", "another", "one", "all", "no", "different",
        "as", "than"
    )

    private val SCRATCH_THAT = Regex("(?i)\\b(scratch that|never mind|delete that)\\b")
    private val NO_WAIT = Regex("(?i)\\b(no wait|sorry i meant|or rather)\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val CONJUNCTIONS = setOf("and", "but", "so", "then", "or", "nor", "yet")

    /**
     * Default vocabulary casing. Mirrors `CustomDictionary::new()` in Rust.
     * Sorted longest-first so "phonon 2" wins over a hypothetical "phonon".
     */
    private val DEFAULT_DICTIONARY: List<Pair<Regex, String>> = listOf(
        "phonon 2" to "Phonon-2",
        "phonon-2" to "Phonon-2",
        "whisper flow" to "Wispr Flow",
        "github" to "GitHub",
        "ios" to "iOS",
        "core ml" to "CoreML",
        "coreml" to "CoreML",
        "macos" to "macOS",
        "postgresql" to "PostgreSQL",
        "sqlite" to "SQLite"
    ).sortedByDescending { it.first.length }
        .map { (from, to) -> Regex("(?i)\\b${Regex.escape(from)}\\b") to to }

    fun clean(
        rawText: String,
        contextBeforeCursor: String? = null,
        removeFillers: Boolean = true,
        deduplicateStutters: Boolean = true,
        handleSelfCorrections: Boolean = true,
        aggressiveDiscourse: Boolean = true
    ): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        if (handleSelfCorrections) {
            text = applySelfCorrections(text)
        }
        if (removeFillers) {
            text = removeFillers(text, aggressiveDiscourse)
        }
        if (deduplicateStutters) {
            text = deduplicateStutters(text)
        }
        text = normalize(text)
        if (text.isEmpty()) return ""
        text = capitalizeSentences(text)

        return applyContext(text, contextBeforeCursor)
    }

    /** Applies the default tech vocabulary casing ("github" -> "GitHub"). */
    fun applyDictionary(text: String): String {
        var result = text
        for ((regex, replacement) in DEFAULT_DICTIONARY) {
            result = regex.replace(result, replacement)
        }
        return result
    }

    // ------------------------------------------------------------------ steps

    private fun applySelfCorrections(input: String): String {
        SCRATCH_THAT.find(input)?.let { match ->
            return input.substring(match.range.last + 1).trim()
        }

        NO_WAIT.find(input)?.let { match ->
            val beforeWords = input.substring(0, match.range.first)
                .trimEnd()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .toMutableList()
            val after = input.substring(match.range.last + 1).trim()
            val afterWords = after.split(WHITESPACE).filter { it.isNotEmpty() }
            val toPop = afterWords.size.coerceAtMost(beforeWords.size).coerceAtLeast(1)
            repeat(toPop) {
                if (beforeWords.isNotEmpty()) beforeWords.removeAt(beforeWords.lastIndex)
            }
            return if (beforeWords.isEmpty()) after else "${beforeWords.joinToString(" ")} $after"
        }

        return input
    }

    private fun removeFillers(input: String, aggressive: Boolean): String {
        val tokens = input.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""

        val cores = tokens.map(::coreOf)
        val drop = BooleanArray(tokens.size)
        val n = tokens.size

        for (i in 0 until n) {
            val core = cores[i]
            if (core in UNCONDITIONAL_FILLERS || core in ALWAYS_DISCOURSE) {
                drop[i] = true
            }
        }

        // "like" -------------------------------------------------------------
        for (i in 0 until n) {
            if (cores[i] != "like" || drop[i]) continue
            val prev = if (i > 0) cores[i - 1] else ""
            val next = if (i + 1 < n) cores[i + 1] else ""
            val protectedWord = prev in LIKE_PROTECTED_PREV
            val fenced = clauseBreakBefore(tokens, i) && clauseBreakAfter(tokens, i)

            when {
                protectedWord -> Unit
                fenced -> drop[i] = true
                aggressive && prev in LIKE_FILLER_PREV &&
                    next !in CLAUSE_STARTERS && next !in DETERMINERS -> drop[i] = true
            }
        }

        // "you know", "I mean", "kind of", "sort of" -------------------------
        for (i in 0 until n - 1) {
            if (drop[i] || drop[i + 1]) continue
            val marker = "${cores[i]} ${cores[i + 1]}"
            if (marker !in SOFT_MARKERS) continue

            val prev = if (i > 0) cores[i - 1] else ""
            val after = if (i + 2 < n) cores[i + 2] else ""
            val fenced = clauseBreakBefore(tokens, i) && clauseBreakAfter(tokens, i + 1)
            val aggressiveOk = aggressive &&
                prev !in SOFT_PROTECTED_PREV &&
                after !in CLAUSE_STARTERS &&
                after !in DETERMINERS

            if (fenced || aggressiveOk) {
                drop[i] = true
                drop[i + 1] = true
            }
        }

        val kept = ArrayList<String>(n)
        for (i in 0 until n) {
            if (drop[i]) {
                // The comma before a removed filler usually only delimited
                // that filler ("we should, uh, deploy"). Keep it when the next
                // kept word is a conjunction so lists and clauses survive.
                var nextKept: String? = null
                for (j in i + 1 until n) {
                    if (!drop[j]) {
                        nextKept = cores[j]
                        break
                    }
                }
                val preserveComma = nextKept != null && CONJUNCTIONS.contains(nextKept)
                if (!preserveComma) {
                    val last = kept.lastOrNull()
                    if (last != null && last.endsWith(',')) {
                        kept[kept.lastIndex] = last.dropLast(1)
                    }
                }
                continue
            }
            kept.add(tokens[i])
        }
        return kept.joinToString(" ")
    }

    private fun deduplicateStutters(input: String): String {
        val words = input.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""

        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (i + 5 < words.size) {
                val p1 = (0..2).map { coreOf(words[i + it]) }
                val p2 = (0..2).map { coreOf(words[i + 3 + it]) }
                if (p1[0].isNotEmpty() && p1 == p2) {
                    i += 3
                    continue
                }
            }
            if (i + 3 < words.size) {
                val p1 = (0..1).map { coreOf(words[i + it]) }
                val p2 = (0..1).map { coreOf(words[i + 2 + it]) }
                if (p1[0].isNotEmpty() && p1 == p2) {
                    i += 2
                    continue
                }
            }
            if (i + 1 < words.size) {
                val current = coreOf(words[i])
                val next = coreOf(words[i + 1])
                if (current.isNotEmpty() && current == next) {
                    i += 1
                    continue
                }
            }
            out.add(words[i])
            i++
        }
        return out.joinToString(" ")
    }

    private fun normalize(input: String): String {
        var s = input.replace(WHITESPACE, " ")
        s = s.replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(" ;", ";")
            .replace(" :", ":")
        s = s.replace(",,", ",")
            .replace(",;", ";")
            .replace(",.", ".")
            .replace("!.", "!")
            .replace("?.", "?")
        s = s.trimStart(',', ';', ':', '.', ' ')
        return s.trim()
    }

    /**
     * Capitalizes the first letter of every sentence, so removing a leading
     * filler does not leave "Fine. it's done.".
     */
    private fun capitalizeSentences(input: String): String {
        val out = StringBuilder(input.length)
        var capitalizeNext = true
        for (c in input) {
            if (capitalizeNext && c.isLetter()) {
                out.append(c.uppercaseChar())
                capitalizeNext = false
            } else {
                out.append(c)
                if (c == '.' || c == '!' || c == '?' || c == '\n') {
                    capitalizeNext = true
                }
            }
        }
        return out.toString()
    }

    private fun applyContext(text: String, contextBeforeCursor: String?): String {
        if (text.isEmpty()) return ""

        val context = contextBeforeCursor ?: return capitalizeFirst(text)
        val trimmedContext = context.trimEnd()
        val capitalize = trimmedContext.isEmpty() ||
            trimmedContext.endsWith(".") ||
            trimmedContext.endsWith("!") ||
            trimmedContext.endsWith("?") ||
            trimmedContext.endsWith("\n")

        var result = if (capitalize) capitalizeFirst(text) else uncapitalizeFirst(text)
        if (context.isNotEmpty() && !context.endsWith(" ") && !context.endsWith("\n")) {
            result = " $result"
        }
        return result
    }

    // ------------------------------------------------------------------ helpers

    private fun coreOf(token: String): String =
        token.trim { !it.isLetterOrDigit() }.lowercase()

    private fun endsClause(token: String): Boolean =
        token.endsWith(',') || token.endsWith('.') || token.endsWith(';') ||
            token.endsWith(':') || token.endsWith('!') || token.endsWith('?')

    private fun clauseBreakBefore(tokens: List<String>, index: Int): Boolean =
        index == 0 || endsClause(tokens[index - 1])

    private fun clauseBreakAfter(tokens: List<String>, index: Int): Boolean =
        index == tokens.lastIndex || endsClause(tokens[index])

    private fun capitalizeFirst(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun uncapitalizeFirst(text: String): String {
        if (text.isEmpty()) return text
        val first = text[0]
        if (first == 'I' && (text.length == 1 || text[1] == ' ' || text[1] == '\'')) return text
        if (text.length > 1 && text[1].isUpperCase()) return text
        return first.lowercaseChar() + text.substring(1)
    }
}
