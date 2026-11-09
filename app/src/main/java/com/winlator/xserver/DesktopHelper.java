package com.winlator.xserver;

import android.util.Log;

import androidx.collection.ArrayMap;

import com.winlator.winhandler.WinHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

public abstract class DesktopHelper {
    private static final String TAG = "DesktopHelper";
    /** 0.1.75: guest /mnt/data/.x86-x11-ready — wine-launch MAP_WAIT before APP_LOAD. */
    private static volatile File readyMarker;
    /** 0.1.78: guest /mnt/data/.x86-nohwnd-req — Loaded-but-no-HWND → host rescan map+raise. */
    private static volatile File nohwndReqFile;
    private static volatile Thread nohwndPoller;
    private static volatile boolean nohwndPollerStop;

    // Sticky app HWND so desk remaps cannot wipe marker / steal raise.
    private static volatile int stickyAppId;
    private static volatile long stickyHwnd;
    private static volatile String stickyClass = "";
    private static volatile boolean stickyIsApp;

    public static void setReadyMarker(File marker) {
        readyMarker = marker;
        if (marker != null) {
            File parent = marker.getParentFile();
            nohwndReqFile = parent != null ? new File(parent, ".x86-nohwnd-req") : null;
        } else {
            nohwndReqFile = null;
        }
    }

    public static void clearReadyMarker() {
        File m = readyMarker;
        if (m != null && m.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            m.delete();
        }
        File r = nohwndReqFile;
        if (r != null && r.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            r.delete();
        }
        stickyAppId = 0;
        stickyHwnd = 0L;
        stickyClass = "";
        stickyIsApp = false;
        lastMsgboxLogId = 0;
    }

