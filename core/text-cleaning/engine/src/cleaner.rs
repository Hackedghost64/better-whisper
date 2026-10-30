use lazy_static::lazy_static;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WordToken {
    pub text: String,
    pub start: f32,
    pub end: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanerOptions {
    #[serde(default = "default_true")]
    pub remove_fillers: bool,
    #[serde(default = "default_true")]
    pub deduplicate_stutters: bool,
    #[serde(default = "default_true")]
    pub handle_self_corrections: bool,
    /// Also strip ambiguous discourse markers ("like", "you know", "kind of")
    /// outside of comma-delimited positions, while still protecting literal
    /// uses ("looks like", "would like", "what kind of").
    #[serde(default = "default_true")]
    pub aggressive_discourse: bool,
    #[serde(default)]
    pub context_before_cursor: Option<String>,
}

fn default_true() -> bool {
    true
}

impl Default for CleanerOptions {
    fn default() -> Self {
        Self {
            remove_fillers: true,
            deduplicate_stutters: true,
            handle_self_corrections: true,
            aggressive_discourse: true,
            context_before_cursor: None,
        }
    }
}

lazy_static! {
    /// Pure disfluencies: always safe to drop.
    static ref UNCONDITIONAL_FILLERS: HashSet<&'static str> = [
        "um", "umm", "uh", "uhh", "er", "erm", "ah", "ahh", "hmm", "hm", "mm", "mmm",
    ].iter().cloned().collect();

    /// Empty discourse markers: no meaning is lost by dropping them.
    static ref ALWAYS_DISCOURSE: HashSet<&'static str> = [
        "basically", "literally",
    ].iter().cloned().collect();

    /// Previous word after which "like" is a real word, not a filler.
    static ref LIKE_PROTECTED_PREV: HashSet<&'static str> = [
        "look", "looks", "looked", "looking",
        "sound", "sounds", "sounded", "sounding",
        "feel", "feels", "felt", "feeling",
        "seem", "seems", "seemed", "seeming",
        "taste", "tastes", "tasted", "smell", "smells", "smelled", "smelt",
        "appear", "appears", "appeared",
        "such", "would", "should", "could",
        "do", "does", "did", "don't", "doesn't", "didn't",
        "more", "much", "just", "something", "anything", "nothing",
    ].iter().cloned().collect();

    /// Previous word that commonly introduces a filler "like".
    static ref LIKE_FILLER_PREV: HashSet<&'static str> = [
        "is", "are", "was", "were", "am", "be", "been",
        "it's", "that's", "really", "very", "pretty",
        "so", "and", "but", "then", "actually", "basically", "literally",
    ].iter().cloned().collect();

    /// Next word that starts a clause, so "like" was probably quotative.
    static ref CLAUSE_STARTERS: HashSet<&'static str> = [
        "i", "you", "we", "they", "he", "she", "it", "it's",
        "this", "that", "that's", "these", "those", "there",
        "what", "who", "why", "how", "when", "where", "if", "because", "cause",
    ].iter().cloned().collect();

    static ref DETERMINERS: HashSet<&'static str> = [
        "a", "an", "the", "my", "your", "his", "her", "its", "their", "our",
    ].iter().cloned().collect();

    /// Previous word that makes "kind of" / "sort of" / "you know" literal.
    static ref SOFT_PROTECTED_PREV: HashSet<&'static str> = [
        "do", "does", "did", "don't", "doesn't", "didn't",
        "what", "which", "this", "that", "these", "those",
        "any", "some", "every", "another", "one", "all", "no", "different",
        "as", "than",
    ].iter().cloned().collect();

    static ref SCRATCH_THAT: Regex = Regex::new(
        r"(?i)\b(scratch that|never mind|delete that)\b"
    ).unwrap();

    static ref NO_WAIT: Regex = Regex::new(
        r"(?i)\b(no wait|sorry i meant|or rather)\s+"
    ).unwrap();
}

pub struct FlowCleaner;

