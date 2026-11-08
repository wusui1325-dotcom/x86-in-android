#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <mutex>
#include <cstring>
#include <cerrno>

#define LOG_TAG "x86aaudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/**
 * Guest ALSA（android_aserver）→ 宿主 AAudio 最短通路。
 * 對齊 Winlator ALSA server 協議；播放改 AAudio（非 AudioTrack）。
 */
class AAudioSink {
public:
    static AAudioSink& instance() {
        static AAudioSink s;
        return s;
    }

    int prepare(int sampleRate, int channelCount, int pcmEncoding /* 0=U8 1=S16 2=FLOAT */, int bufferFrames) {
        std::lock_guard<std::mutex> lock(mu_);
        closeLocked();
        if (sampleRate <= 0 || channelCount <= 0 || bufferFrames <= 0) return -EINVAL;

        aaudio_format_t fmt = AAUDIO_FORMAT_PCM_I16;
        bytesPerSample_ = 2;
        if (pcmEncoding == 0) {
            // AAudio 無 U8：升為 S16 時由 Java 轉；此處拒 U8 用 S16 路徑
            fmt = AAUDIO_FORMAT_PCM_I16;
            bytesPerSample_ = 2;
        } else if (pcmEncoding == 2) {
            fmt = AAUDIO_FORMAT_PCM_FLOAT;
            bytesPerSample_ = 4;
        }

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t r = AAudio_createStreamBuilder(&builder);
        if (r != AAUDIO_OK || !builder) return (int)r;

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate);
        AAudioStreamBuilder_setChannelCount(builder, channelCount);
        AAudioStreamBuilder_setFormat(builder, fmt);
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, bufferFrames * 2);

        r = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (r != AAUDIO_OK || !stream_) {
            LOGE("openStream failed %d", (int)r);
            stream_ = nullptr;
            return (int)r;
        }

        sampleRate_ = sampleRate;
        channels_ = channelCount;
        bufferFrames_ = bufferFrames;
        framesWritten_ = 0;
        LOGI("AAudio ready rate=%d ch=%d frames=%d", sampleRate, channelCount, bufferFrames);
        return 0;
    }

    int start() {
        std::lock_guard<std::mutex> lock(mu_);
        if (!stream_) return -EINVAL;
        return (int)AAudioStream_requestStart(stream_);
    }

    int stop() {
        std::lock_guard<std::mutex> lock(mu_);
        if (!stream_) return 0;
        AAudioStream_requestStop(stream_);
        return 0;
    }

    int pause() {
        std::lock_guard<std::mutex> lock(mu_);
        if (!stream_) return 0;
        AAudioStream_requestPause(stream_);
        return 0;
    }

    int write(const void* data, int nbytes) {
        std::lock_guard<std::mutex> lock(mu_);
        if (!stream_ || !data || nbytes <= 0) return -EINVAL;
        int frameSize = bytesPerSample_ * channels_;
        if (frameSize <= 0) return -EINVAL;
        int32_t frames = nbytes / frameSize;
        // timeout 100ms
        aaudio_result_t r = AAudioStream_write(stream_, data, frames, 100'000'000);
        if (r < 0) return (int)r;
        framesWritten_ += r;
        return r * frameSize;
    }

    int framesPlayed() const {
        return (int)framesWritten_;
    }

    void release() {
        std::lock_guard<std::mutex> lock(mu_);
        closeLocked();
    }

private:
    void closeLocked() {
        if (stream_) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
        sampleRate_ = channels_ = bufferFrames_ = bytesPerSample_ = 0;
        framesWritten_ = 0;
    }

    std::mutex mu_;
    AAudioStream* stream_ = nullptr;
    int sampleRate_ = 0;
    int channels_ = 0;
    int bufferFrames_ = 0;
    int bytesPerSample_ = 2;
    int64_t framesWritten_ = 0;
};

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioPrepare(
        JNIEnv*, jclass, jint sampleRate, jint channels, jint pcmEncoding, jint bufferFrames) {
    return AAudioSink::instance().prepare(sampleRate, channels, pcmEncoding, bufferFrames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioStart(JNIEnv*, jclass) {
    return AAudioSink::instance().start();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioStop(JNIEnv*, jclass) {
    return AAudioSink::instance().stop();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioPause(JNIEnv*, jclass) {
    return AAudioSink::instance().pause();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioWrite(
        JNIEnv* env, jclass, jobject directBuf, jint nbytes) {
    if (!directBuf) return -EINVAL;
    void* ptr = env->GetDirectBufferAddress(directBuf);
    if (!ptr) return -EINVAL;
    return AAudioSink::instance().write(ptr, nbytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioPointer(JNIEnv*, jclass) {
    return AAudioSink::instance().framesPlayed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_alsaserver_ALSAClient_nativeAAudioRelease(JNIEnv*, jclass) {
    AAudioSink::instance().release();
}
