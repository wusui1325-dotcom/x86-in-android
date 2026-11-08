package com.wusper.x86.x11;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.wusper.x86.engine.NativeEngine;
import com.wusper.x86.graphics.DesktopLayout;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 進程內 X Drawable → Present。
 * <p>
 * 優先序（DRI3／零拷貝對齊）：
 * 1. {@link GPUImage} AHB → {@code nativePresentAhb}（EGL GPU_SAMPLED，無 CPU BGRA→RGBA）
 * 2. Drawable BGRA DirectBuffer → {@code nativePresentBgra}（單趟 native swizzle）
 * 3. stub
 */
public final class PresentXFramePump extends Thread {
    public interface FrameSink {
        void onXFrame(ByteBuffer rgba, int width, int height, int strideBytes);
    }

    private final XServer xServer;
    private final FrameSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object pulseLock = new Object();
    private boolean pulse;
    private int ahbHits;
    private int bgraHits;

    public PresentXFramePump(XServer xServer, FrameSink sink) {
        super("x86-x-present-pump");
        this.xServer = xServer;
        this.sink = sink;
        setDaemon(true);
    }

    public void requestPulse() {
        synchronized (pulseLock) {
            pulse = true;
            pulseLock.notifyAll();
        }
    }

    public void shutdown() {
        running.set(false);
        requestPulse();
        interrupt();
    }

    @Override
    public void run() {
        int w = DesktopLayout.WIDTH;
        int h = DesktopLayout.HEIGHT;
        NativeEngine.nativePresentCreate(w, h);
        int frames = 0;
        while (running.get()) {
            try {
                synchronized (pulseLock) {
                    if (!pulse) {
                        pulseLock.wait(33);
                    }
                    pulse = false;
                }
                if (!running.get()) continue;
                presentOnce(frames++);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                // keep pumping
            }
        }
    }

    private void presentOnce(int frameIndex) {
        Drawable drawable = null;
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        long ahbPtr = 0;
        ByteBuffer data = null;
        int strideBytes = width * 4;

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            Window root = xServer.windowManager.rootWindow;
            if (root != null) {
                for (int i = root.getChildren().size() - 1; i >= 0; i--) {
                    Window child = root.getChildren().get(i);
                    if (child != null && child.attributes.isMapped() && child.getContent() != null) {
                        drawable = child.getContent();
                        break;
                    }
                }
                if (drawable == null) {
                    drawable = root.getContent();
                }
            }
            if (drawable != null) {
                width = drawable.width;
                height = drawable.height;
                Texture tex = drawable.getTexture();
                if (tex instanceof GPUImage) {
                    GPUImage gpu = (GPUImage) tex;
                    ahbPtr = gpu.getHardwareBufferPtr();
                    if (gpu.getStride() > 0) {
                        strideBytes = gpu.getStride() * 4;
                    }
                }
                data = drawable.getData();
            }
        }

        if (ahbPtr != 0) {
            int rc = NativeEngine.nativePresentAhb(ahbPtr, width, height);
            if (rc == 0) {
                ahbHits++;
                return;
            }
            // fall through to BGRA if AHB present failed (e.g. still CPU-locked)
        }

        if (data != null) {
            ByteBuffer dup = data.duplicate();
            dup.rewind();
            // Legacy FrameSink still gets RGBA for tests；產品路徑走 nativePresentBgra
            if (sink != null) {
                ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
                byte[] tmp = new byte[4];
                int px = width * height;
                for (int i = 0; i < px; i++) {
                    dup.get(tmp);
                    rgba.put(tmp[2]);
                    rgba.put(tmp[1]);
                    rgba.put(tmp[0]);
                    rgba.put(tmp[3]);
                }
                rgba.flip();
                sink.onXFrame(rgba, width, height, width * 4);
            } else {
                NativeEngine.nativePresentBgra(dup, width, height, strideBytes);
                bgraHits++;
            }
            return;
        }

        NativeEngine.nativePresentStub(frameIndex);
    }

    /** 診斷：AHB／BGRA 命中次數（log 用） */
    public String pathStats() {
        return "ahb=" + ahbHits + " bgra=" + bgraHits
                + " egl=" + NativeEngine.nativePresentEglReady();
    }
}