impl FlowCleaner {
    /// Cleans raw transcribed text according to the provided options.
    pub fn clean_text(input: &str, options: &CleanerOptions) -> String {
        let mut text = input.trim().to_string();
        if text.is_empty() {
            return text;
        }

        if options.handle_self_corrections {
            text = Self::apply_self_corrections(&text);
        }
        if options.remove_fillers {
            text = Self::remove_fillers(&text, options.aggressive_discourse);
        }
        if options.deduplicate_stutters {
            text = Self::deduplicate_stutters(&text);
        }

        text = Self::normalize(&text);
        if text.is_empty() {
            return text;
        }
        text = Self::capitalize_sentences(&text);

        Self::apply_context(&text, options.context_before_cursor.as_deref())
    }

    // ------------------------------------------------------------------ steps

    fn apply_self_corrections(input: &str) -> String {
        if let Some(mat) = SCRATCH_THAT.find(input) {
            return input[mat.end()..].trim().to_string();
        }

        if let Some(mat) = NO_WAIT.find(input) {
            let before = input[..mat.start()].trim_end();
            let after = input[mat.end()..].trim_start();
            let mut before_words: Vec<&str> = before.split_whitespace().collect();
            let after_words: Vec<&str> = after.split_whitespace().collect();
            let to_pop = after_words.len().min(before_words.len()).max(1);
            for _ in 0..to_pop {
                before_words.pop();
            }
            return if before_words.is_empty() {
                after.to_string()
            } else {
                format!("{} {}", before_words.join(" "), after)
            };
        }

        input.to_string()
    }

    /// Context-aware filler removal.
    ///
    /// Token based (rather than regex based) so that the same rules can be
    /// mirrored exactly in Kotlin: the Rust `regex` crate has no look-around,
    /// which is what forced the old implementation to strip "like" blindly.
    fn remove_fillers(input: &str, aggressive: bool) -> String {
        let tokens: Vec<&str> = input.split_whitespace().collect();
        if tokens.is_empty() {
            return String::new();
        }

        let cores: Vec<String> = tokens.iter().map(|t| Self::core_of(t)).collect();
        let n = tokens.len();
        let mut drop = vec![false; n];

        for i in 0..n {
            let core = cores[i].as_str();
            if UNCONDITIONAL_FILLERS.contains(core) || ALWAYS_DISCOURSE.contains(core) {
                drop[i] = true;
            }
        }

        // "like" -------------------------------------------------------------
        for i in 0..n {
            if cores[i] != "like" || drop[i] {
                continue;
            }
            let prev = if i > 0 { cores[i - 1].as_str() } else { "" };
            let next = if i + 1 < n { cores[i + 1].as_str() } else { "" };
            let protected_word = LIKE_PROTECTED_PREV.contains(prev);
            let fenced = Self::clause_break_before(&tokens, i)
                && Self::clause_break_after(&tokens, i);

            if protected_word {
                continue;
            }
            if fenced {
                drop[i] = true;
            } else if aggressive
                && LIKE_FILLER_PREV.contains(prev)
                && !CLAUSE_STARTERS.contains(next)
                && !DETERMINERS.contains(next)
            {
                drop[i] = true;
            }
        }

        // "you know", "I mean", "kind of", "sort of" -------------------------
        for i in 0..n.saturating_sub(1) {
            if drop[i] || drop[i + 1] {
                continue;
            }
            let first = cores[i].as_str();
            let second = cores[i + 1].as_str();
            let is_marker = matches!(
                (first, second),
                ("you", "know") | ("i", "mean") | ("sort", "of") | ("kind", "of")
            );
            if !is_marker {
                continue;
            }

            let prev = if i > 0 { cores[i - 1].as_str() } else { "" };
            let after = if i + 2 < n { cores[i + 2].as_str() } else { "" };
            let fenced = Self::clause_break_before(&tokens, i)
                && Self::clause_break_after(&tokens, i + 1);
            let aggressive_ok = aggressive
                && !SOFT_PROTECTED_PREV.contains(prev)
                && !CLAUSE_STARTERS.contains(after)
                && !DETERMINERS.contains(after);

            if fenced || aggressive_ok {
                drop[i] = true;
                drop[i + 1] = true;
            }
        }

        let mut kept: Vec<String> = Vec::with_capacity(n);
        for i in 0..n {
            if drop[i] {
                // The comma before a removed filler usually only existed to
                // delimit that filler ("we should, uh, deploy"). Keep it when
                // the next kept word is a conjunction, so proper lists and
                // clauses survive ("good, you know, and fine").
                let next_kept = (i + 1..n)
                    .find(|&j| !drop[j])
                    .map(|j| cores[j].as_str());
                let preserve_comma = matches!(
                    next_kept,
                    Some("and" | "but" | "so" | "then" | "or" | "nor" | "yet")
                );
                if !preserve_comma {
                    if let Some(last) = kept.last_mut() {
                        if last.ends_with(',') {
                            last.pop();
                        }
                    }
                }
                continue;
            }
            kept.push(tokens[i].to_string());
        }
        kept.join(" ")
    }

