package com.wusper.x86.x11;

import android.util.Log;

import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.events.Expose;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.RenderExtension;
import com.winlator.xserver.requests.DrawRequests;
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
 * 0.1.66: AHB CPU-first skips presentAhbCpu (GPUImage lock); true CPU = BGRA(virtualData).
 * DIAG path=ahb_skip|bgra|hud|stub. HUD never mutates guest SHM.
 * 0.1.67: prefer application / non-desktop mapped window over explorer desktop
 * (largest-area desktop was hiding real app content). DIAG desktopOnly/mappedKids/wmClass.
 * 0.1.68: sticky guestBlack=1 when presentOk rises but guest stays nearly-black / HUD-only
 * for N frames (DXVK/Turnip black-screen observability; not a Present lock fail).
 * 0.1.69: isNearlyBlack excludes DXVK HUD band (y<64 + BL corner); sticky clears on
 * guestLit ({@code guestBlack=0 recovered}); AHB-no-data → guestSample=none;
 * pathStats adds guestSample= / nonBlackGuest=.
 * 0.1.70: do NOT promote software Drawable→GPUImage (wipes GDI pixels). When AHB has no
 * CPU mapping or guestBlack sticky, forceGpu→ahb_gpu so DRI3/DXVK pixels can land.
 * 0.1.74: prefer windows tagged with {@code _NET_WM_HWND} (real Wine HWND) over area-only
 * app children so Present tracks the DXGI swapchain window; DIAG {@code hwnd=}.
 * 0.1.75: require {@code MapState.VIEWABLE}; if HWND exists but not viewable/zero-geom,
 * wait (no desktop steal) before first present; DIAG {@code mapState=} {@code mapWait=}.
 * 0.1.83: DIAG {@code ahbCpuAccess=} from GPUImage.getVirtualData()!=null (PresentExtension
 * now attaches cpuAccess=false to match DRI3; first-attach race fixed).
 * 0.1.85: forceGpu also when {@code lastAhbCpuAccess==0} (GPU-only AHB with stale non-null
 * software {@code data} was waiting for guestBlack sticky → ahb_skip/BGRA black). Log once
 * {@code forceGpu=ahb_gpu reason=ahbCpuAccess0}. Not Present CPU-first flip.
 * 0.1.112: Dig111 black — App HWND/Xid mapped+SET_DRAWABLE but Present stuck on empty
 * explorer root ({@code child=0x3 mappedKids=0 desktopOnly=1}). Knives: SparseArray scan +
 * force-map unmapped App HWND; never treat explorer as bestApp; retarget Present to App
 * drawable (GDI BGRA) so Boss sees installer dialog pixels.
 * 0.1.115 Dig114: exclude rundll32; hwnd←xid fallback; forceMap+Expose so Wine
 * x11drv GDI blit (PutImage/CopyArea/SHM) hits Present-sampled drawable; DIAG
 * x11_putimage=/x11_copyarea=/x11_shm_putimage=.
 * 0.1.116 Dig115: RENDER stamp x11_render=; after live hwnd composite App tree
 * onto 1280×720 BGRA (never child=0x3 empty root ahb_gpu); ban presentHudBar
 * after live hwnd; surface test pattern (not HUD) for P1-T.
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
    private int lastMappedKids;
    private int lastDesktopOnly; // 1 = presenting explorer desktop with no app child
    private String lastWmClass = "";
    /** 0.1.74: _NET_WM_HWND of presented window (0 if unset). */
    private long lastHwnd;
    /** 0.1.75: last MapState name of presented / pending window. */
    private String lastMapState = "-";
    /** 0.1.75: frames skipped waiting for HWND VIEWABLE / non-zero geom. */
    private int mapWaitHits;
    private int lastPendingHwnd;
    /** 0.1.113: force-mapped App windows this frame (PRESENT_DIAG forceMap=). */
    private int lastForceMapped;
    private int emptyAfterReady;
    private String lastStatsLogged = "";
    private ByteBuffer hudScratch;
    /** 0.1.68: sticky once presentOk grows while guest pixels stay black/HUD-only. */
    private int guestBlackSticky;
    private int consecutiveGuestBlack;
    private static final int GUEST_BLACK_FRAMES = 45;
    /** 0.1.69: lit samples outside DXVK HUD band. */
    private int nonBlackGuestHits;
    /** 0.1.69: "bgra" | "ahb" | "none" — last guest-pixel sample source. */
    private String lastGuestSample = "none";
    /** 0.1.83: 1 if GPUImage has CPU virtualData (cpuAccess=true); 0 if GPU-only; -1 if no AHB. */
    private int lastAhbCpuAccess = -1;
    /** 0.1.85: once-log when forceGpu fires for ahbCpuAccess==0. */
    private boolean loggedAhbCpuAccess0Force;
    /** 0.1.116 P0-Q: last live App xid so we can refuse a later empty-root present. */
    private int latchedAppId;
    private long latchedHwnd;
    private ByteBuffer deskCanvas;
    /** 0.1.116 P1-T: shader test pattern until uptime millis (0 = off). */
    private volatile long surfaceTestUntilMs;
    private ByteBuffer surfaceTestBuf;
    /** DXVK_HUD top band + bottom-left corner to ignore when sampling. */
    private static final int HUD_EXCLUDE_TOP = 64;
    private static final int HUD_EXCLUDE_BL_W = 180;
    private static final int HUD_EXCLUDE_BL_H = 48;

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

        // 0.1.116 P1-T: hold shader test pattern on the Present Surface, then auto-restore.
        if (isSurfaceTestActive()) {
            presentSurfaceTestPattern(frameIndex);
            return;
        }

        Drawable drawable = null;
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        long ahbPtr = 0;
        lastAhbCpuAccess = -1;
        ByteBuffer data = null;
        int strideBytes = width * 4;
        int childId = 0;
        int kids = 0;
        ByteBuffer deskSnapshot = null;
        boolean liveAppFrame = false;

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            Window root = xServer.windowManager.rootWindow;
            if (root != null) {
                kids = root.getChildren() != null ? root.getChildren().size() : 0;
                PickResult pr = pickBestContent(root, xServer);
                lastMappedKids = pr.mappedKids;
                lastDesktopOnly = pr.desktopOnly ? 1 : 0;
                lastWmClass = pr.wmClass != null ? pr.wmClass : "";
                lastHwnd = pr.hwnd;
                lastMapState = pr.mapState != null ? pr.mapState : "-";
                lastPendingHwnd = pr.pendingHwnd;
                lastForceMapped = pr.forceMapped;
                Pick best = pr.pick;
                // 0.1.75: HWND exists but not VIEWABLE / zero-geom — wait; do not steal desktop.
                if (best == null && pr.pendingHwnd > 0) {
                    mapWaitHits++;
                    if ((frameIndex % 30) == 0) {
                        logDiag(frameIndex, "mapWait pendingHwnd=" + pr.pendingHwnd
                                + " mapState=" + lastMapState);
                    }
                    return;
                }
                if (best == null) {
                    best = pickFromWindow(root);
                    lastDesktopOnly = 1;
                    if (best != null) {
                        lastMapState = mapStateName(root);
                    }
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
                // 0.1.70: NEVER promote software Drawable→GPUImage here.
                // createOrObtain(..., cpuAccess=true) replaces data with empty AHB
                // virtualData and wipes GDI/PutImage pixels → permanent guestBlack.
                // Only use AHB if DRI3/PresentExtension already attached a GPUImage.
                if (tex instanceof GPUImage) {
                    GPUImage gpu = (GPUImage) tex;
                    ahbPtr = gpu.getHardwareBufferPtr();
                    // 0.1.83: cheap DIAG — virtualData non-null ⇒ created with cpuAccess=true
                    lastAhbCpuAccess = gpu.getVirtualData() != null ? 1 : 0;
                    if (gpu.getStride() > 0) {
                        strideBytes = gpu.getStride() * 4;
                    }
                } else {
                    lastAhbCpuAccess = -1;
                }
                data = drawable.getData();
            }

            // 0.1.116 P0-Q: after live App hwnd, composite the App window tree onto a
            // 1280×720 BGRA canvas and drop empty root AHB (child=0x3 path=ahb_gpu).
            liveAppFrame = lastHwnd != 0 && lastDesktopOnly == 0 && !isHelperClassName(lastWmClass);
            if (!liveAppFrame && latchedAppId != 0) {
                Window lw = xServer.windowManager.getWindow(latchedAppId);
                if (lw != null && !isHelper(lw)) {
                    Pick lp = pickFromWindow(lw);
                    if (lp == null) lp = pickFromWindowAllowUnmapped(lw);
                    if (lp != null) {
                        drawable = lp.drawable;
                        childId = lp.id;
                        width = lp.width;
                        height = lp.height;
                        lastHwnd = hwndOf(lw);
                        lastWmClass = classOf(lw);
                        lastDesktopOnly = 0;
                        lastMapState = mapStateName(lw);
                        liveAppFrame = true;
                    }
                }
            }
            if (liveAppFrame && childId != 0) {
                latchedAppId = childId;
                latchedHwnd = lastHwnd;
                Window appWin = xServer.windowManager.getWindow(childId);
                deskSnapshot = obtainDeskCanvas();
                blitWindowTree(appWin, deskSnapshot, DesktopLayout.WIDTH, DesktopLayout.HEIGHT);
                ahbPtr = 0;
                lastAhbCpuAccess = -1;
                width = DesktopLayout.WIDTH;
                height = DesktopLayout.HEIGHT;
                strideBytes = width * 4;
                data = deskSnapshot;
            }
        }

        lastW = width;
        lastH = height;
        lastChildId = childId;

        if (liveAppFrame && deskSnapshot != null) {
            lastGuestSample = "bgra";
            boolean black = isNearlyBlack(deskSnapshot, width, height, strideBytes);
            if (!black) {
                nonBlackHits++;
                nonBlackGuestHits++;
                noteGuestContent(true, frameIndex);
            } else {
                // P0-R: do not overlay presentHudBar after live hwnd.
                noteGuestContent(false, frameIndex);
            }
            NativeEngine.nativePresentMarkPath("bgra");
            int rc = NativeEngine.nativePresentBgra(deskSnapshot, width, height, strideBytes);
            if (rc == 0) {
                bgraHits++;
                presentOk++;
                emptyAfterReady = 0;
            } else {
                presentFail++;
            }
            maybeLog(frameIndex, kids);
            return;
        }

        // 0.1.66 P0: GPUImage keeps AHB permanently locked → presentAhbCpu double-lock
        // always fails. True CPU path = BGRA(virtualData).
        // 0.1.70: forceGpu when no CPU mapping (DRI3) or guestBlack sticky (GPU wrote
        // AHB but CPU view stays black). Else ahb_skip → BGRA below.
        // 0.1.85: also forceGpu when ahbCpuAccess==0 (GPU-only AHB) even if Drawable still
        // holds a stale non-null software ByteBuffer — otherwise wait for sticky / ahb_skip.
        boolean forceGpu = (ahbPtr != 0)
                && (data == null || guestBlackSticky != 0 || lastAhbCpuAccess == 0);
        if (forceGpu && lastAhbCpuAccess == 0 && !loggedAhbCpuAccess0Force) {
            loggedAhbCpuAccess0Force = true;
            Log.i(TAG, "PRESENT_DIAG forceGpu=ahb_gpu reason=ahbCpuAccess0 frame=" + frameIndex
                    + " data=" + (data != null ? "nonNull" : "null")
                    + " sticky=" + guestBlackSticky);
        }
        if (ahbPtr != 0 && data == null) {
            int rc = NativeEngine.nativePresentAhb(ahbPtr, width, height, true);
            if (rc == 0) {
                ahbHits++;
                presentOk++;
                emptyAfterReady = 0;
                // 0.1.70: GPU-only AHB — cannot CPU-sample; trust ahb_gpu posted pixels
                lastGuestSample = "none";
                if (guestBlackSticky != 0) {
                    guestBlackSticky = 0;
                    consecutiveGuestBlack = 0;
                    Log.i(TAG, "PRESENT_DIAG guestBlack=0 recovered path=ahb_gpu (no-CPU AHB) frame="
                            + frameIndex);
                } else {
                    consecutiveGuestBlack = 0;
                }
                maybeLog(frameIndex, kids);
                return;
            }
            // AHB failed / skipped — fall through (must not early-return)
            presentFail++;
        } else if (ahbPtr != 0) {
            int rc = NativeEngine.nativePresentAhb(ahbPtr, width, height, forceGpu);
            if (rc == 0) {
                ahbHits++;
                presentOk++;
                emptyAfterReady = 0;
                lastGuestSample = "ahb";
                // forceGpu ahb_gpu: CPU virtualData may still look black — treat as lit
                boolean black = !forceGpu && isNearlyBlack(data, width, height, strideBytes);
                if (!black) {
                    nonBlackHits++;
                    nonBlackGuestHits++;
                    if (forceGpu && guestBlackSticky != 0) {
                        guestBlackSticky = 0;
                        consecutiveGuestBlack = 0;
                        Log.i(TAG, "PRESENT_DIAG guestBlack=0 recovered path=ahb_gpu (sticky force) frame="
                                + frameIndex);
                    } else {
                        noteGuestContent(true, frameIndex);
                    }
                } else {
                    maybePresentHudBar(width, height, frameIndex);
                    noteGuestContent(false, frameIndex);
                }
                maybeLog(frameIndex, kids);
                return;
            }
            // rc != 0 (expected ahb_skip / GPU fail) — BGRA below; do not count as hard fail storm
            if (rc != -11 /* EAGAIN */) {
                presentFail++;
            }
        }

        if (data != null && width > 1 && height > 1) {
            ByteBuffer dup = data.duplicate();
            dup.rewind();
            lastGuestSample = "bgra";
            boolean black = isNearlyBlack(dup, width, height, strideBytes);
            dup.rewind();
            if (!black) {
                nonBlackHits++;
                nonBlackGuestHits++;
                noteGuestContent(true, frameIndex);
            } else {
                // 0.1.66 P1: NEVER paint HUD into guest SHM (duplicate shares backing).
                // Use independent hudScratch via presentHudBar instead.
                if (maybePresentHudBar(width, height, frameIndex)) {
                    presentOk++;
                    emptyAfterReady = 0;
                    noteGuestContent(false, frameIndex);
                    maybeLog(frameIndex, kids);
                    return;
                }
                // fall through to try raw (black) BGRA if HUD present failed
                noteGuestContent(false, frameIndex);
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
                NativeEngine.nativePresentMarkPath("bgra");
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

        emptyAfterReady++;
        if (emptyAfterReady >= 60 || (kids > 0 && emptyAfterReady >= 15)) {
            // Prefer HUD bar over full stub rainbow when we have a size
            if (maybePresentHudBar(width > 1 ? width : DesktopLayout.WIDTH,
                    height > 1 ? height : DesktopLayout.HEIGHT, frameIndex)) {
                stubHits++;
                presentOk++;
                noteGuestContent(false, frameIndex);
            } else {
                int rc = NativeEngine.nativePresentStub(frameIndex);
                stubHits++;
                if (rc == 0) {
                    presentOk++;
                    noteGuestContent(false, frameIndex);
                } else presentFail++;
            }
            if ((frameIndex % 30) == 0 || emptyAfterReady == 60 || emptyAfterReady == 15) {
                logDiag(frameIndex, "stub-fallback kids=" + kids);
            }
            return;
        }
        maybeLog(frameIndex, kids);
    }

    /**
     * 0.1.116 P0-R: HUD bar only when there is no live App hwnd.
     * After live hwnd the 8px cyan/magenta bar must not steal the guest frame.
     */
    private boolean hudAllowed() {
        return lastHwnd == 0 || lastDesktopOnly == 1 || isHelperClassName(lastWmClass);
    }

    private boolean maybePresentHudBar(int width, int height, int frameIndex) {
        if (!hudAllowed()) return false;
        return presentHudBar(width, height, frameIndex);
    }

    /** 0.1.116 P1-T: show a full-surface shader test pattern for durationMs, then auto-restore. */
    public void showSurfaceTest(long durationMs) {
        long now = android.os.SystemClock.uptimeMillis();
        surfaceTestUntilMs = now + Math.max(0L, durationMs);
        requestPulse();
    }

    public boolean isSurfaceTestActive() {
        return surfaceTestUntilMs != 0 && android.os.SystemClock.uptimeMillis() < surfaceTestUntilMs;
    }

    /**
     * Distinct full-frame test pattern (color bars + grid). Not presentHudBar.
     * Pump holds this until {@link #surfaceTestUntilMs}, then resumes guest frames.
     */
    private void presentSurfaceTestPattern(int frameIndex) {
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        int need = width * height * 4;
        if (surfaceTestBuf == null || surfaceTestBuf.capacity() < need) {
            surfaceTestBuf = ByteBuffer.allocateDirect(need);
        }
        surfaceTestBuf.clear();
        // BGRA bars: blue, green, red, yellow, magenta, cyan, white, gray
        int[] bars = {
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF00FFFF,
                0xFFFF00FF, 0xFFFFFF00, 0xFFFFFFFF, 0xFF808080
        };
        byte[] row = new byte[width * 4];
        int barW = Math.max(1, width / bars.length);
        for (int x = 0; x < width; x++) {
            int b = Math.min(x / barW, bars.length - 1);
            int c = bars[b];
            int i = x * 4;
            row[i] = (byte) (c & 0xff);
            row[i + 1] = (byte) ((c >>> 8) & 0xff);
            row[i + 2] = (byte) ((c >>> 16) & 0xff);
            row[i + 3] = (byte) 0xFF;
        }
        for (int y = 0; y < height; y++) {
            if ((y & 16) == 0 && (y / 16) % 2 == 0) {
                // light grid line every 32px
                byte[] grid = row.clone();
                for (int x = 0; x < width; x++) {
                    if ((x & 31) == 0) {
                        int i = x * 4;
                        grid[i] = 0;
                        grid[i + 1] = 0;
                        grid[i + 2] = 0;
                        grid[i + 3] = (byte) 0xFF;
                    }
                }
                surfaceTestBuf.put(grid);
            } else {
                surfaceTestBuf.put(row);
            }
        }
        surfaceTestBuf.flip();
        NativeEngine.nativePresentMarkPath("surface_test");
        int rc = NativeEngine.nativePresentBgra(surfaceTestBuf, width, height, width * 4);
        if (rc == 0) {
            presentOk++;
            emptyAfterReady = 0;
            lastW = width;
            lastH = height;
        }
        if ((frameIndex % 15) == 0) {
            logDiag(frameIndex, "surface_test remain=" + Math.max(0, surfaceTestUntilMs - android.os.SystemClock.uptimeMillis()) + "ms");
        }
    }

    private ByteBuffer obtainDeskCanvas() {
        int need = DesktopLayout.WIDTH * DesktopLayout.HEIGHT * 4;
        if (deskCanvas == null || deskCanvas.capacity() < need) {
            deskCanvas = ByteBuffer.allocateDirect(need);
        }
        deskCanvas.clear();
        byte[] z = new byte[DesktopLayout.WIDTH * 4];
        for (int y = 0; y < DesktopLayout.HEIGHT; y++) deskCanvas.put(z);
        deskCanvas.rewind();
        return deskCanvas;
    }

    private void blitWindowTree(Window w, ByteBuffer dest, int destW, int destH) {
        if (w == null || dest == null) return;
        try {
            if (w.attributes != null && w.attributes.isMapped()) {
                Drawable d = w.getContent();
                if (d != null && d.getData() != null) {
                    blitBgra(d.getData(), d.width & 0xffff, d.height & 0xffff,
                            dest, destW, destH, w.getRootX(), w.getRootY());
                }
                java.util.List<Window> kids = w.getChildren();
                if (kids != null) {
                    for (Window c : kids) blitWindowTree(c, dest, destW, destH);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void blitBgra(ByteBuffer src, int srcW, int srcH,
                                 ByteBuffer dst, int dstW, int dstH, int dstX, int dstY) {
        if (src == null || dst == null || srcW <= 0 || srcH <= 0) return;
        ByteBuffer s = src.duplicate();
        int srcStride = srcW * 4;
        int dstStride = dstW * 4;
        for (int y = 0; y < srcH; y++) {
            int dy = dstY + y;
            if (dy < 0 || dy >= dstH) continue;
            int sx0 = 0;
            int dx0 = dstX;
            int copyW = srcW;
            if (dx0 < 0) {
                sx0 = -dx0;
                copyW -= sx0;
                dx0 = 0;
            }
            if (dx0 + copyW > dstW) copyW = dstW - dx0;
            if (copyW <= 0) continue;
            int soff = y * srcStride + sx0 * 4;
            int doff = dy * dstStride + dx0 * 4;
            int bytes = copyW * 4;
            if (soff < 0 || soff + bytes > s.capacity()) continue;
            if (doff < 0 || doff + bytes > dst.capacity()) continue;
            ByteBuffer slice = s.duplicate();
            slice.position(soff);
            slice.limit(soff + bytes);
            dst.position(doff);
            dst.put(slice);
        }
        dst.rewind();
        src.rewind();
    }

    private static boolean isHelperClassName(String c) {
        if (c == null || c.isEmpty() || "-".equals(c)) return false;
        String s = c.toLowerCase();
        return s.contains("rundll") || s.contains("wineboot") || s.contains("services.exe")
                || s.contains("plugplay") || s.contains("explorer");
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
        NativeEngine.nativePresentMarkPath("hud");
        int rc = NativeEngine.nativePresentBgra(hudScratch, width, height, width * 4);
        if (rc == 0) {
            hudHits++;
            return true;
        }
        return false;
    }

    /**
     * Paint HUD into an independent buffer only. Do NOT pass Drawable.getData() /
     * GPUImage.virtualData (duplicate shares backing → corrupts guest SHM).
     * Prefer {@link #presentHudBar} which uses hudScratch.
     */
    @SuppressWarnings("unused")
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

    /**
     * Sample a grid of pixels; true if almost everything is near-black.
     * 0.1.69: exclude DXVK_HUD band (top {@link #HUD_EXCLUDE_TOP}px) and bottom-left
     * corner so HUD text cannot fake guestLit / block guestBlack sticky.
     */
    private static boolean isNearlyBlack(ByteBuffer data, int width, int height, int strideBytes) {
        if (data == null || width <= 0 || height <= 0) return true;
        ByteBuffer d = data.duplicate();
        int stride = strideBytes > 0 ? strideBytes : width * 4;
        int lit = 0;
        int samples = 0;
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 12);
        int y0 = Math.min(HUD_EXCLUDE_TOP, Math.max(0, height - 1));
        for (int y = y0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                // bottom-left DXVK HUD corner
                if (y >= height - HUD_EXCLUDE_BL_H && x < HUD_EXCLUDE_BL_W) continue;
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

    /**
     * 0.1.67: prefer application / non-desktop windows over explorer desktop.
     * Largest-area desktop (full WxH) previously always beat real app children.
     * 0.1.74: among equals, prefer windows with {@code _NET_WM_HWND} (DXGI/D3D target).
     * 0.1.75: only VIEWABLE + non-zero geom; pending HWND → wait (caller skips desktop).
     * Priority: focused+HWND → focused app → HWND app → any app → dialog HWND →
     * dialog → HWND non-desk → non-desk → desktop/any.
     * 0.1.81: prefer dialog-like (#32770 / Type.DIALOG) over larger explorer chrome.
     * 0.1.112: SparseArray scan + force-map App HWND (Wine WM_STATE without MapWindow);
     * explorer never fills bestApp; retarget away from empty desk.
     * 0.1.114 P1-I Dig113: after APP_LOAD, first non-explorer hwnd (NSIS dialog / #32770)
     * must forceMap + retarget (desktopOnly=0 mappedKids≥1 hwnd≠0). guestPresented /
     * clear present_black only with live App hwnd (KEEP 112/113 anti-FP). Desk-marker
     * split unchanged — explorer still excluded from bestApp.
     */
    private static PickResult pickBestContent(Window root, XServer xServer) {
        Hold hold = new Hold();
        walkCollect(root, true, hold);
        // 0.1.112: tree walk alone missed App (mappedKids=0) — scan all windows + force-map.
        scanAllAndForceMap(xServer, hold);

        // Focused application window wins when it has presentable content.
        try {
            if (xServer != null && xServer.windowManager != null) {
                Window focused = xServer.windowManager.getFocusedWindow();
                if (focused != null && focused != root) {
                    boolean desk = false;
                    boolean app = false;
                    try { desk = focused.isDesktopWindow(); } catch (Throwable ignored) {}
                    try { app = focused.isApplicationWindow(); } catch (Throwable ignored) {}
                    if (!desk && !isHelper(focused) && (app || (!focused.isDesktopWindow()))) {
                        Pick fp = pickFromWindow(focused);
                        if (fp == null) {
                            fp = pickFromWindowAllowUnmapped(focused);
                        }
                        if (fp != null) {
                            return new PickResult(fp, focused, hold.mappedKids, false, classOf(focused),
                                    hwndOf(focused), mapStateName(focused), hold.pendingHwnd, hold.forceMapped);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (hold.bestAppHwnd != null) {
            return new PickResult(hold.bestAppHwnd, hold.bestAppHwndWin, hold.mappedKids, false,
                    classOf(hold.bestAppHwndWin), hwndOf(hold.bestAppHwndWin),
                    mapStateName(hold.bestAppHwndWin), hold.pendingHwnd, hold.forceMapped);
        }
        if (hold.bestApp != null) {
            return new PickResult(hold.bestApp, hold.bestAppWin, hold.mappedKids, false,
                    classOf(hold.bestAppWin), hwndOf(hold.bestAppWin),
                    mapStateName(hold.bestAppWin), hold.pendingHwnd, hold.forceMapped);
        }
        if (hold.bestDialogHwnd != null) {
            return new PickResult(hold.bestDialogHwnd, hold.bestDialogHwndWin, hold.mappedKids, false,
                    classOf(hold.bestDialogHwndWin), hwndOf(hold.bestDialogHwndWin),
                    mapStateName(hold.bestDialogHwndWin), hold.pendingHwnd, hold.forceMapped);
        }
        if (hold.bestDialog != null) {
            return new PickResult(hold.bestDialog, hold.bestDialogWin, hold.mappedKids, false,
                    classOf(hold.bestDialogWin), hwndOf(hold.bestDialogWin),
                    mapStateName(hold.bestDialogWin), hold.pendingHwnd, hold.forceMapped);
        }
        if (hold.bestNonDeskHwnd != null) {
            return new PickResult(hold.bestNonDeskHwnd, hold.bestNonDeskHwndWin, hold.mappedKids, false,
                    classOf(hold.bestNonDeskHwndWin), hwndOf(hold.bestNonDeskHwndWin),
                    mapStateName(hold.bestNonDeskHwndWin), hold.pendingHwnd, hold.forceMapped);
        }
        if (hold.bestNonDesktop != null) {
            return new PickResult(hold.bestNonDesktop, hold.bestNonDeskWin, hold.mappedKids, false,
                    classOf(hold.bestNonDeskWin), hwndOf(hold.bestNonDeskWin),
                    mapStateName(hold.bestNonDeskWin), hold.pendingHwnd, hold.forceMapped);
        }
        // Pending HWND: no presentable pick yet — signal wait (do not steal desktop).
        if (hold.pendingHwnd > 0) {
            return new PickResult(null, hold.pendingWin, hold.mappedKids, false,
                    classOf(hold.pendingWin), hwndOf(hold.pendingWin),
                    hold.pendingMapState != null ? hold.pendingMapState : "UNVIEWABLE",
                    hold.pendingHwnd, hold.forceMapped);
        }
        boolean deskOnly = hold.bestAnyWin != null && hold.bestAnyWin.isDesktopWindow();
        if (hold.bestAny == null) deskOnly = true;
        return new PickResult(hold.bestAny, hold.bestAnyWin, hold.mappedKids, deskOnly,
                classOf(hold.bestAnyWin), hwndOf(hold.bestAnyWin),
                mapStateName(hold.bestAnyWin), 0, hold.forceMapped);
    }

    private static String mapStateName(Window w) {
        if (w == null) return "-";
        try {
            return w.getMapState().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static long hwndOf(Window w) {
        if (w == null) return 0L;
        try {
            // 0.1.115: Wine often never sets _NET_WM_HWND — fall back to XID
            return w.getHandleOrXid();
        } catch (Throwable t) {
            try {
                return w.getHandle();
            } catch (Throwable t2) {
                return 0L;
            }
        }
    }

    private static boolean isHelper(Window w) {
        if (w == null) return false;
        try {
            return w.isHelperProcess();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String classOf(Window w) {
        if (w == null) return "";
        try {
            String c = w.getClassName();
            return c != null ? c.replace(' ', '_') : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 0.1.112: Wine often sets WM_STATE/HWND + SET_DRAWABLE without MapWindow on the
     * in-process X server → tree walk sees mappedKids=0 and Present sticks on root.
     * Scan every window; force-map presentable non-desk; fill Hold buckets.
     */
    private static void scanAllAndForceMap(XServer xServer, Hold hold) {
        if (xServer == null || xServer.windowManager == null || hold == null) return;
        final com.winlator.xserver.WindowManager wm = xServer.windowManager;
        final Window root = wm.rootWindow;
        try {
            wm.forEachWindow(node -> {
                if (node == null || node == root) return;
                boolean desk = false;
                boolean app = false;
                boolean dialog = false;
                long hwnd = 0L;
                boolean helper = false;
                try {
                    desk = node.isDesktopWindow();
                    app = node.isApplicationWindow();
                    dialog = node.isDialogLike();
                    hwnd = node.getHandle();
                    helper = node.isHelperProcess();
                } catch (Throwable ignored) {
                }
                if (hwnd == 0L && !desk && !helper) {
                    try { hwnd = node.getHandleOrXid(); } catch (Throwable ignored) {}
                }
                if (helper) return; // 0.1.115: never forceMap/Present rundll32
                if (desk) {
                    // Still count VIEWABLE desk toward mappedKids for DIAG honesty.
                    try {
                        if (node.getMapState() == Window.MapState.VIEWABLE) {
                            // Avoid double-count if walkCollect already saw it: use id set via tag? keep simple — walkCollect already counted tree.
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // 0.1.114: NSIS installer often Type.NORMAL + _NET_WM_HWND (hwnd≠0) before
                // isApplicationWindow; treat hwnd/dialog as force-map / retarget candidates.
                boolean interesting = app || dialog || hwnd != 0L;
                if (!interesting) {
                    // Still try any non-desk with real content (NSIS may lack HWND early).
                    Pick probeEarly = pickFromWindowAllowUnmapped(node);
                    if (probeEarly == null) return;
                    interesting = true;
                }
                boolean wasMapped = false;
                try { wasMapped = node.attributes.isMapped(); } catch (Throwable ignored) {}
                if (!wasMapped) {
                    try {
                        if (wm.forceMapIfNeeded(node)) {
                            hold.forceMapped++;
                            if (!hold.loggedForceMap) {
                                hold.loggedForceMap = true;
                                android.util.Log.i(TAG, "PRESENT_DIAG forceMap id=0x"
                                        + Integer.toHexString(node.id)
                                        + " hwnd=0x" + Long.toHexString(hwnd)
                                        + " class=" + classOf(node)
                                        + " (0.1.115 Wine WM_STATE without MapWindow)");
                            }
                            // 0.1.115 P0-L: Expose nudge → Wine x11drv GDI blit
                            // (PutImage/CopyArea/SHM) into Present-sampled drawable
                            try {
                                node.sendEvent(new Expose(node));
                            } catch (Throwable ignored2) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (hwnd != 0L || dialog || app) {
                    // Already mapped App/dialog — still nudge redraw once per sticky
                    if (!hold.loggedForceMap && hold.forceMapped == 0) {
                        try {
                            node.sendEvent(new Expose(node));
                            hold.loggedForceMap = true;
                            android.util.Log.i(TAG, "PRESENT_DIAG exposeNudge id=0x"
                                    + Integer.toHexString(node.id)
                                    + " hwnd=0x" + Long.toHexString(hwnd)
                                    + " class=" + classOf(node)
                                    + " (0.1.115 P0-L GDI flush)");
                        } catch (Throwable ignored) {
                        }
                    }
                }
                Window.MapState ms;
                try {
                    ms = node.getMapState();
                } catch (Throwable t) {
                    ms = node.attributes.isMapped() ? Window.MapState.VIEWABLE : Window.MapState.UNMAPPED;
                }
                if (hwnd != 0L) {
                    boolean presentable = ms == Window.MapState.VIEWABLE;
                    Pick probe = presentable ? pickFromWindow(node) : null;
                    if (probe == null) {
                        probe = pickFromWindowAllowUnmapped(node);
                    }
                    if (probe == null) {
                        hold.pendingHwnd++;
                        if (hold.pendingWin == null) {
                            hold.pendingWin = node;
                            hold.pendingMapState = presentable ? "ZERO_GEOM" : ms.name();
                        }
                    }
                }
                Pick p = null;
                // mappedKids: only bump when we force-mapped (tree walk already counted
                // previously VIEWABLE); avoid double-count Dig111-style.
                boolean forceMappedHere = !wasMapped && node.attributes.isMapped();
                if (forceMappedHere && ms == Window.MapState.VIEWABLE) {
                    hold.mappedKids++;
                } else if (ms == Window.MapState.VIEWABLE) {
                    // Off-tree window walkCollect missed — count once.
                    boolean inTree = false;
                    try {
                        Window par = node.getParent();
                        inTree = par != null;
                    } catch (Throwable ignored) {
                    }
                    // Always safe to re-count off-tree; tree kids already counted.
                    // Use id heuristic: if parent chain reaches root, walkCollect saw it.
                    try {
                        Window cur = node;
                        while (cur != null && cur != root) cur = cur.getParent();
                        inTree = (cur == root);
                    } catch (Throwable ignored) {
                        inTree = false;
                    }
                    if (!inTree) hold.mappedKids++;
                }
                p = (ms == Window.MapState.VIEWABLE) ? pickFromWindow(node) : null;
                if (p == null) {
                    p = pickFromWindowAllowUnmapped(node);
                }
                if (p == null) return;
                considerPick(hold, node, p, app, dialog, desk, hwnd);
            });
        } catch (Throwable t) {
            android.util.Log.w(TAG, "scanAllAndForceMap: " + t.getMessage());
        }
    }

    private static void considerPick(Hold hold, Window node, Pick p, boolean app, boolean dialog,
                                     boolean desk, long hwnd) {
        if (hold == null || node == null || p == null || desk) return;
        // 0.1.115 P0-K: sticky exclude rundll32 — never Present-retarget helpers
        if (isHelper(node)) return;
        if (app && (hold.bestApp == null || p.area > hold.bestApp.area)) {
            hold.bestApp = p;
            hold.bestAppWin = node;
        }
        if (app && hwnd != 0L
                && (hold.bestAppHwnd == null || p.area > hold.bestAppHwnd.area)) {
            hold.bestAppHwnd = p;
            hold.bestAppHwndWin = node;
        }
        if (dialog && (hold.bestDialog == null || p.area > hold.bestDialog.area)) {
            hold.bestDialog = p;
            hold.bestDialogWin = node;
        }
        if (dialog && hwnd != 0L
                && (hold.bestDialogHwnd == null || p.area > hold.bestDialogHwnd.area)) {
            hold.bestDialogHwnd = p;
            hold.bestDialogHwndWin = node;
        }
        // HWND alone (NSIS installer: Type.NORMAL + _NET_WM_HWND, may fail isApplicationWindow)
        if (hwnd != 0L
                && (hold.bestNonDeskHwnd == null || p.area > hold.bestNonDeskHwnd.area)) {
            hold.bestNonDeskHwnd = p;
            hold.bestNonDeskHwndWin = node;
        }
        if (hold.bestNonDesktop == null || p.area > hold.bestNonDesktop.area) {
            hold.bestNonDesktop = p;
            hold.bestNonDeskWin = node;
        }
        if (hold.bestAny == null || p.area > hold.bestAny.area) {
            hold.bestAny = p;
            hold.bestAnyWin = node;
        }
    }

    private static void walkCollect(Window node, boolean skipSelf, Hold hold) {
        if (node == null) return;
        if (!skipSelf) {
            long hwnd = 0L;
            boolean desk = false;
            boolean app = false;
            boolean dialog = false;
            boolean helper = false;
            try {
                hwnd = node.getHandle();
                desk = node.isDesktopWindow();
                app = node.isApplicationWindow();
                dialog = node.isDialogLike();
                helper = node.isHelperProcess();
            } catch (Throwable ignored) {
            }
            if (hwnd == 0L && !desk && !helper) {
                try { hwnd = node.getHandleOrXid(); } catch (Throwable ignored) {}
            }
            if (helper) {
                // still walk children; do not count helper as bestApp
                List<Window> kids = node.getChildren();
                if (kids != null) {
                    for (int i = 0; i < kids.size(); i++) walkCollect(kids.get(i), false, hold);
                }
                return;
            }
            Window.MapState ms;
            try {
                ms = node.getMapState();
            } catch (Throwable t) {
                ms = node.attributes.isMapped() ? Window.MapState.VIEWABLE : Window.MapState.UNMAPPED;
            }
            // Track HWND that exists but is not yet presentable (CreateSwapChain race).
            if (hwnd != 0L && !desk) {
                boolean presentable = ms == Window.MapState.VIEWABLE;
                Pick probe = presentable ? pickFromWindow(node) : null;
                if (probe == null) {
                    hold.pendingHwnd++;
                    if (hold.pendingWin == null) {
                        hold.pendingWin = node;
                        hold.pendingMapState = presentable ? "ZERO_GEOM" : ms.name();
                    }
                }
            }
            if (ms == Window.MapState.VIEWABLE) {
                hold.mappedKids++;
                Pick p = pickFromWindow(node);
                if (p != null) {
                    // 0.1.112: never put explorer into bestApp / non-desk buckets
                    if (!desk) {
                        considerPick(hold, node, p, app, dialog, false, hwnd);
                    } else if (hold.bestAny == null || p.area > hold.bestAny.area) {
                        hold.bestAny = p;
                        hold.bestAnyWin = node;
                    }
                }
            }
        }
        List<Window> children = node.getChildren();
        if (children == null) return;
        for (int i = 0; i < children.size(); i++) {
            walkCollect(children.get(i), false, hold);
        }
    }

    private static Pick pickFromWindow(Window w) {
        if (w == null) return null;
        try {
            if (w.getMapState() != Window.MapState.VIEWABLE) return null;
        } catch (Throwable t) {
            if (!w.attributes.isMapped()) return null;
        }
        Drawable d = w.getContent();
        if (d == null) return null;
        int ww = d.width & 0xffff;
        int hh = d.height & 0xffff;
        if (ww <= 1 || hh <= 1) return null;
        return new Pick(d, w.id, ww * hh, ww, hh);
    }

    /**
     * 0.1.112: allow presenting App drawable even before MapWindow (GDI already
     * SET_DRAWABLE into content). Prefer after force-map; fallback when still unmapped.
     */
    private static Pick pickFromWindowAllowUnmapped(Window w) {
        if (w == null) return null;
        try {
            if (w.isDesktopWindow()) return null;
        } catch (Throwable ignored) {
        }
        Drawable d = w.getContent();
        if (d == null) return null;
        int ww = d.width & 0xffff;
        int hh = d.height & 0xffff;
        if (ww <= 1 || hh <= 1) return null;
        return new Pick(d, w.id, ww * hh, ww, hh);
    }

    private static final class Hold {
        Pick bestApp;
        Pick bestAppHwnd;
        Pick bestDialog;
        Pick bestDialogHwnd;
        Pick bestNonDesktop;
        Pick bestNonDeskHwnd;
        Pick bestAny;
        Window bestAppWin;
        Window bestAppHwndWin;
        Window bestDialogWin;
        Window bestDialogHwndWin;
        Window bestNonDeskWin;
        Window bestNonDeskHwndWin;
        Window bestAnyWin;
        int mappedKids;
        int pendingHwnd;
        Window pendingWin;
        String pendingMapState;
        /** 0.1.112: windows force-mapped this frame (Wine WM_STATE without MapWindow). */
        int forceMapped;
        boolean loggedForceMap;
    }

    private static final class PickResult {
        final Pick pick;
        final Window win;
        final int mappedKids;
        final boolean desktopOnly;
        final String wmClass;
        final long hwnd;
        final String mapState;
        final int pendingHwnd;
        /** 0.1.113: forceMapped count from Hold for live PRESENT_DIAG forceMap=. */
        final int forceMapped;

        PickResult(Pick pick, Window win, int mappedKids, boolean desktopOnly, String wmClass,
                   long hwnd, String mapState, int pendingHwnd) {
            this(pick, win, mappedKids, desktopOnly, wmClass, hwnd, mapState, pendingHwnd, 0);
        }

        PickResult(Pick pick, Window win, int mappedKids, boolean desktopOnly, String wmClass,
                   long hwnd, String mapState, int pendingHwnd, int forceMapped) {
            this.pick = pick;
            this.win = win;
            this.mappedKids = mappedKids;
            this.desktopOnly = desktopOnly;
            this.wmClass = wmClass;
            this.hwnd = hwnd;
            this.mapState = mapState != null ? mapState : "-";
            this.pendingHwnd = pendingHwnd;
            this.forceMapped = forceMapped;
        }
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


    /** 0.1.68/69: mark guest-black streak; sticky log; clear sticky on recover. */
    private void noteGuestContent(boolean guestLit, int frameIndex) {
        if (guestLit) {
            consecutiveGuestBlack = 0;
            if (guestBlackSticky != 0) {
                guestBlackSticky = 0;
                Log.i(TAG, "PRESENT_DIAG guestBlack=0 recovered frame=" + frameIndex
                        + " guestSample=" + lastGuestSample
                        + " nonBlackGuest=" + nonBlackGuestHits);
            }
            return;
        }
        consecutiveGuestBlack++;
        if (guestBlackSticky == 0 && consecutiveGuestBlack >= GUEST_BLACK_FRAMES && presentOk > 0) {
            guestBlackSticky = 1;
            String path = NativeEngine.nativePresentLastPath();
            Log.w(TAG, "PRESENT_DIAG guestBlack=1 path=" + path
                    + " desktopOnly=" + lastDesktopOnly
                    + " mappedKids=" + lastMappedKids
                    + " hwnd=0x" + Long.toHexString(lastHwnd)
                    + " mapState=" + lastMapState
                    + " mapWait=" + mapWaitHits
                    + " pendingHwnd=" + lastPendingHwnd
                    + " wmClass=" + (lastWmClass.isEmpty() ? "-" : lastWmClass)
                    + " nonBlack=" + nonBlackHits
                    + " nonBlackGuest=" + nonBlackGuestHits
                    + " guestSample=" + lastGuestSample
                    + " hud=" + hudHits
                    + " ok=" + presentOk
                    + " frame=" + frameIndex);
        }
    }

    public String pathStats() {
        return "ahb=" + ahbHits
                + " bgra=" + bgraHits
                + " stub=" + stubHits
                + " ok=" + presentOk
                + " fail=" + presentFail
                + " nonBlack=" + nonBlackHits
                + " nonBlackGuest=" + nonBlackGuestHits
                + " hud=" + hudHits
                + " waitAttach=" + waitingAttach
                + " last=" + lastW + "x" + lastH
                + " child=0x" + Integer.toHexString(lastChildId)
                + " mappedKids=" + lastMappedKids
                + " desktopOnly=" + lastDesktopOnly
                + " wmClass=" + (lastWmClass.isEmpty() ? "-" : lastWmClass)
                + " hwnd=0x" + Long.toHexString(lastHwnd)
                + " mapState=" + lastMapState
                + " mapWait=" + mapWaitHits
                + " pendingHwnd=" + lastPendingHwnd
                + " forceMap=" + lastForceMapped
                + " empty=" + emptyAfterReady
                + " ready=" + NativeEngine.nativePresentReady()
                + " egl=" + NativeEngine.nativePresentEglReady()
                + " lastRc=" + NativeEngine.nativePresentLastRc()
                + " lockFail=" + NativeEngine.nativePresentLockFails()
                + " path=" + NativeEngine.nativePresentLastPath()
                + " guestBlack=" + guestBlackSticky
                + " guestSample=" + lastGuestSample
                + " ahbCpuAccess=" + lastAhbCpuAccess
                + " x11_putimage=" + DrawRequests.putImageCount()
                + " x11_copyarea=" + DrawRequests.copyAreaCount()
                + " x11_shm_putimage=" + MITSHMExtension.shmPutImageCount()
                + " x11_render=" + RenderExtension.renderCount()
                + " x11_putimage_all=" + DrawRequests.putImageAllCount()
                + " x11_copyarea_all=" + DrawRequests.copyAreaAllCount();
    }

    public int presentOkCount() {
        return presentOk;
    }

    public boolean hasPresented() {
        return presentOk > 0;
    }
}
