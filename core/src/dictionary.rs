use std::collections::HashMap;

/// Personal vocabulary and custom text snippet dictionary.
/// Allows users to specify custom casing/acronyms (e.g. "Kubernetes", "PostgreSQL", "Phonon-2")
/// as well as voice triggers that expand into text snippets (e.g. "insert calendly" -> URL).
#[derive(Debug, Clone, Default)]
pub struct CustomDictionary {
    /// Exact word/phrase mappings (case-insensitive key -> formatted replacement)
    pub replacements: HashMap<String, String>,
    /// Voice shortcuts / snippets
    pub snippets: HashMap<String, String>,
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
    }

    pub fn add_snippet(&mut self, voice_trigger: &str, expansion: &str) {
        self.snippets.insert(voice_trigger.to_lowercase(), expansion.to_string());
    }

    /// Applies custom replacements and voice expansions across the text.
    pub fn apply(&self, input: &str) -> String {
        let mut text = input.to_string();

        // 1. Voice snippet expansions (e.g., "insert my email")
        for (trigger, expansion) in &self.snippets {
            let re_pattern = format!(r"(?i)\b{}\b", regex::escape(trigger));
            if let Ok(re) = regex::Regex::new(&re_pattern) {
                text = re.replace_all(&text, expansion.as_str()).to_string();
            }
        }

        // 2. Custom vocabulary casing & acronyms
        for (from, to) in &self.replacements {
            let re_pattern = format!(r"(?i)\b{}\b", regex::escape(from));
            if let Ok(re) = regex::Regex::new(&re_pattern) {
                text = re.replace_all(&text, to.as_str()).to_string();
            }
        }

        text
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
}