    /// Deduplicates 1-, 2- and 3-word repeated phrases.
    fn deduplicate_stutters(input: &str) -> String {
        let words: Vec<&str> = input.split_whitespace().collect();
        if words.is_empty() {
            return String::new();
        }

        let mut cleaned: Vec<&str> = Vec::new();
        let mut i = 0;

        while i < words.len() {
            if i + 5 < words.len() {
                let p1 = [
                    Self::core_of(words[i]),
                    Self::core_of(words[i + 1]),
                    Self::core_of(words[i + 2]),
                ];
                let p2 = [
                    Self::core_of(words[i + 3]),
                    Self::core_of(words[i + 4]),
                    Self::core_of(words[i + 5]),
                ];
                if !p1[0].is_empty() && p1 == p2 {
                    i += 3;
                    continue;
                }
            }

            if i + 3 < words.len() {
                let p1 = [Self::core_of(words[i]), Self::core_of(words[i + 1])];
                let p2 = [Self::core_of(words[i + 2]), Self::core_of(words[i + 3])];
                if !p1[0].is_empty() && p1 == p2 {
                    i += 2;
                    continue;
                }
            }

            if i + 1 < words.len() {
                let current = Self::core_of(words[i]);
                let next = Self::core_of(words[i + 1]);
                if !current.is_empty() && current == next {
                    i += 1;
                    continue;
                }
            }

            cleaned.push(words[i]);
            i += 1;
        }

        cleaned.join(" ")
    }

    fn normalize(input: &str) -> String {
        let mut s = input.split_whitespace().collect::<Vec<_>>().join(" ");
        s = s
            .replace(" ,", ",")
            .replace(" .", ".")
            .replace(" !", "!")
            .replace(" ?", "?")
            .replace(" ;", ";")
            .replace(" :", ":");
        s = s
            .replace(",,", ",")
            .replace(",;", ";")
            .replace(",.", ".")
            .replace("!.", "!")
            .replace("?.", "?");
        s = s
            .trim_start_matches(|c: char| c == ',' || c == ';' || c == ':' || c == '.' || c == ' ')
            .to_string();
        s.trim().to_string()
    }

    /// Capitalizes the first letter of every sentence, so removing a leading
    /// filler does not leave "Fine. it's done.".
    fn capitalize_sentences(input: &str) -> String {
        let mut out = String::with_capacity(input.len());
        let mut capitalize_next = true;
        for c in input.chars() {
            if capitalize_next && c.is_alphabetic() {
                out.extend(c.to_uppercase());
                capitalize_next = false;
            } else {
                out.push(c);
                if c == '.' || c == '!' || c == '?' || c == '\n' {
                    capitalize_next = true;
                }
            }
        }
        out
    }

    fn apply_context(text: &str, context_before_cursor: Option<&str>) -> String {
        if text.is_empty() {
            return String::new();
        }

        let context = match context_before_cursor {
            Some(c) => c,
            None => return Self::capitalize_first_char(text),
        };

        let trimmed_context = context.trim_end();
        let needs_capital = trimmed_context.is_empty()
            || trimmed_context.ends_with('.')
            || trimmed_context.ends_with('!')
            || trimmed_context.ends_with('?')
            || trimmed_context.ends_with('\n');

        let mut result = if needs_capital {
            Self::capitalize_first_char(text)
        } else {
            Self::uncapitalize_first_char(text)
        };

        if !context.is_empty() && !context.ends_with(' ') && !context.ends_with('\n') {
            result = format!(" {}", result);
        }

        result
    }

    fn core_of(token: &str) -> String {
        token
            .trim_matches(|c: char| !c.is_alphanumeric())
            .to_lowercase()
    }

    fn ends_clause(token: &str) -> bool {
        token.ends_with(',')
            || token.ends_with('.')
            || token.ends_with(';')
            || token.ends_with(':')
            || token.ends_with('!')
            || token.ends_with('?')
    }

