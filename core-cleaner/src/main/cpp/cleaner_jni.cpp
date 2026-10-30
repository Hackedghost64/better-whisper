#include <jni.h>
#include <cstdint>
#include <string>

extern "C" {
    char* better_whisper_clean_text(
        const char* input,
        const char* context_before_cursor,
        int32_t remove_fillers,
        int32_t deduplicate_stutters,
        int32_t handle_self_corrections,
        int32_t aggressive_discourse
    );
    void better_whisper_free_string(char* ptr);
}

namespace {

jstring empty_string(JNIEnv* env) {
    return env->NewStringUTF("");
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_betterwhisper_core_cleaner_TranscriptCleaner_nativeClean(
    JNIEnv* env,
    jobject /* this */,
    jstring raw_text_jstr,
    jstring context_jstr,
    jboolean remove_fillers,
    jboolean dedup_stutters,
    jboolean handle_corrections,
    jboolean aggressive_discourse
) {
    if (raw_text_jstr == nullptr) {
        return empty_string(env);
    }

    const char* raw_text = env->GetStringUTFChars(raw_text_jstr, nullptr);
    if (raw_text == nullptr) {
        // OOM: GetStringUTFChars already raised OutOfMemoryError.
        return nullptr;
    }

    const char* context = nullptr;
    if (context_jstr != nullptr) {
        context = env->GetStringUTFChars(context_jstr, nullptr);
        if (context == nullptr) {
            env->ReleaseStringUTFChars(raw_text_jstr, raw_text);
            return nullptr;
        }
    }

    char* cleaned = better_whisper_clean_text(
        raw_text,
        context,
        remove_fillers ? 1 : 0,
        dedup_stutters ? 1 : 0,
        handle_corrections ? 1 : 0,
        aggressive_discourse ? 1 : 0
    );

    // Fall back to the untouched transcript rather than losing the utterance.
    const char* out = cleaned ? cleaned : raw_text;
    jstring result = empty_string(env);
    if (out != nullptr) {
        result = env->NewStringUTF(out);
    }

    if (cleaned) {
        better_whisper_free_string(cleaned);
    }
    if (context) {
        env->ReleaseStringUTFChars(context_jstr, context);
    }
    env->ReleaseStringUTFChars(raw_text_jstr, raw_text);

    return result;
}
