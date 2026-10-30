use regex::Regex;
use std::collections::HashMap;
use std::sync::OnceLock;

#[derive(Debug, Clone)]
struct CompiledDictionary {
    /// (trigger regex, expansion), longest triggers first.
    snippets: Vec<(Regex, String)>,
    /// (word regex, replacement), longest triggers first.
    replacements: Vec<(Regex, String)>,
}

/// Personal vocabulary and custom text snippet dictionary.
/// Allows users to specify custom casing/acronyms (e.g. "Kubernetes", "PostgreSQL", "Phonon-2")
/// as well as voice triggers that expand into text snippets (e.g. "insert calendly" -> URL).
///
/// Regexes are compiled once and cached: `apply` used to rebuild ten regexes on
/// every dictation, which was the dominant cost of the whole cleaner.
#[derive(Debug, Default)]
pub struct CustomDictionary {
    /// Exact word/phrase mappings (case-insensitive key -> formatted replacement)
    pub replacements: HashMap<String, String>,
    /// Voice shortcuts / snippets
    pub snippets: HashMap<String, String>,
    compiled: OnceLock<CompiledDictionary>,
}

impl Clone for CustomDictionary {
    fn clone(&self) -> Self {
        Self {
            replacements: self.replacements.clone(),
            snippets: self.snippets.clone(),
            compiled: OnceLock::new(),
        }
    }
}

impl CustomDictionary {
    pub fn new() -> Self {
        let mut dict = Self::default();
        // Add common tech defaults
        dict.add_replacement("phonon 2", "Phonon-2");
        dict.add_replacement("phonon-2", "Phonon-2");
        dict.add_replacement("whisper flow", "Wispr Flow");
        dict.add_replacement("github", "GitHub");
        dict.add_replacement("ios", "iOS");
        dict.add_replacement("core ml", "CoreML");
        dict.add_replacement("coreml", "CoreML");
        dict.add_replacement("macos", "macOS");
        dict.add_replacement("postgresql", "PostgreSQL");
        dict.add_replacement("sqlite", "SQLite");
        dict
    }

    pub fn add_replacement(&mut self, from: &str, to: &str) {
        self.replacements.insert(from.to_lowercase(), to.to_string());
        self.compiled = OnceLock::new();
    }

    pub fn add_snippet(&mut self, voice_trigger: &str, expansion: &str) {
        self.snippets.insert(voice_trigger.to_lowercase(), expansion.to_string());
        self.compiled = OnceLock::new();
    }

    /// Applies custom replacements and voice expansions across the text.
    pub fn apply(&self, input: &str) -> String {
        let compiled = self.compiled.get_or_init(|| self.build());
        let mut text = input.to_string();

        for (re, expansion) in &compiled.snippets {
            text = re.replace_all(&text, expansion.as_str()).to_string();
        }
        for (re, replacement) in &compiled.replacements {
            text = re.replace_all(&text, replacement.as_str()).to_string();
        }
        text
    }

    fn build(&self) -> CompiledDictionary {
        let build_entries = |entries: &HashMap<String, String>| {
            let mut compiled: Vec<(Regex, String)> = entries
                .iter()
                .filter_map(|(from, to)| {
                    let pattern = format!(r"(?i)\b{}\b", regex::escape(from));
                    Regex::new(&pattern).ok().map(|re| (re, to.clone()))
                })
                .collect();
            // Longest phrase first so "phonon 2" wins over "phonon".
            compiled.sort_by(|a, b| b.0.as_str().len().cmp(&a.0.as_str().len()));
            compiled
        };

        CompiledDictionary {
            snippets: build_entries(&self.snippets),
            replacements: build_entries(&self.replacements),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dictionary_replacements() {
        let dict = CustomDictionary::new();
        let input = "I am testing phonon 2 against whisper flow on ios and macos.";
        let output = dict.apply(input);
        assert_eq!(output, "I am testing Phonon-2 against Wispr Flow on iOS and macOS.");
    }

    #[test]
    fn test_snippet_expansion() {
        let mut dict = CustomDictionary::new();
        dict.add_snippet("insert my email", "user@example.com");
        let input = "Please reach out at insert my email for updates.";
        let output = dict.apply(input);
        assert_eq!(output, "Please reach out at user@example.com for updates.");
    }

    #[test]
    fn test_apply_is_repeatable() {
        let dict = CustomDictionary::new();
        assert_eq!(dict.apply("github"), "GitHub");
        assert_eq!(dict.apply("github"), "GitHub");
    }

    #[test]
    fn test_add_after_compilation_invalidates_cache() {
        let mut dict = CustomDictionary::new();
        assert_eq!(dict.apply("kubernetes"), "kubernetes");
        dict.add_replacement("kubernetes", "Kubernetes");
        assert_eq!(dict.apply("kubernetes"), "Kubernetes");
    }
}
