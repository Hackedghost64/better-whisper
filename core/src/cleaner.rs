use lazy_static::lazy_static;
use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WordToken {
    pub text: String,
    pub start: f32,
    pub end: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanerOptions {
    pub remove_fillers: bool,
    pub deduplicate_stutters: bool,
    pub handle_self_corrections: bool,
    pub context_before_cursor: Option<String>,
}

impl Default for CleanerOptions {
    fn default() -> Self {
        Self {
            remove_fillers: true,
            deduplicate_stutters: true,
            handle_self_corrections: true,
            context_before_cursor: None,
        }
    }
}

lazy_static! {
    // Matches fillers optionally surrounded by commas or pauses: e.g. ", uh,", "uh,", "um,"
    static ref UNCONDITIONAL_FILLERS: Regex = Regex::new(
        r"(?i)(?:,\s*)?\b(um|uh|er|ah|hmm|hm)\b(?:\s*,)?"
    ).unwrap();

    static ref PHRASE_FILLERS: Regex = Regex::new(
        r"(?i)(?:,\s*)?\b(you know|i mean|sort of|kind of)\b(?:\s*,)?"
    ).unwrap();

    static ref SCRATCH_THAT: Regex = Regex::new(
        r"(?i)\b(scratch that|never mind|delete that)\b"
    ).unwrap();

    static ref NO_WAIT: Regex = Regex::new(
        r"(?i)\b(no wait|sorry i meant|or rather|actually)\s+"
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

        // 1. Handle explicit self-correction commands like "scratch that"
        if options.handle_self_corrections {
            text = Self::apply_self_corrections(&text);
        }

        // 2. Remove unconditional fillers (um, uh, er, ah, hmm)
        if options.remove_fillers {
            text = UNCONDITIONAL_FILLERS.replace_all(&text, " ").to_string();
            text = PHRASE_FILLERS.replace_all(&text, " ").to_string();
        }

        // 3. Stutter deduplication ("I I", "the the", "we need to we need to")
        if options.deduplicate_stutters {
            text = Self::deduplicate_stutters(&text);
        }

        // 4. Clean up spaces and punctuation anomalies caused by stripping
        text = Self::normalize_whitespace_and_punctuation(&text);

        // 5. Apply context-aware capitalization and spacing
        Self::apply_context(&text, options.context_before_cursor.as_deref())
    }

    /// Handles self-corrections like "scratch that" or "no wait [correction]"
    fn apply_self_corrections(input: &str) -> String {
        if let Some(mat) = SCRATCH_THAT.find(input) {
            let after = &input[mat.end()..].trim();
            return after.to_string();
        }

        if let Some(mat) = NO_WAIT.find(input) {
            let before = &input[..mat.start()].trim_end();
            let after = &input[mat.end()..].trim_start();
            let mut before_words: Vec<&str> = before.split_whitespace().collect();
            let after_words: Vec<&str> = after.split_whitespace().collect();
            // Match the number of tokens being replaced by the correction phrase
            let to_pop = after_words.len().min(before_words.len()).max(1);
            for _ in 0..to_pop {
                before_words.pop();
            }
            if before_words.is_empty() {
                return after.to_string();
            } else {
                return format!("{} {}", before_words.join(" "), after);
            }
        }

        input.to_string()
    }

    /// Deduplicates stutters for 1-word, 2-word, and 3-word repeated phrases.
    fn deduplicate_stutters(input: &str) -> String {
        let words: Vec<&str> = input.split_whitespace().collect();
        if words.is_empty() {
            return String::new();
        }

        let mut cleaned: Vec<&str> = Vec::new();
        let mut i = 0;

        let clean_word = |w: &str| -> String {
            w.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase()
        };

        while i < words.len() {
            // 3-word phrase stutter check ("we need to we need to" -> "we need to")
            if i + 5 < words.len() {
                let p1 = [clean_word(words[i]), clean_word(words[i+1]), clean_word(words[i+2])];
                let p2 = [clean_word(words[i+3]), clean_word(words[i+4]), clean_word(words[i+5])];
                if p1[0] == p2[0] && p1[1] == p2[1] && p1[2] == p2[2] && !p1[0].is_empty() {
                    i += 3;
                    continue;
                }
            }

            // 2-word phrase stutter check ("to do to do" -> "to do")
            if i + 3 < words.len() {
                let p1 = [clean_word(words[i]), clean_word(words[i+1])];
                let p2 = [clean_word(words[i+2]), clean_word(words[i+3])];
                if p1[0] == p2[0] && p1[1] == p2[1] && !p1[0].is_empty() {
                    i += 2;
                    continue;
                }
            }

            // 1-word stutter check ("I I" -> "I")
            if i + 1 < words.len() {
                let current_stripped = clean_word(words[i]);
                let next_stripped = clean_word(words[i + 1]);
                if !current_stripped.is_empty() && current_stripped == next_stripped {
                    i += 1;
                    continue;
                }
            }

            cleaned.push(words[i]);
            i += 1;
        }

        cleaned.join(" ")
    }

    /// Normalizes spaces around punctuation that may be left after removing fillers.
    fn normalize_whitespace_and_punctuation(input: &str) -> String {
        let mut s = input.replace("  ", " ");
        while s.contains("  ") {
            s = s.replace("  ", " ");
        }

        // Clean up punctuation artifacts
        s = s.replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?");
        s = s.replace(",,", ",").replace(",.", ".").replace("!.", "!").replace("?.", "?");

        // Trim leading punctuation that may result from dropping a leading filler
        s = s.trim_start_matches(|c: char| c == ',' || c == ';' || c == ' ' || c == '.').to_string();

        s.trim().to_string()
    }

    /// Applies smart capitalization and spacing based on the cursor context.
    fn apply_context(text: &str, context_before_cursor: Option<&str>) -> String {
        if text.is_empty() {
            return String::new();
        }

        let context = match context_before_cursor {
            Some(c) => c,
            None => {
                return Self::capitalize_first_char(text);
            }
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

        // Determine if we need to prefix a space
        if !context.is_empty() && !context.ends_with(' ') && !context.ends_with('\n') {
            result = format!(" {}", result);
        }

        result
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
}
