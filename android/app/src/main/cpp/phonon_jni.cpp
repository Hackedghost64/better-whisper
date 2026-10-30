#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "../../../../../models/android-ndk/phonon.h"

#define TAG "PhononJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Declared in better_whisper_core (Rust C-ABI)
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

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_betterwhisper_engine_PhononEngine_nativeInitModel(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path_jstr
) {
    const char* model_path = env->GetStringUTFChars(model_path_jstr, nullptr);
    LOGI("Initializing Phonon-2 model from %s", model_path);

    PhononModel* model = phonon_init(model_path);
    env->ReleaseStringUTFChars(model_path_jstr, model_path);

    if (!model) {
        LOGE("Failed to initialize Phonon-2 model!");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jstring JNICALL
Java_com_betterwhisper_engine_PhononEngine_nativeTranscribe(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle,
    jfloatArray pcm_samples_jarr,
    jint num_samples,
    jstring context_before_cursor_jstr
) {
    PhononModel* model = reinterpret_cast<PhononModel*>(model_handle);
    if (!model) {
        LOGE("Invalid model handle passed to nativeTranscribe");
        return env->NewStringUTF("");
    }

    jfloat* samples = env->GetFloatArrayElements(pcm_samples_jarr, nullptr);
    if (!samples) {
        return env->NewStringUTF("");
    }

    // 1. Run ultra-fast Phonon-2 speech-to-text inference
    PhononResult* raw_result = phonon_transcribe(model, samples, static_cast<size_t>(num_samples));
    env->ReleaseFloatArrayElements(pcm_samples_jarr, samples, JNI_ABORT);

    if (!raw_result || !raw_result->full_transcript) {
        if (raw_result) phonon_free_result(raw_result);
        return env->NewStringUTF("");
    }

    const char* raw_text = raw_result->full_transcript;
    const char* context = nullptr;
    if (context_before_cursor_jstr != nullptr) {
        context = env->GetStringUTFChars(context_before_cursor_jstr, nullptr);
    }

    // 2. Run Wispr Flow post-processing cleaner (<2 ms: removes "um", "uh", stutters, formats context)
    char* cleaned_text = better_whisper_clean_text(
        raw_text,
        context,
        true, // remove_fillers
        true, // deduplicate_stutters
        true  // handle_self_corrections
    );

    jstring output_jstr = env->NewStringUTF(cleaned_text ? cleaned_text : raw_text);

    // 3. Free resources
    if (cleaned_text) {
        better_whisper_free_string(cleaned_text);
    }
    if (context) {
        env->ReleaseStringUTFChars(context_before_cursor_jstr, context);
    }
    phonon_free_result(raw_result);

    return output_jstr;
}

JNIEXPORT void JNICALL
Java_com_betterwhisper_engine_PhononEngine_nativeDestroyModel(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong model_handle
) {
    PhononModel* model = reinterpret_cast<PhononModel*>(model_handle);
    if (model) {
        LOGI("Destroying Phonon-2 model handle");
        phonon_destroy(model);
    }
}

} // extern "C"
