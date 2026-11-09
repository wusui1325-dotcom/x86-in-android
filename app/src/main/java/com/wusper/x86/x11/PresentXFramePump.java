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
 * 0.1.64: only count presentOk when native returns 0 with Surface attached
 * ({@link NativeEngine#nativePresentReady()} == window_). Nearly-black frames get a
 * bright 8px HUD bar so Boss can see Present works.
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
    private int nonBlackHits;
    private int hudHits;
    private int lastW;
    private int lastH;
    private int lastChildId;
    private int emptyAfterReady;
    private String lastStatsLogged = "";
    private ByteBuffer hudScratch;

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
        // ready() == window_ attached (0.1.64)
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
                Texture tex = drawable.getTexture();
                if (!(tex instanceof GPUImage)) {
                    try {
                        GPUImage.createOrObtain(xServer, drawable, true, true);
                        tex = drawable.getTexture();
                    } catch (Throwable ignored) {
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
                boolean black = data == null || isNearlyBlack(data, width, height, strideBytes);
                if (!black) {
                    nonBlackHits++;
                } else {
                    // Prove Surface path with HUD when AHB frame is empty/black
                    presentHudBar(width, height, frameIndex);
                }
                maybeLog(frameIndex, kids);
                return;
            }
            presentFail++;
        }

        if (data != null && width > 1 && height > 1) {
            ByteBuffer dup = data.duplicate();
            dup.rewind();
            boolean black = isNearlyBlack(dup, width, height, strideBytes);
            dup.rewind();
            if (!black) {
                nonBlackHits++;
            } else {
                paintHudBarBgra(dup, width, height, strideBytes);
                dup.rewind();
            }
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
                    if (black) hudHits++;
                    maybeLog(frameIndex, kids);
                    return;
                }
                presentFail++;
            }
        }

        emptyAfterReady++;
        if (emptyAfterReady >= 60 || (kids > 0 && emptyAfterReady >= 15)) {
            // Prefer HUD bar over full stub rainbow when we have a size
            if (presentHudBar(width > 1 ? width : DesktopLayout.WIDTH,
                    height > 1 ? height : DesktopLayout.HEIGHT, frameIndex)) {
                stubHits++;
                presentOk++;
            } else {
                int rc = NativeEngine.nativePresentStub(frameIndex);
                stubHits++;
                if (rc == 0) presentOk++;
                else presentFail++;
            }
            if ((frameIndex % 30) == 0 || emptyAfterReady == 60 || emptyAfterReady == 15) {
                logDiag(frameIndex, "stub-fallback kids=" + kids);
            }
            return;
        }
        maybeLog(frameIndex, kids);
    }

    /** Bright 8px bar at top — proves ANativeWindow post works even if guest is black. */
    private boolean presentHudBar(int width, int height, int frameIndex) {
        if (width <= 1 || height <= 1) {
            width = DesktopLayout.WIDTH;
            height = DesktopLayout.HEIGHT;
        }
        int need = width * height * 4;
        if (hudScratch == null || hudScratch.capacity() < need) {
            hudScratch = ByteBuffer.allocateDirect(need);
        }
        hudScratch.clear();
        byte[] row = new byte[width * 4];
        // cyan/magenta pulse so motion is obvious
        boolean pulse = ((frameIndex / 15) & 1) == 0;
        byte r = (byte) (pulse ? 0 : 255);
        byte g = (byte) (pulse ? 255 : 0);
        byte b = (byte) 255;
        for (int x = 0; x < width; x++) {
            int i = x * 4;
            row[i] = b;     // BGRA
            row[i + 1] = g;
            row[i + 2] = r;
            row[i + 3] = (byte) 255;
        }
        byte[] black = new byte[width * 4];
        for (int y = 0; y < height; y++) {
            if (y < 8) {
                hudScratch.put(row);
            } else {
                hudScratch.put(black);
            }
        }
        hudScratch.flip();
        int rc = NativeEngine.nativePresentBgra(hudScratch, width, height, width * 4);
        if (rc == 0) {
            hudHits++;
            return true;
        }
        return false;
    }

    private static void paintHudBarBgra(ByteBuffer bgra, int width, int height, int strideBytes) {
        int stride = strideBytes > 0 ? strideBytes : width * 4;
        boolean pulse = (System.currentTimeMillis() / 500) % 2 == 0;
        byte r = (byte) (pulse ? 0 : 255);
        byte g = (byte) (pulse ? 255 : 0);
        byte b = (byte) 255;
        byte[] pix = new byte[]{b, g, r, (byte) 255};
        int rows = Math.min(8, height);
        for (int y = 0; y < rows; y++) {
            int rowOff = y * stride;
            for (int x = 0; x < width; x++) {
                int off = rowOff + x * 4;
                if (off + 3 < bgra.capacity()) {
                    bgra.position(off);
                    bgra.put(pix);
                }
            }
        }
        bgra.rewind();
    }

    /** Sample a grid of pixels; true if almost everything is near-black. */
    private static boolean isNearlyBlack(ByteBuffer data, int width, int height, int strideBytes) {
        if (data == null || width <= 0 || height <= 0) return true;
        ByteBuffer d = data.duplicate();
        int stride = strideBytes > 0 ? strideBytes : width * 4;
        int lit = 0;
        int samples = 0;
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 12);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int off = y * stride + x * 4;
                if (off + 3 >= d.capacity()) continue;
                d.position(off);
                int b = d.get() & 0xff;
                int g = d.get() & 0xff;
                int r = d.get() & 0xff;
                samples++;
                if (r > 24 || g > 24 || b > 24) lit++;
            }
        }
        if (samples == 0) return true;
        // nearly black if <2% lit samples
        return lit * 50 < samples;
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

    private static Pick pickLargestMapped(Window root, boolean includeSelf) {
        Pick best = includeSelf ? pickFromWindow(root) : null;
        List<Window> children = root.getChildren();
        if (children == null) return best;
        for (int i = 0; i < children.size(); i++) {
            Window child = children.get(i);
            if (child == null || !child.attributes.isMapped()) continue;
            Pick direct = pickFromWindow(child);
            if (direct != null && (best == null || direct.area > best.area)) {
                best = direct;
            }
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

    public String pathStats() {
        return "ahb=" + ahbHits
                + " bgra=" + bgraHits
                + " stub=" + stubHits
                + " ok=" + presentOk
                + " fail=" + presentFail
                + " nonBlack=" + nonBlackHits
                + " hud=" + hudHits
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
