#include "present_engine.h"
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <cerrno>
#include <cstring>
#include <vector>

#define LOG_TAG "x86present"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef PFNGLEGLIMAGETARGETTEXTURE2DOESPROC
typedef void (*PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(GLenum target, GLeglImageOES image);
#endif

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

static const char* kVert = R"(attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main(){
  vUv = aUv;
  gl_Position = vec4(aPos, 0.0, 1.0);
})";

// Frag uses sampler2D + glEGLImageTargetTexture2DOES
static const char* kFrag2D = R"(precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
void main(){
  gl_FragColor = texture2D(uTex, vUv);
})";

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
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
            AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
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
    LOGI("AHB ready %dx%d stride_bytes=%d usage=CPU+GPU_SAMPLED", width_, height_, stride_);
    return 0;
}

void PresentEngine::destroyEgl() {
    if (egl_dpy_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(egl_dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (tex_) { glDeleteTextures(1, &tex_); tex_ = 0; }
        if (vbo_) { glDeleteBuffers(1, &vbo_); vbo_ = 0; }
        if (prog_) { glDeleteProgram(prog_); prog_ = 0; }
        if (egl_surf_ != EGL_NO_SURFACE) {
            eglDestroySurface(egl_dpy_, egl_surf_);
            egl_surf_ = EGL_NO_SURFACE;
        }
        if (egl_ctx_ != EGL_NO_CONTEXT) {
            eglDestroyContext(egl_dpy_, egl_ctx_);
            egl_ctx_ = EGL_NO_CONTEXT;
        }
        // do not terminate shared default display
    }
    egl_ok_ = false;
}

void PresentEngine::releaseEglWindowSurfaceForCpu() {
    if (egl_surf_ == EGL_NO_SURFACE && !egl_ok_) return;
    if (egl_dpy_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(egl_dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (egl_surf_ != EGL_NO_SURFACE) {
            eglDestroySurface(egl_dpy_, egl_surf_);
            egl_surf_ = EGL_NO_SURFACE;
            LOGI("EGL window surface released for CPU ANativeWindow_lock");
        }
    }
    // Keep display/context/shaders if any — but mark not ready until ensureEgl
    // recreates window surface. Safer: full destroy so next GPU path is clean.
    destroyEgl();
    egl_tried_ = false; // allow ensureEgl again for AHB GPU path
}

int PresentEngine::ensureEgl() {
    if (egl_ok_) {
        // recreate window surface if needed
        if (window_ && egl_surf_ == EGL_NO_SURFACE) {
            EGLint sattr[] = { EGL_NONE };
            egl_surf_ = eglCreateWindowSurface(egl_dpy_, egl_cfg_, window_, sattr);
            if (egl_surf_ == EGL_NO_SURFACE) {
                LOGW("eglCreateWindowSurface failed err=0x%x", eglGetError());
                return -1;
            }
        }
        return 0;
    }
    if (egl_tried_ && !egl_ok_) return -1;
    egl_tried_ = true;
    if (!window_) return -EINVAL;

    egl_dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl_dpy_ == EGL_NO_DISPLAY) return -1;
    EGLint maj = 0, min = 0;
    if (!eglInitialize(egl_dpy_, &maj, &min)) return -1;

    const EGLint cfgAttr[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE,
    };
    EGLint ncfg = 0;
    if (!eglChooseConfig(egl_dpy_, cfgAttr, &egl_cfg_, 1, &ncfg) || ncfg < 1) {
        LOGW("eglChooseConfig failed");
        return -1;
    }
    const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    egl_ctx_ = eglCreateContext(egl_dpy_, egl_cfg_, EGL_NO_CONTEXT, ctxAttr);
    if (egl_ctx_ == EGL_NO_CONTEXT) {
        LOGW("eglCreateContext failed");
        return -1;
    }
    EGLint sattr[] = { EGL_NONE };
    egl_surf_ = eglCreateWindowSurface(egl_dpy_, egl_cfg_, window_, sattr);
    if (egl_surf_ == EGL_NO_SURFACE) {
        LOGW("eglCreateWindowSurface failed err=0x%x", eglGetError());
        destroyEgl();
        return -1;
    }
    if (!eglMakeCurrent(egl_dpy_, egl_surf_, egl_surf_, egl_ctx_)) {
        LOGW("eglMakeCurrent failed");
        destroyEgl();
        return -1;
    }

    auto compile = [](GLenum type, const char* src) -> GLuint {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[256];
            glGetShaderInfoLog(s, sizeof(log), nullptr, log);
            LOGE("shader: %s", log);
            glDeleteShader(s);
            return 0;
        }
        return s;
    };
    GLuint vs = compile(GL_VERTEX_SHADER, kVert);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFrag2D);
    if (!vs || !fs) {
        destroyEgl();
        return -1;
    }
    prog_ = glCreateProgram();
    glAttachShader(prog_, vs);
    glAttachShader(prog_, fs);
    glLinkProgram(prog_);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint lok = 0;
    glGetProgramiv(prog_, GL_LINK_STATUS, &lok);
    if (!lok) {
        destroyEgl();
        return -1;
    }

    // fullscreen triangle strip: pos.xy uv.xy
    const float quad[] = {
            -1.f, -1.f, 0.f, 1.f,
             1.f, -1.f, 1.f, 1.f,
            -1.f,  1.f, 0.f, 0.f,
             1.f,  1.f, 1.f, 0.f,
    };
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glGenTextures(1, &tex_);
    glBindTexture(GL_TEXTURE_2D, tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    egl_ok_ = true;
    LOGI("EGL present path ready (GPU_SAMPLED AHB→Surface)");
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
    destroyEgl();
    egl_tried_ = false;
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
    // 0.1.65: do NOT create EGL window surface here — it owns the buffer queue
    // and makes ANativeWindow_lock fail for BGRA/CPU present. GPU path creates
    // EGL on demand in presentAhb / ensureEgl.
    LOGI("Surface attached cpu-first (egl deferred)");
    return 0;
}

