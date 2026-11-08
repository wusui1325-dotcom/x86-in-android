#include "present_engine.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <cerrno>
#include <cstring>
#include <vector>

#define LOG_TAG "x86present"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

PresentEngine& PresentEngine::instance() {
    static PresentEngine e;
    return e;
}

int PresentEngine::ensureAhb(int width, int height) {
    if (ahb_ && width_ == width && height_ == height) return 0;
    if (ahb_) {
        AHardwareBuffer_release(ahb_);
        ahb_ = nullptr;
    }
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage =
            AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    int rc = AHardwareBuffer_allocate(&desc, &ahb_);
    if (rc != 0 || !ahb_) {
        LOGE("AHardwareBuffer_allocate failed rc=%d", rc);
        ahb_ = nullptr;
        return rc != 0 ? rc : -1;
    }
    AHardwareBuffer_Desc out{};
    AHardwareBuffer_describe(ahb_, &out);
    width_ = width;
    height_ = height;
    stride_ = static_cast<int>(out.stride) * 4;
    if (stride_ < width * 4) stride_ = width * 4;
    LOGI("AHB ready %dx%d stride_bytes=%d", width_, height_, stride_);
    return 0;
}

int PresentEngine::create(int width, int height) {
    std::lock_guard<std::mutex> lock(mu_);
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return -EINVAL;
    return ensureAhb(width, height);
}

int PresentEngine::attachSurface(JNIEnv* env, jobject surface) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!surface) return -EINVAL;
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    window_ = ANativeWindow_fromSurface(env, surface);
    if (!window_) {
        LOGE("ANativeWindow_fromSurface failed");
        return -1;
    }
    if (width_ > 0 && height_ > 0) {
        ANativeWindow_setBuffersGeometry(
                window_, width_, height_, WINDOW_FORMAT_RGBA_8888);
    }
    LOGI("Surface attached");
    return 0;
}

void PresentEngine::detachSurface() {
    std::lock_guard<std::mutex> lock(mu_);
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void PresentEngine::destroy() {
    std::lock_guard<std::mutex> lock(mu_);
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (ahb_) {
        AHardwareBuffer_release(ahb_);
        ahb_ = nullptr;
    }
    width_ = height_ = stride_ = 0;
}

int PresentEngine::postAhbToWindow() {
    if (!window_ || !ahb_) return -EINVAL;

    void* ahbPixels = nullptr;
    int rc = AHardwareBuffer_lock(
            ahb_, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ahbPixels);
    if (rc != 0 || !ahbPixels) {
        LOGE("AHB lock read failed rc=%d", rc);
        return rc != 0 ? rc : -1;
    }

    ANativeWindow_setBuffersGeometry(
            window_, width_, height_, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer winBuf{};
    if (ANativeWindow_lock(window_, &winBuf, nullptr) != 0) {
        AHardwareBuffer_unlock(ahb_, nullptr);
        LOGE("ANativeWindow_lock failed");
        return -1;
    }

    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(ahb_, &d);
    const int srcStride = static_cast<int>(d.stride) * 4;
    const int dstStride = winBuf.stride * 4;
    const int rowBytes = width_ * 4;
    auto* dst = static_cast<uint8_t*>(winBuf.bits);
    const auto* src = static_cast<const uint8_t*>(ahbPixels);
    const int rows = height_ < winBuf.height ? height_ : winBuf.height;
    for (int y = 0; y < rows; ++y) {
        memcpy(dst + y * dstStride, src + y * srcStride, rowBytes);
    }
    AHardwareBuffer_unlock(ahb_, nullptr);
    ANativeWindow_unlockAndPost(window_);
    return 0;
}

int PresentEngine::presentRgba(const uint8_t* rgba, int width, int height, int strideBytes) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!rgba || width <= 0 || height <= 0) return -EINVAL;
    int rc = ensureAhb(width, height);
    if (rc != 0) return rc;

    void* out = nullptr;
    rc = AHardwareBuffer_lock(
            ahb_, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &out);
    if (rc != 0 || !out) {
        LOGE("AHB lock write failed rc=%d", rc);
        return rc != 0 ? rc : -1;
    }
    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(ahb_, &d);
    const int dstStride = static_cast<int>(d.stride) * 4;
    const int srcStride = strideBytes > 0 ? strideBytes : width * 4;
    const int rowBytes = width * 4;
    auto* dst = static_cast<uint8_t*>(out);
    for (int y = 0; y < height; ++y) {
        memcpy(dst + y * dstStride, rgba + y * srcStride, rowBytes);
    }
    AHardwareBuffer_unlock(ahb_, nullptr);

    if (!window_) return 0;
    return postAhbToWindow();
}

int PresentEngine::presentStub(int frameIndex) {
    int w;
    int h;
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (width_ <= 0 || height_ <= 0) {
            int rc = ensureAhb(1280, 720);
            if (rc != 0) return rc;
        }
        w = width_;
        h = height_;
    }
    std::vector<uint8_t> rgba(static_cast<size_t>(w) * static_cast<size_t>(h) * 4u);
    const int band = (frameIndex * 4) % (w > 8 ? w - 8 : 1);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            size_t i = (static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)) * 4u;
            uint8_t r = static_cast<uint8_t>((x * 255) / (w > 1 ? w : 1));
            uint8_t g = static_cast<uint8_t>((y * 255) / (h > 1 ? h : 1));
            uint8_t b = static_cast<uint8_t>((frameIndex * 5) & 0xff);
            if (x >= band && x < band + 8) {
                r = 255;
                g = 255;
                b = 255;
            }
            // 頂部狀態條：深藍底
            if (y < 28) {
                r = 16;
                g = 24;
                b = 48;
            }
            rgba[i] = r;
            rgba[i + 1] = g;
            rgba[i + 2] = b;
            rgba[i + 3] = 255;
        }
    }
    return presentRgba(rgba.data(), w, h, w * 4);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentCreate(
        JNIEnv*, jclass, jint width, jint height) {
    return PresentEngine::instance().create(width, height);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentAttachSurface(
        JNIEnv* env, jclass, jobject surface) {
    return PresentEngine::instance().attachSurface(env, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentDetach(JNIEnv*, jclass) {
    PresentEngine::instance().detachSurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentDestroy(JNIEnv*, jclass) {
    PresentEngine::instance().destroy();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentRgba(
        JNIEnv* env, jclass, jobject rgbaDirect, jint width, jint height, jint stride) {
    if (rgbaDirect == nullptr) return -EINVAL;
    void* ptr = env->GetDirectBufferAddress(rgbaDirect);
    if (!ptr) return -EINVAL;
    jlong cap = env->GetDirectBufferCapacity(rgbaDirect);
    int need = (stride > 0 ? stride : width * 4) * height;
    if (cap < need) return -EINVAL;
    return PresentEngine::instance().presentRgba(
            static_cast<const uint8_t*>(ptr), width, height, stride);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentStub(
        JNIEnv*, jclass, jint frameIndex) {
    return PresentEngine::instance().presentStub(frameIndex);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentReady(JNIEnv*, jclass) {
    return PresentEngine::instance().ready() ? JNI_TRUE : JNI_FALSE;
}