    public static void attachTo(final XServer xServer) {
        setupXResources(xServer);
        startNoHwndPoller(xServer);

        xServer.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener() {
            @Override
            public void onPointerButtonPress(Pointer.Button button) {
                updateFocusedWindow(xServer);
            }
        });

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                // 0.1.75: raise before focus/bringToFront so HWND is ABOVE + ConfigureNotify
                // before DXGI CreateSwapChain (WinHandler.bringToFront is a no-op here).
                // 0.1.78: desk must not steal raise above sticky app.
                raiseIfNeeded(xServer, window);
                setFocusedWindow(xServer, window);
                maybeWriteReadyMarker(window, null);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                // HWND / WM_CLASS may arrive after first MapWindow — refresh sticky + marker.
                // 0.1.112: Wine often sets WM_STATE/_NET_WM_HWND without MapWindow — force-map
                // so Present sees VIEWABLE App (NSIS dialog) instead of empty explorer desk.
                // 0.1.113 Dig112: ALSO write .x86-x11-ready on explorer desk=1 WITHOUT App hwnd.
                // 112 required nonzero App hwnd / kicked explorer from isApplicationWindow →
                // property path skipped desk → marker never written → MAP_WAIT timeout →
                // APP_LOAD_FATAL (chicken-egg). Present still excludes explorer from bestApp.
                if (window == null) return;
                try {
                    long hwnd = window.getHandle();
                    boolean desk = window.isDesktopWindow();
                    boolean app = window.isApplicationWindow();
                    boolean dialog = false;
                    try { dialog = window.isDialogLike(); } catch (Throwable ignored2) {}
                    // P0-A: explorer desktop mapped (WM_STATE/SET_DRAWABLE/WM_CLASS) unlocks
                    // APP_LOAD via marker; do NOT wait for app=1 or nonzero App hwnd.
                    if (desk) {
                        try {
                            if (!window.attributes.isMapped()) {
                                boolean mapped = xServer.windowManager.forceMapIfNeeded(window);
                                if (mapped) {
                                    Log.i(TAG, "MAP_RAISE forceMap-desk-on-prop id=0x"
                                            + Integer.toHexString(window.id)
                                            + " hwnd=0x" + Long.toHexString(hwnd)
                                            + " atom=" + (property != null ? property.nameAsString() : "?")
                                            + " (0.1.113 Dig112)");
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "forceMap-desk-on-prop: " + t.getMessage());
                        }
                        maybeWriteReadyMarker(window, null);
                        return;
                    }
                    if (app || hwnd != 0L || dialog) {
                        try {
                            if (!window.attributes.isMapped()) {
                                boolean mapped = xServer.windowManager.forceMapIfNeeded(window);
                                if (mapped) {
                                    Log.i(TAG, "MAP_RAISE forceMap-on-prop id=0x"
                                            + Integer.toHexString(window.id)
                                            + " hwnd=0x" + Long.toHexString(hwnd)
                                            + " atom=" + (property != null ? property.nameAsString() : "?")
                                            + " (0.1.112)");
                                }
                            } else {
                                xServer.windowManager.raiseWindow(window);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "forceMap-on-prop: " + t.getMessage());
                        }
                        rememberSticky(window, app || dialog, hwnd);
                        maybeWriteReadyMarker(window, null);
                        if (dialog) logMsgboxDetect(window, hwnd);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void startNoHwndPoller(final XServer xServer) {
        nohwndPollerStop = true;
        Thread old = nohwndPoller;
        if (old != null) {
            try { old.interrupt(); } catch (Throwable ignored) {}
        }
        nohwndPollerStop = false;
        Thread t = new Thread(() -> {
            Log.i(TAG, "NOHWND_MAP poller start");
            while (!nohwndPollerStop) {
                try {
                    File req = nohwndReqFile;
                    if (req != null && req.isFile()) {
                        handleNoHwndRequest(xServer, req);
                    }
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t1) {
                    Log.w(TAG, "NOHWND_MAP poller: " + t1.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
            Log.i(TAG, "NOHWND_MAP poller stop");
        }, "x86-nohwnd-poller");
        t.setDaemon(true);
        nohwndPoller = t;
        t.start();
    }

    /** Stop poller when session ends (best-effort; next attachTo restarts). */
    public static void stopNoHwndPoller() {
        nohwndPollerStop = true;
        Thread t = nohwndPoller;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }

    private static void handleNoHwndRequest(XServer xServer, File req) {
        String basename = "app.exe";
        String body = "";
        try (BufferedReader br = new BufferedReader(new FileReader(req))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                String low = line.trim();
                if (low.startsWith("basename=")) {
                    basename = low.substring("basename=".length()).trim();
                    if (basename.isEmpty()) basename = "app.exe";
                }
            }
            body = sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "NOHWND_MAP read req: " + t.getMessage());
            // still consume so we don't spin
        }
        // Consume request so we act once per write (guest may re-touch).
        //noinspection ResultOfMethodCallIgnored
        req.delete();

        String scan = scanMapRaiseApp(xServer, basename);
        Log.i(TAG, "NOHWND_MAP basename=" + basename + " nohwnd_scan=" + scan
                + " req_bytes=" + body.length());
        // Refresh marker with scan diag even if no candidate (so guest sees response).
        Window sticky = stickyAppId != 0 ? xServer.windowManager.getWindow(stickyAppId) : null;
        if (sticky != null) {
            maybeWriteReadyMarker(sticky, scan);
        } else {
            writeScanOnlyMarker(scan, basename);
        }
    }

    /**
     * 0.1.78: scan all non-desktop windows; force map + raise those matching PE basename
     * (WM_CLASS) or carrying {@code _NET_WM_HWND}. Returns compact nohwnd_scan= payload.
     */
    public static String scanMapRaiseApp(XServer xServer, String classHint) {
        if (xServer == null) return "none";
        WindowManager wm = xServer.windowManager;
        ArrayList<Window> candidates = wm.findNonDesktopCandidates(classHint);
        if (candidates.isEmpty()) {
            // Fallback: any non-desk with hwnd, even if classHint missed.
            candidates = wm.findNonDesktopCandidates(null);
        }
        if (candidates.isEmpty()) return "none";

        StringBuilder scan = new StringBuilder();
        Window best = null;
        long bestHwnd = 0L;
        for (int i = 0; i < candidates.size(); i++) {
            Window w = candidates.get(i);
            if (w == null || w == wm.rootWindow) continue;
            boolean wasMapped = false;
            long hwnd = 0L;
            String cn = "";
            try {
                wasMapped = w.attributes.isMapped();
                hwnd = w.getHandle();
                cn = w.getClassName();
                if (cn == null) cn = "";
            } catch (Throwable ignored) {
            }
            boolean mappedNow = wasMapped;
            if (!wasMapped) {
                try {
                    wm.mapWindow(w);
                    mappedNow = w.attributes.isMapped();
                } catch (Throwable t) {
                    Log.w(TAG, "NOHWND_MAP mapWindow: " + t.getMessage());
                }
            }
            try {
                wm.raiseWindow(w);
            } catch (Throwable t) {
                Log.w(TAG, "NOHWND_MAP raiseWindow: " + t.getMessage());
            }
            try {
                boolean parentIsRoot = w.getParent() == wm.rootWindow;
                wm.setFocus(
                        w,
                        parentIsRoot
                                ? WindowManager.FocusRevertTo.POINTER_ROOT
                                : WindowManager.FocusRevertTo.PARENT);
            } catch (Throwable ignored) {
            }
            boolean app = false;
            boolean dialog = false;
            try { app = w.isApplicationWindow(); } catch (Throwable ignored) {}
            try { dialog = w.isDialogLike(); } catch (Throwable ignored) {}
            rememberSticky(w, app || hwnd != 0L || dialog, hwnd);
            if (dialog) logMsgboxDetect(w, hwnd);

            if (scan.length() > 0) scan.append(';');
            scan.append("id=0x").append(Integer.toHexString(w.id))
                    .append(",class=").append(cn.replace(' ', '_').replace(';', '_'))
                    .append(",hwnd=0x").append(Long.toHexString(hwnd))
                    .append(",mapped=").append(wasMapped ? "1" : "0")
                    .append("->").append(mappedNow ? "1" : "0");

            // 0.1.81: prefer dialog-like / HWND over first arbitrary candidate
            boolean prefer = false;
            if (best == null) prefer = true;
            else if (dialog && bestHwnd == 0L) prefer = true;
            else if (hwnd != 0L && bestHwnd == 0L) prefer = true;
            if (prefer) {
                best = w;
                bestHwnd = hwnd;
            }
        }
        if (best != null) {
            maybeWriteReadyMarker(best, scan.toString());
        }
        return scan.length() == 0 ? "none" : scan.toString();
    }

    private static void rememberSticky(Window window, boolean app, long hwnd) {
        if (window == null) return;
        boolean desk = false;
        boolean dialog = false;
        try { desk = window.isDesktopWindow(); } catch (Throwable ignored) {}
        try { dialog = window.isDialogLike(); } catch (Throwable ignored) {}
        if (desk) return;
        // 0.1.81: dialog-like (#32770 / Type.DIALOG) sticky even without isApplicationWindow
        if (!(app || hwnd != 0L || dialog)) return;
        stickyAppId = window.id;
        if (hwnd != 0L) stickyHwnd = hwnd;
        stickyIsApp = app || dialog || stickyIsApp;
        try {
            String cn = window.getClassName();
            if (cn != null && !cn.isEmpty()) stickyClass = cn;
        } catch (Throwable ignored) {
        }
    }

    private static void raiseIfNeeded(XServer xServer, Window window) {
        if (window == null || window == xServer.windowManager.rootWindow) return;
        try {
            boolean app = false;
            boolean desk = false;
            long hwnd = 0L;
            try {
                app = window.isApplicationWindow();
                desk = window.isDesktopWindow();
                hwnd = window.getHandle();
            } catch (Throwable ignored) {
            }
            // 0.1.78: explorer remaps must not stack ABOVE sticky app HWND.
            if (desk && stickyAppId != 0) {
                Window sticky = xServer.windowManager.getWindow(stickyAppId);
                if (sticky != null && sticky != xServer.windowManager.rootWindow) {
                    xServer.windowManager.raiseWindow(sticky);
                    Log.i(TAG, "MAP_RAISE desk-steal-block → re-raise sticky id=0x"
                            + Integer.toHexString(stickyAppId)
                            + " hwnd=0x" + Long.toHexString(stickyHwnd));
                    return;
                }
            }
            boolean dialog = false;
            try { dialog = window.isDialogLike(); } catch (Throwable ignored) {}
            if (app || desk || hwnd != 0L || dialog) {
                xServer.windowManager.raiseWindow(window);
            }
            if (!desk && (app || hwnd != 0L || dialog)) {
                rememberSticky(window, app || dialog, hwnd);
                if (dialog) logMsgboxDetect(window, hwnd);
            }
        } catch (Throwable t) {
            Log.w(TAG, "raiseIfNeeded: " + t.getMessage());
        }
    }

    private static void maybeWriteReadyMarker(Window window, String nohwndScan) {
        File m = readyMarker;
        if (m == null) return;
        try {
            boolean desk = false;
            boolean app = false;
            long hwnd = 0L;
            String cn = "";
            try {
                desk = window.isDesktopWindow();
                app = window.isApplicationWindow();
                hwnd = window.getHandle();
                cn = window.getClassName();
                if (cn == null) cn = "";
            } catch (Throwable ignored) {
            }
            boolean dialog = false;
            try { dialog = window.isDialogLike(); } catch (Throwable ignored) {}
            // Desktop map unlocks APP_LOAD (desk=1 alone is enough — 0.1.113 Dig112).
            // HWND/dialog map refreshes marker for APP_HWND_WAIT / Present DIAG.
            if (!(desk || app || hwnd != 0L || dialog || (nohwndScan != null && !nohwndScan.isEmpty()))) {
                // Still allow sticky refresh when property updates an already-sticky window.
                if (stickyAppId == 0 || window.id != stickyAppId) return;
            }
            if (!desk && (app || hwnd != 0L || dialog)) {
                rememberSticky(window, app || dialog, hwnd);
            }

            // 0.1.78 sticky merge: desk-only write must not wipe app hwnd.
            boolean outApp = app || dialog;
            long outHwnd = hwnd;
            String outClass = cn;
            boolean outDesk = desk;
            if (desk && stickyAppId != 0 && stickyHwnd != 0L) {
                outApp = stickyIsApp || true;
                outHwnd = stickyHwnd;
                if (outClass.isEmpty()) outClass = stickyClass;
                // Marker reports sticky app presence even while desk is also mapped.
                outDesk = false;
            } else if (!desk && stickyHwnd != 0L && outHwnd == 0L) {
                outHwnd = stickyHwnd;
                outApp = stickyIsApp || outApp;
                if (outClass.isEmpty()) outClass = stickyClass;
            } else if (stickyIsApp && !desk) {
                outApp = true;
            }

            File parent = m.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            StringBuilder body = new StringBuilder();
            body.append("mapped=1 desk=").append(outDesk ? 1 : 0)
                    .append(" app=").append(outApp || outHwnd != 0L ? 1 : 0)
                    .append(" hwnd=0x").append(Long.toHexString(outHwnd))
                    .append(" id=0x").append(Integer.toHexString(
                            outHwnd != 0L && stickyAppId != 0 ? stickyAppId : window.id));
            if (outClass != null && !outClass.isEmpty()) {
                body.append(" class=").append(outClass.replace(' ', '_'));
            }
            if (nohwndScan != null && !nohwndScan.isEmpty()) {
                body.append(" nohwnd_scan=").append(nohwndScan.replace(' ', '_'));
            }
            body.append('\n');
            try (FileOutputStream fos = new FileOutputStream(m, false)) {
                fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (desk && stickyAppId == 0) {
                // 0.1.113: explicit desk=1 app=0 stamp body for MAP_WAIT dig
                Log.i(TAG, "MAP_RAISE desktop ready marker=" + m.getName()
                        + " desk=1 app=0 hwnd=0x" + Long.toHexString(outHwnd)
                        + " id=0x" + Integer.toHexString(window.id)
                        + " class=" + (outClass.isEmpty() ? "explorer.exe" : outClass.replace(' ', '_'))
                        + " (0.1.113 Dig112 split desk marker from App hwnd)");
            } else if (outHwnd != 0L || outApp) {
                Log.i(TAG, "MAP_RAISE app sticky marker hwnd=0x" + Long.toHexString(outHwnd)
                        + " app=" + (outApp ? 1 : 0)
                        + (nohwndScan != null ? " nohwnd_scan=" + nohwndScan : ""));
            }
        } catch (Throwable t) {
            Log.w(TAG, "readyMarker write: " + t.getMessage());
        }
    }

    private static void writeScanOnlyMarker(String scan, String basename) {
        File m = readyMarker;
        if (m == null) return;
        try {
            File parent = m.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            // Preserve sticky if any; else desk=1 placeholder with scan diag.
            int desk = stickyAppId != 0 ? 0 : 1;
            int app = (stickyIsApp || stickyHwnd != 0L) ? 1 : 0;
            long hwnd = stickyHwnd;
            int id = stickyAppId;
            String body = "mapped=1 desk=" + desk
                    + " app=" + app
                    + " hwnd=0x" + Long.toHexString(hwnd)
                    + " id=0x" + Integer.toHexString(id)
                    + " class=" + (stickyClass.isEmpty() ? basename : stickyClass).replace(' ', '_')
                    + " nohwnd_scan=" + (scan == null ? "none" : scan.replace(' ', '_'))
                    + "\n";
            try (FileOutputStream fos = new FileOutputStream(m, false)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan marker write: " + t.getMessage());
        }
    }

    private static void updateFocusedWindow(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window focusedWindow = xServer.windowManager.getFocusedWindow();
            Window child = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
            if (child == null && focusedWindow != xServer.windowManager.rootWindow) {
                xServer.windowManager.setFocus(xServer.windowManager.rootWindow, WindowManager.FocusRevertTo.NONE);
            }
            else if (child != null && child != focusedWindow) {
                setFocusedWindow(xServer, child);
            }
        }
    }

    private static void setFocusedWindow(XServer xServer, Window window) {
        WinHandler winHandler = xServer.getWinHandler();
            if (winHandler == null) return;
        // 0.1.78: do not let desktop focus steal after sticky app HWND.
        try {
            if (window.isDesktopWindow() && stickyAppId != 0) {
                Window sticky = xServer.windowManager.getWindow(stickyAppId);
                if (sticky != null) {
                    boolean parentIsRoot = sticky.getParent() == xServer.windowManager.rootWindow;
                    xServer.windowManager.setFocus(
                            sticky,
                            parentIsRoot
                                    ? WindowManager.FocusRevertTo.POINTER_ROOT
                                    : WindowManager.FocusRevertTo.PARENT);
                    winHandler.bringToFront(sticky.getClassName(), sticky.getHandle());
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        if (window.isApplicationWindow()) {
            boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
            xServer.windowManager.setFocus(window, parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);

            if (window.isSurface()) {
                ArrayList<Window> dialogWindows = xServer.windowManager.findDialogWindows(window.id);
                if (!dialogWindows.isEmpty()) {
                    for (Window dialogWindow : dialogWindows) winHandler.bringToFront(dialogWindow.getClassName(), dialogWindow.getHandle());
                }
                else winHandler.bringToFront(window.getClassName(), window.getHandle());
            }
            else {
                Window child = window.getChildAt(0);
                if (child != null && child.isSurface()) winHandler.bringToFront(window.getClassName(), window.getHandle());
            }
        }
        else if (window.isDialogLike()) {
            winHandler.bringToFront(window.getClassName(), window.getHandle());
            try {
                boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
                xServer.windowManager.setFocus(
                        window,
                        parentIsRoot
                                ? WindowManager.FocusRevertTo.POINTER_ROOT
                                : WindowManager.FocusRevertTo.PARENT);
            } catch (Throwable ignored) {
            }
            rememberSticky(window, true, window.getHandle());
            logMsgboxDetect(window, window.getHandle());
        }
    }

    /** 0.1.81: KEY_LOG-friendly detect for Wine MessageBox / #32770. */
    private static volatile int lastMsgboxLogId;

    private static void logMsgboxDetect(Window window, long hwnd) {
        if (window == null) return;
        try {
            if (window.id == lastMsgboxLogId) return;
            lastMsgboxLogId = window.id;
            String cn = "";
            try {
                cn = window.getClassName();
                if (cn == null) cn = "";
            } catch (Throwable ignored) {
            }
            boolean strict = false;
            try { strict = window.isDialogBox(); } catch (Throwable ignored) {}
            Log.i(TAG, "APP_MSGBOX_DETECT source=x11 class="
                    + cn.replace(' ', '_')
                    + " hwnd=0x" + Long.toHexString(hwnd)
                    + " id=0x" + Integer.toHexString(window.id)
                    + " strictDialog=" + (strict ? "1" : "0"));
        } catch (Throwable t) {
            Log.w(TAG, "APP_MSGBOX_DETECT: " + t.getMessage());
        }
    }

    private static void setupXResources(XServer xServer) {
        ArrayMap<String, String> values = new ArrayMap<>();
        values.put("size", "20");
        values.put("theme", "dmz");
        values.put("theme_core", "true");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("Xcursor")
              .append('.')
              .append(entry.getKey())
              .append(':')
              .append('\t')
              .append(entry.getValue())
              .append('\n');
        }

        byte[] data = sb.toString().getBytes(XServer.LATIN1_CHARSET);
        xServer.windowManager.rootWindow.modifyProperty(Atom.RESOURCE_MANAGER, Atom.STRING, Property.Format.BYTE_ARRAY, Property.Mode.APPEND, data);
    }
}