void PresentEngine::detachSurface() {
    std::lock_guard<std::mutex> lock(mu_);
    destroyEgl();
    egl_tried_ = false;
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void PresentEngine::destroy() {
    std::lock_guard<std::mutex> lock(mu_);
    destroyEgl();
    egl_tried_ = false;
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
    if (!window_ || !ahb_) {
        last_present_rc_ = -EINVAL;
        return -EINVAL;
    }
    // 0.1.65: CPU path first — do not ensureEgl here (would steal buffer queue).
    // Optional GPU: only if already have window surface from presentAhb.
    if (egl_ok_ && egl_surf_ != EGL_NO_SURFACE) {
        int rc = presentAhbGpu(ahb_, width_, height_);
        if (rc == 0) {
            last_present_rc_ = 0;
            return 0;
        }
        releaseEglWindowSurfaceForCpu();
    } else {
        releaseEglWindowSurfaceForCpu();
    }
    void* ahbPixels = nullptr;
    int rc = AHardwareBuffer_lock(
            ahb_, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ahbPixels);
    if (rc != 0 || !ahbPixels) {
        LOGE("AHB lock read failed rc=%d", rc);
        last_present_rc_ = rc != 0 ? rc : -1;
        return last_present_rc_;
    }
    ANativeWindow_setBuffersGeometry(
            window_, width_, height_, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer winBuf{};
    int lockRc = ANativeWindow_lock(window_, &winBuf, nullptr);
    if (lockRc != 0) {
        AHardwareBuffer_unlock(ahb_, nullptr);
        lock_fail_count_++;
        last_present_rc_ = lockRc;
        LOGE("ANativeWindow_lock(AHB-CPU) failed rc=%d fails=%d", lockRc, lock_fail_count_);
        return lockRc;
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
    last_present_rc_ = 0;
    return 0;
}

int PresentEngine::presentAhbGpu(AHardwareBuffer* ahb, int width, int height) {
    if (!egl_ok_ || !ahb || egl_surf_ == EGL_NO_SURFACE) return -EINVAL;
    if (!eglMakeCurrent(egl_dpy_, egl_surf_, egl_surf_, egl_ctx_)) return -1;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClient =
            reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
                    eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    PFNEGLCREATEIMAGEKHRPROC createImage =
            reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
                    eglGetProcAddress("eglCreateImageKHR"));
    PFNEGLDESTROYIMAGEKHRPROC destroyImage =
            reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                    eglGetProcAddress("eglDestroyImageKHR"));
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetTex =
            reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
                    eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!getClient || !createImage || !destroyImage || !targetTex) {
        LOGW("EGLImage extensions missing");
        return -1;
    }

    AHardwareBuffer_acquire(ahb);
    EGLClientBuffer client = getClient(ahb);
    if (!client) {
        AHardwareBuffer_release(ahb);
        return -1;
    }
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR image = createImage(
            egl_dpy_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (!image) {
        LOGW("eglCreateImageKHR failed err=0x%x", eglGetError());
        AHardwareBuffer_release(ahb);
        return -1;
    }

    EGLint vw = width, vh = height;
    eglQuerySurface(egl_dpy_, egl_surf_, EGL_WIDTH, &vw);
    eglQuerySurface(egl_dpy_, egl_surf_, EGL_HEIGHT, &vh);
    glViewport(0, 0, vw, vh);
    glUseProgram(prog_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_);
    targetTex(GL_TEXTURE_2D, image);

    GLint aPos = glGetAttribLocation(prog_, "aPos");
    GLint aUv = glGetAttribLocation(prog_, "aUv");
    GLint uTex = glGetUniformLocation(prog_, "uTex");
    glUniform1i(uTex, 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 16, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(aUv);
    glVertexAttribPointer(aUv, 2, GL_FLOAT, GL_FALSE, 16, reinterpret_cast<void*>(8));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindTexture(GL_TEXTURE_2D, 0);
    eglSwapBuffers(egl_dpy_, egl_surf_);

    destroyImage(egl_dpy_, image);
    AHardwareBuffer_release(ahb);
    return 0;
}

int PresentEngine::presentAhbCpu(AHardwareBuffer* ahb, int width, int height) {
    if (!window_ || !ahb) return -EINVAL;
    void* pixels = nullptr;
    int rc = AHardwareBuffer_lock(
            ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &pixels);
    if (rc != 0 || !pixels) {
        // may already be locked by GPUImage — try WITHOUT usage? can't.
        return rc != 0 ? rc : -1;
    }
    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(ahb, &d);
    const int srcStride = static_cast<int>(d.stride) * 4;
    // HAL BGRA (5) or RGBA — swizzle if BGRA
    // 5 = HAL_PIXEL_FORMAT_BGRA_8888 / AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM
    const bool isBgra = (d.format == 5u);
    int out = 0;
    if (isBgra) {
        out = blitBgraToWindow(static_cast<const uint8_t*>(pixels), width, height, srcStride);
    } else {
        releaseEglWindowSurfaceForCpu();
        ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
        ANativeWindow_Buffer winBuf{};
        int lockRc = ANativeWindow_lock(window_, &winBuf, nullptr);
        if (lockRc != 0) {
            AHardwareBuffer_unlock(ahb, nullptr);
            lock_fail_count_++;
            LOGE("ANativeWindow_lock(AHB-RGBA) failed rc=%d", lockRc);
            return lockRc;
        }
        auto* dst = static_cast<uint8_t*>(winBuf.bits);
        const auto* src = static_cast<const uint8_t*>(pixels);
        const int dstStride = winBuf.stride * 4;
        const int rowBytes = width * 4;
        const int rows = height < winBuf.height ? height : winBuf.height;
        for (int y = 0; y < rows; ++y) {
            memcpy(dst + y * dstStride, src + y * srcStride, rowBytes);
        }
        ANativeWindow_unlockAndPost(window_);
        out = 0;
    }
    AHardwareBuffer_unlock(ahb, nullptr);
    return out;
}

int PresentEngine::blitBgraToWindow(
        const uint8_t* bgra, int width, int height, int strideBytes) {
    if (!window_ || !bgra || width <= 0 || height <= 0) {
        last_present_rc_ = -EINVAL;
        return -EINVAL;
    }
    // EGL window surface and ANativeWindow_lock cannot share the queue
    releaseEglWindowSurfaceForCpu();
    ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer winBuf{};
    int lockRc = ANativeWindow_lock(window_, &winBuf, nullptr);
    if (lockRc != 0) {
        lock_fail_count_++;
        last_present_rc_ = lockRc != 0 ? lockRc : -1;
        LOGE("ANativeWindow_lock(BGRA) failed rc=%d fails=%d", lockRc, lock_fail_count_);
        return last_present_rc_;
    }
    const int srcStride = strideBytes > 0 ? strideBytes : width * 4;
    const int dstStride = winBuf.stride * 4;
    auto* dst = static_cast<uint8_t*>(winBuf.bits);
    const int rows = height < winBuf.height ? height : winBuf.height;
    const int cols = width < winBuf.width ? width : winBuf.width;
    for (int y = 0; y < rows; ++y) {
        const uint8_t* s = bgra + y * srcStride;
        uint8_t* d = dst + y * dstStride;
        for (int x = 0; x < cols; ++x) {
            const int i = x * 4;
            d[i + 0] = s[i + 2]; // R
            d[i + 1] = s[i + 1]; // G
            d[i + 2] = s[i + 0]; // B
            d[i + 3] = s[i + 3]; // A
        }
    }
    ANativeWindow_unlockAndPost(window_);
    last_present_rc_ = 0;
    return 0;
}

int PresentEngine::presentAhb(AHardwareBuffer* external, int width, int height) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!external || width <= 0 || height <= 0) return -EINVAL;
    if (width_ != width || height_ != height) {
        width_ = width;
        height_ = height;
    }
    // 0.1.64: never fake-success without Surface
    if (!window_) return -EINVAL;
    // Try GPU on demand; on failure release EGL window surface before CPU lock
    if (ensureEgl() == 0) {
        int rc = presentAhbGpu(external, width, height);
        if (rc == 0) {
            last_present_rc_ = 0;
            return 0;
        }
        LOGW("presentAhbGpu failed rc=%d — falling back to CPU", rc);
    }
    releaseEglWindowSurfaceForCpu();
    int rc = presentAhbCpu(external, width, height);
    last_present_rc_ = rc;
    return rc;
}

