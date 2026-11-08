#pragma once
#include <jni.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <cstdint>
#include <mutex>

/**
 * GpuStack Present v0（對齊 Termux-X11／Winlator 思路的最短自研版）：
 * 正規緩衝 = AHardwareBuffer；輸出 = ANativeWindow（Session Surface）。
 * 幀來源：RGBA 記憶體／present.shm／（過渡）XWD feeder。
 */
class PresentEngine {
public:
    static PresentEngine& instance();

    int create(int width, int height);
    int attachSurface(JNIEnv* env, jobject surface);
    void detachSurface();
    void destroy();

    /** 將 RGBA8888 寫入 AHB 並 post 到 Surface。size >= stride*height */
    int presentRgba(const uint8_t* rgba, int width, int height, int strideBytes);

    /** 產生可測 stub 幀（色條＋序號）並 present */
    int presentStub(int frameIndex);

    int width() const { return width_; }
    int height() const { return height_; }
    bool ready() const { return ahb_ != nullptr; }
    bool hasWindow() const { return window_ != nullptr; }

private:
    PresentEngine() = default;
    int ensureAhb(int width, int height);
    int postAhbToWindow();

    std::mutex mu_;
    AHardwareBuffer* ahb_ = nullptr;
    ANativeWindow* window_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    int stride_ = 0;
};

extern "C" {
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentCreate(
        JNIEnv*, jclass, jint width, jint height);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentAttachSurface(
        JNIEnv*, jclass, jobject surface);
JNIEXPORT void JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentDetach(
        JNIEnv*, jclass);
JNIEXPORT void JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentDestroy(
        JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentRgba(
        JNIEnv*, jclass, jobject rgbaDirect, jint width, jint height, jint stride);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentStub(
        JNIEnv*, jclass, jint frameIndex);
JNIEXPORT jboolean JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentReady(
        JNIEnv*, jclass);
}
