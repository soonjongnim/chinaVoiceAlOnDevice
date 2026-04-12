#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include "sherpa-onnx/c-api/c-api.h"

#define TAG "SherpaOnnxNative"

// --- Global Engine Handles ---
static const SherpaOnnxOfflineRecognizer *recognizer = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_stt_MainActivity_initModel(JNIEnv *env, jobject thiz, jstring modelDir) {
    if (recognizer) return; 

    const char *dirStr = env->GetStringUTFChars(modelDir, nullptr);
    std::string dir = dirStr;
    env->ReleaseStringUTFChars(modelDir, dirStr);

    // 1. STT Recognizer 초기화 (Sherpa-Onnx C-API 사용)
    std::string encoder = dir + "/encoder-epoch-20-avg-1.onnx";
    std::string decoder = dir + "/decoder-epoch-20-avg-1.onnx";
    std::string joiner = dir + "/joiner-epoch-20-avg-1.onnx";
    std::string tokens = dir + "/tokens.txt";

    SherpaOnnxOfflineRecognizerConfig config;
    memset(&config, 0, sizeof(config));
    config.model_config.transducer.encoder = encoder.c_str();
    config.model_config.transducer.decoder = decoder.c_str();
    config.model_config.transducer.joiner = joiner.c_str();
    config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = 4;
    config.model_config.debug = 1;

    recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
    if (recognizer) __android_log_print(ANDROID_LOG_DEBUG, TAG, "STT Engine Initialized");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_stt_MainActivity_recognize(JNIEnv *env, jobject thiz, jfloatArray audioData) {
    if (!recognizer) return env->NewStringUTF("STT Model not initialized");

    jsize len = env->GetArrayLength(audioData);
    jfloat *data = env->GetFloatArrayElements(audioData, 0);

    const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(recognizer);
    SherpaOnnxAcceptWaveformOffline(stream, 16000, data, len);
    SherpaOnnxDecodeOfflineStream(recognizer, stream);

    const SherpaOnnxOfflineRecognizerResult *result = SherpaOnnxGetOfflineStreamResult(stream);
    jstring text = env->NewStringUTF(result->text);

    SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);
    env->ReleaseFloatArrayElements(audioData, data, 0);

    return text;
}

