// whisper.cpp JNI bridge. WAV (16kHz mono s16le) to SRT

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstring>
#include <thread>
#include <algorithm>
#include <atomic>
#include <new>

#include "whisper.h"

#define LOG_TAG "subflow_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Set from Kotlin when the user cancels. whisper checks it before every ggml graph
// (encode and both decode paths), so an abort lands within a graph, not at the end of
// the file. Without it a cancelled transcription kept a core busy for hours while the
// UI already said "cancelled".
static std::atomic<bool> g_abort{false};

static bool abort_requested(void * /*user_data*/) {
    return g_abort.load(std::memory_order_relaxed);
}

enum class WavStatus { Ok, Unreadable, TooLong };

// minimal WAV reader for the pcm_s16le 16kHz mono ffmpeg produces.
// max_samples bounds the float buffer: it is the only allocation here that scales with
// the length of the film, and nothing above this layer can survive it failing.
static WavStatus read_wav_pcm16_mono(const char *path, std::vector<float> &pcm, size_t max_samples) {
    FILE *f = fopen(path, "rb");
    if (!f) return WavStatus::Unreadable;

    char riff[4], wave[4];
    uint32_t chunk_size;
    if (fread(riff, 1, 4, f) != 4 || memcmp(riff, "RIFF", 4) != 0) { fclose(f); return WavStatus::Unreadable; }
    if (fread(&chunk_size, 4, 1, f) != 1) { fclose(f); return WavStatus::Unreadable; }
    if (fread(wave, 1, 4, f) != 4 || memcmp(wave, "WAVE", 4) != 0) { fclose(f); return WavStatus::Unreadable; }

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
            if (fread(&fmt, 2, 1, f) != 1 || fread(&channels, 2, 1, f) != 1 ||
                fread(&rate, 4, 1, f) != 1 || fread(&byte_rate, 4, 1, f) != 1 ||
                fread(&align, 2, 1, f) != 1 || fread(&bits, 2, 1, f) != 1) {
                fclose(f);
                return WavStatus::Unreadable;
            }
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
    if (data_size == 0 || bits != 16 || channels < 1) { fclose(f); return WavStatus::Unreadable; }

    const size_t total_samples = data_size / 2;
    const size_t frames = total_samples / channels;
    // refuse before allocating anything: the header alone tells us how big this gets
    if (frames > max_samples) {
        fclose(f);
        LOGE("audio too long: %zu frames, budget %zu", frames, max_samples);
        return WavStatus::TooLong;
    }

    pcm.resize(frames);

    // convert in blocks. staging the whole int16 payload first would double the peak,
    // which is exactly the allocation that used to fail on a feature-length film.
    const size_t block_frames = 1u << 16;
    std::vector<int16_t> block(block_frames * channels);
    size_t written = 0;
    while (written < frames) {
        if (g_abort.load(std::memory_order_relaxed)) { fclose(f); return WavStatus::Unreadable; }
        const size_t want = std::min(block_frames, frames - written);
        const size_t got = fread(block.data(), sizeof(int16_t) * channels, want, f);
        if (got == 0) break;
        for (size_t i = 0; i < got; i++) {
            int32_t acc = 0;
            for (int c = 0; c < channels; c++) acc += block[i * channels + c];
            pcm[written + i] = (float) (acc / channels) / 32768.0f;
        }
        written += got;
    }
    fclose(f);
    if (written < frames) pcm.resize(written); // short read, keep what is really there
    return pcm.empty() ? WavStatus::Unreadable : WavStatus::Ok;
}

static std::string ms_to_srt_time(int64_t ms) {
    char buf[32];
    const int64_t h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, mil = ms % 1000;
    snprintf(buf, sizeof(buf), "%02lld:%02lld:%02lld,%03lld",
             (long long) h, (long long) m, (long long) s, (long long) mil);
    return buf;
}

extern "C" JNIEXPORT void JNICALL
Java_com_subflow_pipeline_WhisperEngine_nativeRequestAbort(JNIEnv *, jclass) {
    g_abort.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_subflow_pipeline_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass /*clazz*/, jstring jModelPath, jstring jWavPath, jint jMaxSamples) {

    // whole body guarded: a std::bad_alloc crossing the JNI boundary terminates the
    // process outright, so PipelineRunner's catch(Throwable) never gets to see it and
    // the search can never be marked FAILED.
    whisper_context *ctx = nullptr;
    try {
        g_abort.store(false, std::memory_order_relaxed);

        const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
        const char *wav_path = env->GetStringUTFChars(jWavPath, nullptr);
        if (model_path == nullptr || wav_path == nullptr) {
            if (model_path) env->ReleaseStringUTFChars(jModelPath, model_path);
            if (wav_path) env->ReleaseStringUTFChars(jWavPath, wav_path);
            return nullptr;
        }

        const size_t max_samples = jMaxSamples > 0 ? (size_t) jMaxSamples : 0;
        std::vector<float> pcm;
        const WavStatus status = read_wav_pcm16_mono(wav_path, pcm, max_samples);
        env->ReleaseStringUTFChars(jWavPath, wav_path);
        if (status != WavStatus::Ok) {
            env->ReleaseStringUTFChars(jModelPath, model_path);
            return nullptr;
        }
        LOGI("WAV loaded: %.1f s", pcm.size() / 16000.0f);

        whisper_context_params cparams = whisper_context_default_params();
        ctx = whisper_init_from_file_with_params(model_path, cparams);
        env->ReleaseStringUTFChars(jModelPath, model_path);
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
        // cancellation reaches the native side here, and nowhere else
        params.abort_callback = abort_requested;
        params.abort_callback_user_data = nullptr;

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
            LOGE("whisper_full failed (aborted=%d)", (int) g_abort.load(std::memory_order_relaxed));
        }

        whisper_free(ctx);
        ctx = nullptr;
        // a partial transcript from an aborted run is not a result
        if (srt.empty() || g_abort.load(std::memory_order_relaxed)) return nullptr;
        // NewStringUTF wants Modified-UTF-8 but whisper can emit 4-byte UTF-8
        // (emoji) that trips CheckJNI and aborts. return raw bytes, Kotlin decodes as UTF-8
        const jsize len = (jsize) srt.size();
        jbyteArray out = env->NewByteArray(len);
        if (out == nullptr) return nullptr;
        env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(srt.data()));
        return out;
    } catch (const std::bad_alloc &) {
        LOGE("out of memory during transcription");
        if (ctx) whisper_free(ctx);
        return nullptr;
    } catch (const std::exception &e) {
        LOGE("transcription failed: %s", e.what());
        if (ctx) whisper_free(ctx);
        return nullptr;
    } catch (...) {
        LOGE("transcription failed: unknown error");
        if (ctx) whisper_free(ctx);
        return nullptr;
    }
}
