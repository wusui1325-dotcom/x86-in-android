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

        Drawable drawable = null;
        int width = DesktopLayout.WIDTH;
        int height = DesktopLayout.HEIGHT;
        long ahbPtr = 0;
        lastAhbCpuAccess = -1;
        ByteBuffer data = null;
        int strideBytes = width * 4;
        int childId = 0;
        int kids = 0;

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
        }

        lastW = width;
        lastH = height;
        lastChildId = childId;

        // 0.1.66 P0: GPUImage keeps AHB permanently locked → presentAhbCpu double-lock
        // always fails. True CPU path = BGRA(virtualData).
        // 0.1.70: forceGpu when no CPU mapping (DRI3) or guestBlack sticky (GPU wrote
        // AHB but CPU view stays black). Else ahb_skip → BGRA below.
        boolean forceGpu = (ahbPtr != 0) && (data == null || guestBlackSticky != 0);
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
                    presentHudBar(width, height, frameIndex);
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
                if (presentHudBar(width, height, frameIndex)) {
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
            if (presentHudBar(width > 1 ? width : DesktopLayout.WIDTH,
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
     */
    private static PickResult pickBestContent(Window root, XServer xServer) {
        Hold hold = new Hold();
        walkCollect(root, true, hold);

        // Focused application window wins when it has presentable content.
        try {
            if (xServer != null && xServer.windowManager != null) {
                Window focused = xServer.windowManager.getFocusedWindow();
                if (focused != null && focused != root) {
                    boolean app = false;
                    try { app = focused.isApplicationWindow(); } catch (Throwable ignored) {}
                    if (app || (!focused.isDesktopWindow())) {
                        Pick fp = pickFromWindow(focused);
                        if (fp != null) {
                            return new PickResult(fp, focused, hold.mappedKids, false, classOf(focused),
                                    hwndOf(focused), mapStateName(focused), hold.pendingHwnd);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (hold.bestAppHwnd != null) {
            return new PickResult(hold.bestAppHwnd, hold.bestAppHwndWin, hold.mappedKids, false,
                    classOf(hold.bestAppHwndWin), hwndOf(hold.bestAppHwndWin),
                    mapStateName(hold.bestAppHwndWin), hold.pendingHwnd);
        }
        if (hold.bestApp != null) {
            return new PickResult(hold.bestApp, hold.bestAppWin, hold.mappedKids, false,
                    classOf(hold.bestAppWin), hwndOf(hold.bestAppWin),
                    mapStateName(hold.bestAppWin), hold.pendingHwnd);
        }
        if (hold.bestDialogHwnd != null) {
            return new PickResult(hold.bestDialogHwnd, hold.bestDialogHwndWin, hold.mappedKids, false,
                    classOf(hold.bestDialogHwndWin), hwndOf(hold.bestDialogHwndWin),
                    mapStateName(hold.bestDialogHwndWin), hold.pendingHwnd);
        }
        if (hold.bestDialog != null) {
            return new PickResult(hold.bestDialog, hold.bestDialogWin, hold.mappedKids, false,
                    classOf(hold.bestDialogWin), hwndOf(hold.bestDialogWin),
                    mapStateName(hold.bestDialogWin), hold.pendingHwnd);
        }
        if (hold.bestNonDeskHwnd != null) {
            return new PickResult(hold.bestNonDeskHwnd, hold.bestNonDeskHwndWin, hold.mappedKids, false,
                    classOf(hold.bestNonDeskHwndWin), hwndOf(hold.bestNonDeskHwndWin),
                    mapStateName(hold.bestNonDeskHwndWin), hold.pendingHwnd);
        }
        if (hold.bestNonDesktop != null) {
            return new PickResult(hold.bestNonDesktop, hold.bestNonDeskWin, hold.mappedKids, false,
                    classOf(hold.bestNonDeskWin), hwndOf(hold.bestNonDeskWin),
                    mapStateName(hold.bestNonDeskWin), hold.pendingHwnd);
        }
        // Pending HWND: no presentable pick yet — signal wait (do not steal desktop).
        if (hold.pendingHwnd > 0) {
            return new PickResult(null, hold.pendingWin, hold.mappedKids, false,
                    classOf(hold.pendingWin), hwndOf(hold.pendingWin),
                    hold.pendingMapState != null ? hold.pendingMapState : "UNVIEWABLE",
                    hold.pendingHwnd);
        }
        boolean deskOnly = hold.bestAnyWin != null && hold.bestAnyWin.isDesktopWindow();
        if (hold.bestAny == null) deskOnly = true;
        return new PickResult(hold.bestAny, hold.bestAnyWin, hold.mappedKids, deskOnly,
                classOf(hold.bestAnyWin), hwndOf(hold.bestAnyWin),
                mapStateName(hold.bestAnyWin), 0);
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
            return w.getHandle();
        } catch (Throwable t) {
            return 0L;
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

    private static void walkCollect(Window node, boolean skipSelf, Hold hold) {
        if (node == null) return;
        if (!skipSelf) {
            long hwnd = 0L;
            boolean desk = false;
            boolean app = false;
            boolean dialog = false;
            try {
                hwnd = node.getHandle();
                desk = node.isDesktopWindow();
                app = node.isApplicationWindow();
                dialog = node.isDialogLike();
            } catch (Throwable ignored) {
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
                    if (app && (hold.bestApp == null || p.area > hold.bestApp.area)) {
                        hold.bestApp = p;
                        hold.bestAppWin = node;
                    }
                    if (app && hwnd != 0L
                            && (hold.bestAppHwnd == null || p.area > hold.bestAppHwnd.area)) {
                        hold.bestAppHwnd = p;
                        hold.bestAppHwndWin = node;
                    }
                    // 0.1.81: dialog bucket — prefer MessageBox over large desk chrome by area
                    if (dialog && (hold.bestDialog == null || p.area > hold.bestDialog.area)) {
                        hold.bestDialog = p;
                        hold.bestDialogWin = node;
                    }
                    if (dialog && hwnd != 0L
                            && (hold.bestDialogHwnd == null || p.area > hold.bestDialogHwnd.area)) {
                        hold.bestDialogHwnd = p;
                        hold.bestDialogHwndWin = node;
                    }
                    if (!desk && (hold.bestNonDesktop == null || p.area > hold.bestNonDesktop.area)) {
                        hold.bestNonDesktop = p;
                        hold.bestNonDeskWin = node;
                    }
                    if (!desk && hwnd != 0L
                            && (hold.bestNonDeskHwnd == null || p.area > hold.bestNonDeskHwnd.area)) {
                        hold.bestNonDeskHwnd = p;
                        hold.bestNonDeskHwndWin = node;
                    }
                    if (hold.bestAny == null || p.area > hold.bestAny.area) {
                        hold.bestAny = p;
                        hold.bestAnyWin = node;
                    }
                }
                // zero-geom HWND already counted as pending above
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

        PickResult(Pick pick, Window win, int mappedKids, boolean desktopOnly, String wmClass,
                   long hwnd, String mapState, int pendingHwnd) {
            this.pick = pick;
            this.win = win;
            this.mappedKids = mappedKids;
            this.desktopOnly = desktopOnly;
            this.wmClass = wmClass;
            this.hwnd = hwnd;
            this.mapState = mapState != null ? mapState : "-";
            this.pendingHwnd = pendingHwnd;
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
                + " empty=" + emptyAfterReady
                + " ready=" + NativeEngine.nativePresentReady()
                + " egl=" + NativeEngine.nativePresentEglReady()
                + " lastRc=" + NativeEngine.nativePresentLastRc()
                + " lockFail=" + NativeEngine.nativePresentLockFails()
                + " path=" + NativeEngine.nativePresentLastPath()
                + " guestBlack=" + guestBlackSticky
                + " guestSample=" + lastGuestSample
                + " ahbCpuAccess=" + lastAhbCpuAccess;
    }

    public int presentOkCount() {
        return presentOk;
    }

    public boolean hasPresented() {
        return presentOk > 0;
    }
}
