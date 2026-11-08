package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Stand-in for Winlator XServerView. queueEvent／requestRender 供 Drawable 銷毀與 Present 喚醒。
 */
@SuppressLint("ViewConstructor")
public class XServerView extends FrameLayout {
    private final GLRenderer renderer;
    private final Executor glExecutor = Executors.newSingleThreadExecutor();
    private Runnable onFrameRequest;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new GLRenderer(this, xServer);
        xServer.setRenderer(renderer);
        // invisible host for queueEvent semantics
        setVisibility(View.GONE);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void queueEvent(Runnable r) {
        glExecutor.execute(r);
    }

    public void requestRender() {
        if (onFrameRequest != null) onFrameRequest.run();
    }

    public void setOnFrameRequest(Runnable onFrameRequest) {
        this.onFrameRequest = onFrameRequest;
    }
}
