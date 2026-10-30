#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "phonon.h"

#define TAG "PhononTranscribeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeLoad(
    JNIEnv* env,
    jobject /* this */,
    jstring model_dir_jstr
) {
    if (!model_dir_jstr) return 0;
    const char* model_dir = env->GetStringUTFChars(model_dir_jstr, nullptr);
    LOGI("Loading Phonon-2 model from %s", model_dir);

    phonon_options opt = PHONON_OPTIONS_INIT;
    opt.n_threads = 4; // Capped for performance cores on mobile (Snapdragon 8 Gen 2)
    opt.cpu_policy = PHONON_CPU_AUTO;
    phonon_model* model = phonon_load(model_dir, &opt);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir);

    if (!model) {
        LOGE("Failed to load phonon model: %s", phonon_last_error());
        return 0;
    }
    LOGI("Phonon-2 model loaded successfully: %s", phonon_describe(model));
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeFree(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong model_handle
) {
    auto* model = reinterpret_cast<phonon_model*>(model_handle);
    if (model) {
        phonon_free(model);
    }
}

JNIEXPORT jstring JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeTranscribeBuffer(
    JNIEnv* env,
    jobject /* this */,
    jlong model_handle,
    jfloatArray pcm_jarr
) {
    auto* model = reinterpret_cast<phonon_model*>(model_handle);
    if (!model || !pcm_jarr) {
        LOGE("nativeTranscribeBuffer called with null model or pcm");
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(pcm_jarr);
    if (len < 1600) { // Less than 0.1s
        LOGI("Audio buffer too short (%d samples), skipping", len);
        return env->NewStringUTF("");
    }

    jfloat* pcm = env->GetFloatArrayElements(pcm_jarr, nullptr);

    phonon_result* result = nullptr;
    int rc = phonon_transcribe(model, pcm, static_cast<size_t>(len), PHONON_PCM_F32, 16000, 1, &result);
    env->ReleaseFloatArrayElements(pcm_jarr, pcm, JNI_ABORT);

    if (rc != PHONON_OK || !result) {
        LOGE("phonon_transcribe error (%d): %s", rc, phonon_last_error());
        if (result) phonon_result_free(result);
        return env->NewStringUTF("");
    }

    const char* text = result->text ? result->text : "";
    LOGI("Transcribed %.2fs audio in %.3fs -> \"%s\"", result->audio_s, result->decode_s, text);

    jstring out_jstr = env->NewStringUTF(text);
    phonon_result_free(result);
    return out_jstr;
}

} // extern "C"
