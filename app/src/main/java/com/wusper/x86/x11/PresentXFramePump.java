package com.wusper.x86.x11;

import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.wusper.x86.engine.NativeEngine;
import com.wusper.x86.graphics.DesktopLayout;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 從進程內 X 的 root／頂層 mapped window Drawable 取像素 → Present（AHB）。
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
        // ensure present engine sized
        NativeEngine.nativePresentCreate(w, h);
        int frames = 0;
        while (running.get()) {
            try {
                boolean doFrame;
                synchronized (pulseLock) {
                    if (!pulse) {
                        pulseLock.wait(33);
                    }
                    doFrame = true;
                    pulse = false;
                }
                if (!doFrame || !running.get()) continue;
                presentOnce(frames++);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                // keep pumping
            }
        }
    }

    private void presentOnce(int frameIndex) {
        ByteBuffer data = null;
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            Window root = xServer.windowManager.rootWindow;
            Drawable drawable = null;
            // prefer frontmost mapped child with content
            if (root != null) {
                for (int i = root.getChildren().size() - 1; i >= 0; i--) {
                    Window child = root.getChildren().get(i);
                    if (child != null && child.attributes.isMapped() && child.getContent() != null) {
                        drawable = child.getContent();
                        width = drawable.width;
                        height = drawable.height;
                        break;
                    }
                }
                if (drawable == null) {
                    drawable = root.getContent();
                    if (drawable != null) {
                        width = drawable.width;
                        height = drawable.height;
                    }
                }
            }
            if (drawable != null) {
                data = drawable.getData();
            }
        }
        if (data != null) {
            ByteBuffer dup = data.duplicate();
            dup.rewind();
            // Drawable is typically BGRA little-endian; Present expects RGBA.
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            byte[] tmp = new byte[4];
            for (int i = 0; i < width * height; i++) {
                dup.get(tmp); // B,G,R,A
                rgba.put(tmp[2]);
                rgba.put(tmp[1]);
                rgba.put(tmp[0]);
                rgba.put(tmp[3]);
            }
            rgba.flip();
            if (sink != null) {
                sink.onXFrame(rgba, width, height, width * 4);
            } else {
                NativeEngine.nativePresentRgba(rgba, width, height, width * 4);
            }
        } else {
            NativeEngine.nativePresentStub(frameIndex);
        }
    }
}