int PresentEngine::presentBgra(const uint8_t* bgra, int width, int height, int strideBytes) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!bgra || width <= 0 || height <= 0) return -EINVAL;
    width_ = width;
    height_ = height;
    if (!window_) {
        // Stage into AHB for later attach, but return 1 so Java does NOT count presentOk
        int rc = ensureAhb(width, height);
        if (rc != 0) return rc;
        void* out = nullptr;
        rc = AHardwareBuffer_lock(
                ahb_, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &out);
        if (rc != 0 || !out) return rc != 0 ? rc : -1;
        AHardwareBuffer_Desc d{};
        AHardwareBuffer_describe(ahb_, &d);
        const int dstStride = static_cast<int>(d.stride) * 4;
        const int srcStride = strideBytes > 0 ? strideBytes : width * 4;
        auto* dst = static_cast<uint8_t*>(out);
        for (int y = 0; y < height; ++y) {
            const uint8_t* s = bgra + y * srcStride;
            uint8_t* row = dst + y * dstStride;
            for (int x = 0; x < width; ++x) {
                const int i = x * 4;
                row[i + 0] = s[i + 2];
                row[i + 1] = s[i + 1];
                row[i + 2] = s[i + 0];
                row[i + 3] = s[i + 3];
            }
        }
        AHardwareBuffer_unlock(ahb_, nullptr);
        return 1; // staged only — not posted to Surface
    }
    // Fast path: one pass into window (no intermediate AHB write)
    int rc = blitBgraToWindow(bgra, width, height, strideBytes);
    last_present_rc_ = rc;
    return rc;
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

    // 0.1.64: refuse fake success — stub/RGBA must post to Surface
    if (!window_) return -EINVAL;
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
                r = 255; g = 255; b = 255;
            }
            if (y < 28) { r = 16; g = 48; b = 32; } // greenish = DRI3/EGL build marker
            rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = 255;
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
Java_com_wusper_x86_engine_NativeEngine_nativePresentBgra(
        JNIEnv* env, jclass, jobject bgraDirect, jint width, jint height, jint stride) {
    if (bgraDirect == nullptr) return -EINVAL;
    void* ptr = env->GetDirectBufferAddress(bgraDirect);
    if (!ptr) return -EINVAL;
    jlong cap = env->GetDirectBufferCapacity(bgraDirect);
    int need = (stride > 0 ? stride : width * 4) * height;
    if (cap < need) return -EINVAL;
    return PresentEngine::instance().presentBgra(
            static_cast<const uint8_t*>(ptr), width, height, stride);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentAhb(
        JNIEnv*, jclass, jlong ahbPtr, jint width, jint height) {
    if (ahbPtr == 0) return -EINVAL;
    return PresentEngine::instance().presentAhb(
            reinterpret_cast<AHardwareBuffer*>(ahbPtr), width, height);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentEglReady(JNIEnv*, jclass) {
    return PresentEngine::instance().eglReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentLastRc(JNIEnv*, jclass) {
    return PresentEngine::instance().lastPresentRc();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativePresentLockFails(JNIEnv*, jclass) {
    return PresentEngine::instance().lockFailCount();
}
