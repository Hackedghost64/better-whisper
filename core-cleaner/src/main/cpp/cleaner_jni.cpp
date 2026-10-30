#include <jni.h>
#include <string>

extern "C" {
    char* better_whisper_clean_text(
        const char* input,
        const char* context_before_cursor,
        bool remove_fillers,
        bool deduplicate_stutters,
        bool handle_self_corrections
    );
    void better_whisper_free_string(char* ptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_betterwhisper_core_cleaner_TranscriptCleaner_nativeClean(
    JNIEnv* env,
    jobject /* this */,
    jstring raw_text_jstr,
    jstring context_jstr,
    jboolean remove_fillers,
    jboolean dedup_stutters,
    jboolean handle_corrections
) {
    if (!raw_text_jstr) {
        return env->NewStringUTF("");
    }

    const char* raw_text = env->GetStringUTFChars(raw_text_jstr, nullptr);
    const char* context = context_jstr ? env->GetStringUTFChars(context_jstr, nullptr) : nullptr;

    char* cleaned = better_whisper_clean_text(
        raw_text,
        context,
        remove_fillers,
        dedup_stutters,
        handle_corrections
    );

    jstring result = env->NewStringUTF(cleaned ? cleaned : raw_text);

    if (cleaned) {
        better_whisper_free_string(cleaned);
    }
    if (context) {
        env->ReleaseStringUTFChars(context_jstr, context);
    }
    env->ReleaseStringUTFChars(raw_text_jstr, raw_text);

    return result;
}
