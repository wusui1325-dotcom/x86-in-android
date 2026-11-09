#pragma once
#include <jni.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <cstdint>
#include <mutex>

/**
 * GpuStack Present（DRI3／Vortek 對齊最短版）：
 * - 正規緩衝 = AHardwareBuffer（含 GPU_SAMPLED）
 * - 優先：外部 AHB（Winlator GPUImage／DRI3）→ EGLImage → Surface（無 CPU BGRA→RGBA）
 * - 其次：BGRA DirectByteBuffer → 單趟 swizzle 進 ANativeWindow
 * - 保底：RGBA／stub
 */
class PresentEngine {
public:
    static PresentEngine& instance();

    int create(int width, int height);
    int attachSurface(JNIEnv* env, jobject surface);
    void detachSurface();
    void destroy();

    int presentRgba(const uint8_t* rgba, int width, int height, int strideBytes);
    /** BGRA8888（Drawable／GPUImage virtualData）→ 單趟進 Surface */
    int presentBgra(const uint8_t* bgra, int width, int height, int strideBytes);
    /** 外部 AHB（不釋放呼叫端；本函式 acquire／release）→ EGL 或 CPU 讀出 */
    int presentAhb(AHardwareBuffer* external, int width, int height);
    int presentStub(int frameIndex);

    int width() const { return width_; }
    int height() const { return height_; }
    /** 0.1.64: ready = Surface attached (window_). AHB alone is NOT ready. */
    bool ready() const { return window_ != nullptr; }
    bool hasWindow() const { return window_ != nullptr; }
    bool eglReady() const { return egl_ok_ && egl_surf_ != EGL_NO_SURFACE; }
    int lastPresentRc() const { return last_present_rc_; }
    int lockFailCount() const { return lock_fail_count_; }
    /** 0.1.66: "ahb_cpu" | "ahb_gpu" | "" */
    const char* lastPresentPath() const { return last_present_path_; }

private:
    PresentEngine() = default;
    int ensureAhb(int width, int height);
    int postAhbToWindow();
    int ensureEgl();
    void destroyEgl();
    /** 0.1.65: drop EGL window surface so ANativeWindow_lock can own the queue */
    void releaseEglWindowSurfaceForCpu();
    int presentAhbGpu(AHardwareBuffer* ahb, int width, int height);
    int presentAhbCpu(AHardwareBuffer* ahb, int width, int height);
    int blitBgraToWindow(const uint8_t* bgra, int width, int height, int strideBytes);

    std::mutex mu_;
    AHardwareBuffer* ahb_ = nullptr;
    ANativeWindow* window_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    int stride_ = 0;
    int last_present_rc_ = 0;
    int lock_fail_count_ = 0;
    const char* last_present_path_ = "";

    EGLDisplay egl_dpy_ = EGL_NO_DISPLAY;
    EGLContext egl_ctx_ = EGL_NO_CONTEXT;
    EGLSurface egl_surf_ = EGL_NO_SURFACE;
    EGLConfig egl_cfg_ = nullptr;
    GLuint prog_ = 0;
    GLuint vbo_ = 0;
    GLuint tex_ = 0;
    bool egl_ok_ = false;
    bool egl_tried_ = false;
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
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentBgra(
        JNIEnv*, jclass, jobject bgraDirect, jint width, jint height, jint stride);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentAhb(
        JNIEnv*, jclass, jlong ahbPtr, jint width, jint height);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentStub(
        JNIEnv*, jclass, jint frameIndex);
JNIEXPORT jboolean JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentReady(
        JNIEnv*, jclass);
JNIEXPORT jboolean JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentEglReady(
        JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentLastRc(
        JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentLockFails(
        JNIEnv*, jclass);
JNIEXPORT jstring JNICALL Java_com_wusper_x86_engine_NativeEngine_nativePresentLastPath(
        JNIEnv*, jclass);
}
