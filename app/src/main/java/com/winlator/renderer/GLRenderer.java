package com.winlator.renderer;

import com.winlator.core.Bitmask;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Property;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

/** Stub compositor：畫面由 wusper Present（AHB）輸出，不走 Winlator GLSurfaceView。 */
public class GLRenderer implements WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    public final ViewTransformation viewTransformation = new ViewTransformation();
    // fullscreenTransformation created per-window in Winlator; not needed for Present path

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
    }

    @Override public void onMapWindow(Window window) { if (xServerView != null) xServerView.requestRender(); }
    @Override public void onUnmapWindow(Window window) { if (xServerView != null) xServerView.requestRender(); }
    @Override public void onChangeWindowZOrder(Window window) { if (xServerView != null) xServerView.requestRender(); }
    @Override public void onUpdateWindowContent(Window window) { if (xServerView != null) xServerView.requestRender(); }
    @Override public void onUpdateWindowGeometry(Window window, boolean resized) { if (xServerView != null) xServerView.requestRender(); }
    @Override public void onUpdateWindowAttributes(Window window, Bitmask mask) {}
    @Override public void onModifyWindowProperty(Window window, Property property) {}
    @Override public void onPointerMove(short x, short y) { if (xServerView != null) xServerView.requestRender(); }
}