    fn clause_break_before(tokens: &[&str], index: usize) -> bool {
        index == 0 || Self::ends_clause(tokens[index - 1])
    }

    fn clause_break_after(tokens: &[&str], index: usize) -> bool {
        index + 1 == tokens.len() || Self::ends_clause(tokens[index])
    }

    fn capitalize_first_char(s: &str) -> String {
        let mut chars = s.chars();
        match chars.next() {
            None => String::new(),
            Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        }
    }

    fn uncapitalize_first_char(s: &str) -> String {
        let mut chars = s.chars();
        match chars.next() {
            None => String::new(),
            Some('I') if s.starts_with("I ") || s == "I" || s.starts_with("I'") => s.to_string(),
            Some(first) => {
                let remaining = chars.as_str();
                if remaining.chars().next().map_or(false, |c| c.is_uppercase()) {
                    s.to_string()
                } else {
                    first.to_lowercase().collect::<String>() + remaining
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_filler_removal() {
        let options = CleanerOptions::default();
        let input = "Um, I think we should, uh, deploy the new model today.";
        let cleaned = FlowCleaner::clean_text(input, &options);
        assert_eq!(cleaned, "I think we should deploy the new model today.");
    }

    #[test]
    fn test_discourse_markers_removed() {
        let options = CleanerOptions::default();
        let input = "It's basically like really good, you know, and honestly literally fine.";
        let cleaned = FlowCleaner::clean_text(input, &options);
        assert_eq!(cleaned, "It's really good, and honestly fine.");
    }

    #[test]
    fn test_literal_like_is_preserved() {
        let options = CleanerOptions::default();
        assert_eq!(
            FlowCleaner::clean_text("I like pizza and it looks like rain.", &options),
            "I like pizza and it looks like rain."
        );
        assert_eq!(
            FlowCleaner::clean_text("Would you like to go?", &options),
            "Would you like to go?"
        );
        assert_eq!(
            FlowCleaner::clean_text("What kind of car is that?", &options),
            "What kind of car is that?"
        );
    }

    #[test]
    fn test_filler_like_is_removed() {
        let options = CleanerOptions::default();
        assert_eq!(
            FlowCleaner::clean_text("It's like really cold outside.", &options),
            "It's really cold outside."
        );
    }

    #[test]
    fn test_stutter_deduplication() {
        let options = CleanerOptions::default();
        let input = "I I want to to see if we can do this.";
        let cleaned = FlowCleaner::clean_text(input, &options);
        assert_eq!(cleaned, "I want to see if we can do this.");
    }

    #[test]
    fn test_phrase_stutter_deduplication() {
        let options = CleanerOptions::default();
        let input = "we need to we need to push the commit now.";
        let cleaned = FlowCleaner::clean_text(input, &options);
        assert_eq!(cleaned, "We need to push the commit now.");
    }

    #[test]
    fn test_scratch_that() {
        let options = CleanerOptions::default();
        let input = "Send an email to Alice scratch that send a message on Slack.";
        let cleaned = FlowCleaner::clean_text(input, &options);
        assert_eq!(cleaned, "Send a message on Slack.");
    }

    #[test]
    fn test_context_awareness() {
        let mut options = CleanerOptions::default();
        options.context_before_cursor = Some("Hello world. ".to_string());
        let cleaned = FlowCleaner::clean_text("this is a test.", &options);
        assert_eq!(cleaned, "This is a test.");

        options.context_before_cursor = Some("I wanted to say".to_string());
        let cleaned2 = FlowCleaner::clean_text("that everything is fine.", &options);
        assert_eq!(cleaned2, " that everything is fine.");
    }

    #[test]
    fn test_sentence_recapitalized_after_filler() {
        let options = CleanerOptions::default();
        let cleaned = FlowCleaner::clean_text("Done. Um, let's move on.", &options);
        assert_eq!(cleaned, "Done. Let's move on.");
    }

    #[test]
    fn test_conservative_mode_keeps_discourse() {
        let mut options = CleanerOptions::default();
        options.aggressive_discourse = false;
        let cleaned = FlowCleaner::clean_text("It's kind of weird but okay.", &options);
        assert_eq!(cleaned, "It's kind of weird but okay.");
    }
}
