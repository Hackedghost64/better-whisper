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
    opt.n_threads = 4; // Capped for big.LITTLE performance cores on mobile
    phonon_model* model = phonon_load(model_dir, &opt);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir);

    if (!model) {
        LOGE("Failed to load phonon model: %s", phonon_last_error());
        return 0;
    }
    LOGI("Phonon-2 model loaded successfully");
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

JNIEXPORT jlong JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeStreamOpen(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong model_handle
) {
    auto* model = reinterpret_cast<phonon_model*>(model_handle);
    if (!model) return 0;

    phonon_stream* stream = phonon_stream_open(model);
    return reinterpret_cast<jlong>(stream);
}

JNIEXPORT jobject JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeStreamFeed(
    JNIEnv* env,
    jobject /* this */,
    jlong stream_handle,
    jfloatArray pcm_jarr
) {
    auto* stream = reinterpret_cast<phonon_stream*>(stream_handle);
    if (!stream || !pcm_jarr) return nullptr;

    jsize len = env->GetArrayLength(pcm_jarr);
    jfloat* pcm = env->GetFloatArrayElements(pcm_jarr, nullptr);

    const phonon_event* events = nullptr;
    int n_events = 0;
    int rc = phonon_stream_feed(stream, pcm, len, PHONON_PCM_F32, 16000, 1, &events, &n_events);
    env->ReleaseFloatArrayElements(pcm_jarr, pcm, JNI_ABORT);

    if (rc != PHONON_OK || n_events <= 0 || !events) {
        return nullptr;
    }

    // Return the latest event
    const auto& ev = events[n_events - 1];

    jclass cls = env->FindClass("com/betterwhisper/core/transcribe/StreamEvent");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return nullptr;

    jstring text_jstr = env->NewStringUTF(ev.text ? ev.text : "");
    return env->NewObject(cls, ctor, ev.kind, text_jstr);
}

JNIEXPORT jstring JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeStreamClose(
    JNIEnv* env,
    jobject /* this */,
    jlong stream_handle
) {
    auto* stream = reinterpret_cast<phonon_stream*>(stream_handle);
    if (!stream) return env->NewStringUTF("");

    const phonon_event* events = nullptr;
    int n_events = 0;
    phonon_stream_close(stream, &events, &n_events);

    const char* full_text = phonon_stream_transcript(stream);
    return env->NewStringUTF(full_text ? full_text : "");
}

JNIEXPORT void JNICALL
Java_com_betterwhisper_core_transcribe_PhononTranscribeEngine_nativeStreamFree(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong stream_handle
) {
    auto* stream = reinterpret_cast<phonon_stream*>(stream_handle);
    if (stream) {
        phonon_stream_free(stream);
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
    if (!model || !pcm_jarr) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(pcm_jarr);
    jfloat* pcm = env->GetFloatArrayElements(pcm_jarr, nullptr);

    phonon_result* result = nullptr;
    int rc = phonon_transcribe(model, pcm, len, PHONON_PCM_F32, 16000, 1, &result);
    env->ReleaseFloatArrayElements(pcm_jarr, pcm, JNI_ABORT);

    if (rc != PHONON_OK || !result || !result->text) {
        if (result) phonon_result_free(result);
        return env->NewStringUTF("");
    }

    jstring out_jstr = env->NewStringUTF(result->text);
    phonon_result_free(result);
    return out_jstr;
}

} // extern "C"
