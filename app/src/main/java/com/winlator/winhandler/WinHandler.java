package com.winlator.winhandler;

import android.util.Log;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

/**
 * 本機 WinHandler：對齊 Winlator UDP winhandler 的 mouseEvent 語意，
 * 但不依賴客體 winhandler.dll——一律落到進程內 X inject（相對位移／鍵鼠／滾輪）。
 * 完整 dinput 手把狀態通道可選後續；現提供 sendGamepadState no-op＋外層鍵位映射。
 */
public class WinHandler {
    private XServer xServer;

    public void attachXServer(XServer xServer) {
        this.xServer = xServer;
        if (xServer != null) {
            xServer.setWinHandler(this);
        }
    }

    public XServer getXServer() {
        return xServer;
    }

    /**
     * @param flags {@link MouseEventFlags}
     * @param dx    相對位移 X（MOVE）
     * @param dy    相對位移 Y（MOVE）
     * @param wheelDelta 滾輪（WHEEL）；正上負下，單位同 Winlator（通常 ±120）
     */
    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        XServer xs = xServer;
        if (xs == null) return;

        if ((flags & MouseEventFlags.MOVE) != 0) {
            if (dx != 0 || dy != 0) {
                xs.injectPointerMoveDelta(dx, dy);
            }
        }

        if ((flags & MouseEventFlags.LEFTDOWN) != 0) {
            xs.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        }
        if ((flags & MouseEventFlags.LEFTUP) != 0) {
            xs.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        }
        if ((flags & MouseEventFlags.RIGHTDOWN) != 0) {
            xs.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
        }
        if ((flags & MouseEventFlags.RIGHTUP) != 0) {
            xs.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        }
        if ((flags & MouseEventFlags.MIDDLEDOWN) != 0) {
            xs.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
        }
        if ((flags & MouseEventFlags.MIDDLEUP) != 0) {
            xs.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
        }

        if ((flags & MouseEventFlags.WHEEL) != 0 && wheelDelta != 0) {
            // 每 120 一格；不足一格也至少一格
            int ticks = Math.max(1, Math.abs(wheelDelta) / 120);
            Pointer.Button btn = wheelDelta > 0
                    ? Pointer.Button.BUTTON_SCROLL_UP
                    : Pointer.Button.BUTTON_SCROLL_DOWN;
            for (int i = 0; i < ticks; i++) {
                xs.injectPointerButtonPress(btn);
                xs.injectPointerButtonRelease(btn);
            }
        }
    }

    /**
     * 0.1.77: no guest winhandler.dll — raise on the in-process X WM instead of no-op.
     * Closes 075 leftover: MapWindow raises once, but focus/dialog paths still called this.
     */
    public void bringToFront(String className, long handle) {
        XServer xs = xServer;
        if (xs == null) return;
        try {
            WindowManager wm = xs.windowManager;
            Window target = null;
            if (handle != 0L) {
                target = wm.findWindowByHandle(handle);
            }
            if (target == null && className != null && !className.isEmpty()) {
                target = wm.findWindowByClassName(className);
            }
            if (target == null || target == wm.rootWindow) return;
            wm.raiseWindow(target);
            boolean parentIsRoot = target.getParent() == wm.rootWindow;
            wm.setFocus(
                    target,
                    parentIsRoot
                            ? WindowManager.FocusRevertTo.POINTER_ROOT
                            : WindowManager.FocusRevertTo.PARENT);
            Log.i(
                    "WinHandler",
                    "bringToFront raise class="
                            + className
                            + " hwnd=0x"
                            + Long.toHexString(handle)
                            + " id=0x"
                            + Integer.toHexString(target.id));
        } catch (Throwable t) {
            Log.w("WinHandler", "bringToFront: " + t.getMessage());
        }
    }

    public void sendGamepadState() {
        // 完整 dinput UDP 後續；現由 InProcessXInput 鍵位映射
    }
}
