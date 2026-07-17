// whisper.cpp JNI bridge. WAV (16kHz mono s16le) to SRT

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstring>
#include <thread>
#include <algorithm>

#include "whisper.h"

#define LOG_TAG "subflow_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// minimal WAV reader for the pcm_s16le 16kHz mono ffmpeg produces
static bool read_wav_pcm16_mono(const char *path, std::vector<float> &pcm) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;

    char riff[4], wave[4];
    uint32_t chunk_size;
    if (fread(riff, 1, 4, f) != 4 || memcmp(riff, "RIFF", 4) != 0) { fclose(f); return false; }
    fread(&chunk_size, 4, 1, f);
    if (fread(wave, 1, 4, f) != 4 || memcmp(wave, "WAVE", 4) != 0) { fclose(f); return false; }

    uint16_t channels = 1, bits = 16;
    uint32_t data_size = 0;
    // scan chunks for fmt and data
    while (true) {
        char id[4];
        uint32_t size;
        if (fread(id, 1, 4, f) != 4 || fread(&size, 4, 1, f) != 1) break;
        if (memcmp(id, "fmt ", 4) == 0) {
            uint16_t fmt;
            uint32_t rate, byte_rate;
            uint16_t align;
            fread(&fmt, 2, 1, f);
            fread(&channels, 2, 1, f);
            fread(&rate, 4, 1, f);
            fread(&byte_rate, 4, 1, f);
            fread(&align, 2, 1, f);
            fread(&bits, 2, 1, f);
            // skip remaining fmt bytes plus the pad byte
            if (size > 16) fseek(f, (long) (size - 16), SEEK_CUR);
            if (size & 1) fseek(f, 1, SEEK_CUR);
        } else if (memcmp(id, "data", 4) == 0) {
            data_size = size;
            break;
        } else {
            // odd-sized chunks have a pad byte. skip it or "data" is never found
            fseek(f, (long) (size + (size & 1)), SEEK_CUR);
        }
    }
    if (data_size == 0 || bits != 16 || channels < 1) { fclose(f); return false; }

    const size_t n = data_size / 2;
    std::vector<int16_t> raw(n);
    const size_t got = fread(raw.data(), 2, n, f);
    fclose(f);

    const size_t frames = got / channels;
    pcm.resize(frames);
    for (size_t i = 0; i < frames; i++) {
        // average channels if multi-channel (ffmpeg already outputs mono)
        int32_t acc = 0;
        for (int c = 0; c < channels; c++) acc += raw[i * channels + c];
        pcm[i] = (float) (acc / channels) / 32768.0f;
    }
    return !pcm.empty();
}

static std::string ms_to_srt_time(int64_t ms) {
    char buf[32];
    const int64_t h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, mil = ms % 1000;
    snprintf(buf, sizeof(buf), "%02lld:%02lld:%02lld,%03lld",
             (long long) h, (long long) m, (long long) s, (long long) mil);
    return buf;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_subflow_pipeline_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass /*clazz*/, jstring jModelPath, jstring jWavPath) {

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    const char *wav_path = env->GetStringUTFChars(jWavPath, nullptr);

    std::vector<float> pcm;
    if (!read_wav_pcm16_mono(wav_path, pcm)) {
        LOGE("failed to read WAV: %s", wav_path);
        env->ReleaseStringUTFChars(jModelPath, model_path);
        env->ReleaseStringUTFChars(jWavPath, wav_path);
        return nullptr;
    }
    LOGI("WAV loaded: %.1f s", pcm.size() / 16000.0f);

    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);
    env->ReleaseStringUTFChars(jWavPath, wav_path);
    if (!ctx) {
        LOGE("failed to load model");
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = nullptr;      // auto detect
    params.translate = false;       // keep source language, translation happens in the pipeline
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.no_context = true;
    params.n_threads = std::max(2, std::min(4, (int) std::thread::hardware_concurrency()));

    std::string srt;
    if (whisper_full(ctx, params, pcm.data(), (int) pcm.size()) == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; i++) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            // whisper t0/t1 are in 10ms units
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            srt += std::to_string(i + 1) + "\n";
            srt += ms_to_srt_time(t0) + " --> " + ms_to_srt_time(t1) + "\n";
            const char *trimmed = text;
            while (*trimmed == ' ') trimmed++;
            srt += trimmed;
            srt += "\n\n";
        }
    } else {
        LOGE("whisper_full failed");
    }

    whisper_free(ctx);
    if (srt.empty()) return nullptr;
    // NewStringUTF wants Modified-UTF-8 but whisper can emit 4-byte UTF-8
    // (emoji) that trips CheckJNI and aborts. return raw bytes, Kotlin decodes as UTF-8
    const jsize len = (jsize) srt.size();
    jbyteArray out = env->NewByteArray(len);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(srt.data()));
    return out;
}
