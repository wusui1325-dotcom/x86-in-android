package com.wusper.x86.x11;

import android.util.Log;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.wusper.x86.engine.NativeEngine;
import com.wusper.x86.graphics.DesktopLayout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 進程內 X Drawable → Present。
 * <p>
 * 優先序（DRI3／零拷貝對齊）：
 * 1. {@link GPUImage} AHB → {@code nativePresentAhb}（EGL GPU_SAMPLED，無 CPU BGRA→RGBA）
 * 2. Drawable BGRA DirectBuffer → {@code nativePresentBgra}（單趟 native swizzle）
 * 3. stub-fallback（attach 後仍無像素時，證明 Surface 通路）
 */
public final class PresentXFramePump extends Thread {
    private static final String TAG = "PresentXFramePump";

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
    private int stubHits;
    private int waitingAttach;
    private int presentOk;
    private int presentFail;
    private int lastW;
    private int lastH;
    private int lastChildId;
    private int emptyAfterReady;
    private String lastStatsLogged = "";

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
        // Attach 前 skip 重活；nativePresentAhb／Bgra 在無 window_ 時會假成功
        if (!NativeEngine.nativePresentReady()) {
            waitingAttach++;
            if ((frameIndex % 30) == 0) {
                logDiag(frameIndex, "waitingAttach");
            }
            return;
        }

        Drawable drawable = null;
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        long ahbPtr = 0;
        ByteBuffer data = null;
        int strideBytes = width * 4;
        int childId = 0;
        int kids = 0;

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            Window root = xServer.windowManager.rootWindow;
            if (root != null) {
                kids = root.getChildren() != null ? root.getChildren().size() : 0;
                Pick best = pickLargestMapped(root, /*includeSelf=*/false);
                if (best == null) {
                    // fall back to root content
                    best = pickFromWindow(root);
                }
                if (best != null) {
                    drawable = best.drawable;
                    childId = best.id;
                    width = best.width;
                    height = best.height;
                }
            }
            if (drawable != null) {
                // Best-effort promote child (and root) to GPUImage for AHB path
                Texture tex = drawable.getTexture();
                if (!(tex instanceof GPUImage)) {
                    try {
                        GPUImage.createOrObtain(xServer, drawable, true, true);
                        tex = drawable.getTexture();
                    } catch (Throwable ignored) {
                        // GL queueEvent may not be ready; BGRA path still works
                    }
                }
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

        lastW = width;
        lastH = height;
        lastChildId = childId;

        if (ahbPtr != 0) {
            int rc = NativeEngine.nativePresentAhb(ahbPtr, width, height);
            if (rc == 0) {
                ahbHits++;
                presentOk++;
                emptyAfterReady = 0;
                maybeLog(frameIndex, kids);
                return;
            }
            presentFail++;
            // fall through to BGRA if AHB present failed
        }

        if (data != null && width > 1 && height > 1) {
            ByteBuffer dup = data.duplicate();
            dup.rewind();
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
                bgraHits++;
                presentOk++;
                emptyAfterReady = 0;
                maybeLog(frameIndex, kids);
                return;
            } else {
                int rc = NativeEngine.nativePresentBgra(dup, width, height, strideBytes);
                if (rc == 0) {
                    bgraHits++;
                    presentOk++;
                    emptyAfterReady = 0;
                    maybeLog(frameIndex, kids);
                    return;
                }
                presentFail++;
            }
        }

        // No usable pixel data after attach — stub so Surface isn't pure black
        emptyAfterReady++;
        // After ~2s (~60 frames @33ms) or immediately if we have mapped kids with empty content
        if (emptyAfterReady >= 60 || (kids > 0 && emptyAfterReady >= 15)) {
            int rc = NativeEngine.nativePresentStub(frameIndex);
            stubHits++;
            if (rc == 0) {
                presentOk++;
            } else {
                presentFail++;
            }
            if ((frameIndex % 30) == 0 || emptyAfterReady == 60 || emptyAfterReady == 15) {
                logDiag(frameIndex, "stub-fallback kids=" + kids);
            }
            return;
        }
        maybeLog(frameIndex, kids);
    }

    private void maybeLog(int frameIndex, int kids) {
        if ((frameIndex % 30) == 0) {
            logDiag(frameIndex, "kids=" + kids);
        }
    }

    private void logDiag(int frameIndex, String extra) {
        String stats = pathStats() + " frame=" + frameIndex + " " + extra;
        if (!stats.equals(lastStatsLogged)) {
            Log.i(TAG, stats);
            lastStatsLogged = stats;
        }
    }

    /** 選面積最大的 mapped 子視窗（遞迴；略過 1×1／無 content）。 */
    private static Pick pickLargestMapped(Window root, boolean includeSelf) {
        Pick best = includeSelf ? pickFromWindow(root) : null;
        List<Window> children = root.getChildren();
        if (children == null) return best;
        // Prefer larger area; scan all (not only top of z-order)
        for (int i = 0; i < children.size(); i++) {
            Window child = children.get(i);
            if (child == null || !child.attributes.isMapped()) continue;
            Pick direct = pickFromWindow(child);
            if (direct != null && (best == null || direct.area > best.area)) {
                best = direct;
            }
            // Recurse into nested Wine desktop children
            Pick nested = pickLargestMapped(child, /*includeSelf=*/false);
            if (nested != null && (best == null || nested.area > best.area)) {
                best = nested;
            }
        }
        return best;
    }

    private static Pick pickFromWindow(Window w) {
        if (w == null || !w.attributes.isMapped()) return null;
        Drawable d = w.getContent();
        if (d == null) return null;
        int ww = d.width & 0xffff;
        int hh = d.height & 0xffff;
        if (ww <= 1 || hh <= 1) return null;
        return new Pick(d, w.id, ww * hh, ww, hh);
    }

    private static final class Pick {
        final Drawable drawable;
        final int id;
        final int area;
        final int width;
        final int height;

        Pick(Drawable drawable, int id, int area, int width, int height) {
            this.drawable = drawable;
            this.id = id;
            this.area = area;
            this.width = width;
            this.height = height;
        }
    }

    /** 診斷：AHB／BGRA／stub 命中與 attach 狀態（log／Session PRESENT_DIAG） */
    public String pathStats() {
        return "ahb=" + ahbHits
                + " bgra=" + bgraHits
                + " stub=" + stubHits
                + " ok=" + presentOk
                + " fail=" + presentFail
                + " waitAttach=" + waitingAttach
                + " last=" + lastW + "x" + lastH
                + " child=0x" + Integer.toHexString(lastChildId)
                + " empty=" + emptyAfterReady
                + " ready=" + NativeEngine.nativePresentReady()
                + " egl=" + NativeEngine.nativePresentEglReady();
    }

    public int presentOkCount() {
        return presentOk;
    }

    public boolean hasPresented() {
        return presentOk > 0;
    }
}
