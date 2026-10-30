# Better Whisper release rules.

# JNI entry points are resolved by name at runtime, so the classes that declare
# native methods must survive shrinking/obfuscation unchanged.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.betterwhisper.core.cleaner.TranscriptCleaner { *; }
-keep class com.betterwhisper.core.transcribe.PhononTranscribeEngine { *; }

# The accessibility service is referenced from the manifest and from Settings.
-keep class com.betterwhisper.feature.bubble.FlowAccessibilityService { *; }

# Kotlin coroutines / Compose ship their own consumer rules; this keeps stack
# traces readable without disabling obfuscation for the whole app.
-keepattributes SourceFile,LineNumberTable
