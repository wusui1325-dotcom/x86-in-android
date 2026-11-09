package com.winlator.xserver;

import android.util.Log;

import androidx.collection.ArrayMap;

import com.winlator.winhandler.WinHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

public abstract class DesktopHelper {
    private static final String TAG = "DesktopHelper";
    /** 0.1.75: guest /mnt/data/.x86-x11-ready — wine-launch MAP_WAIT before APP_LOAD. */
    private static volatile File readyMarker;

    public static void setReadyMarker(File marker) {
        readyMarker = marker;
    }

    public static void clearReadyMarker() {
        File m = readyMarker;
        if (m != null && m.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            m.delete();
        }
    }

    public static void attachTo(final XServer xServer) {
        setupXResources(xServer);

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
                raiseIfNeeded(xServer, window);
                setFocusedWindow(xServer, window);
                maybeWriteReadyMarker(window);
            }
        });
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
            if (app || desk || hwnd != 0L || window.isDialogBox()) {
                xServer.windowManager.raiseWindow(window);
            }
        } catch (Throwable t) {
            Log.w(TAG, "raiseIfNeeded: " + t.getMessage());
        }
    }

    private static void maybeWriteReadyMarker(Window window) {
        File m = readyMarker;
        if (m == null) return;
        try {
            boolean desk = false;
            boolean app = false;
            long hwnd = 0L;
            try {
                desk = window.isDesktopWindow();
                app = window.isApplicationWindow();
                hwnd = window.getHandle();
            } catch (Throwable ignored) {
            }
            // Desktop map unlocks APP_LOAD; HWND map refreshes marker for DIAG.
            if (!(desk || app || hwnd != 0L)) return;
            File parent = m.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            String body = "mapped=1 desk=" + (desk ? 1 : 0)
                    + " app=" + (app ? 1 : 0)
                    + " hwnd=0x" + Long.toHexString(hwnd)
                    + " id=0x" + Integer.toHexString(window.id)
                    + "\n";
            try (FileOutputStream fos = new FileOutputStream(m, false)) {
                fos.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (desk) {
                Log.i(TAG, "MAP_RAISE desktop ready marker=" + m.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "readyMarker write: " + t.getMessage());
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
        else if (window.isDialogBox()) {
            winHandler.bringToFront(window.getClassName(), window.getHandle());
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
