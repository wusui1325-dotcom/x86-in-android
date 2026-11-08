package com.wusper.x86.x11;

import android.content.Context;
import android.util.Log;

import com.winlator.XServerDisplayActivity;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XServer;
import com.winlator.widget.XServerView;
import com.wusper.x86.graphics.DesktopLayout;

import java.io.File;

/**
 * 進程內 X（Winlator Java X／LGPL-2.1 vendor）：
 * Unix socket → Wine DISPLAY；畫面由 {@link PresentXFramePump} → AHB Present。
 */
public final class InProcessX {
    private static final String TAG = "InProcessX";

    private final Context appCtx;
    private final File imageFsRoot;
    private XServerDisplayActivity host;
    private XServer xServer;
    private XServerView xServerView;
    private XServerComponent xServerComponent;
    private SysVSharedMemoryComponent sysVComponent;
    private PresentXFramePump framePump;
    private boolean started;

    public InProcessX(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        // sockets live under filesDir/imagefs so proot can bind the same tree
        this.imageFsRoot = new File(appCtx.getFilesDir(), "engine/imagefs");
    }

    public File getImageFsRoot() {
        return imageFsRoot;
    }

    public XServer getXServer() {
        return xServer;
    }

    public synchronized void start(PresentXFramePump.FrameSink sink) {
        if (started) return;
        imageFsRoot.mkdirs();
        host = new XServerDisplayActivity();
        host.attachHostContext(appCtx);

        ScreenInfo screenInfo = new ScreenInfo(DesktopLayout.WIDTH, DesktopLayout.HEIGHT);
        // ScreenInfo ctor check
        xServer = new XServer(host, screenInfo);
        xServerView = new XServerView(appCtx, xServer);
        xServer.setRenderer(xServerView.getRenderer());

        UnixSocketConfig xCfg = UnixSocketConfig.create(
                imageFsRoot.getAbsolutePath(), UnixSocketConfig.XSERVER_PATH);
        UnixSocketConfig shmCfg = UnixSocketConfig.create(
                imageFsRoot.getAbsolutePath(), UnixSocketConfig.SYSVSHM_SERVER_PATH);

        sysVComponent = new SysVSharedMemoryComponent(xServer, shmCfg);
        sysVComponent.start();
        xServerComponent = new XServerComponent(xServer, xCfg);
        xServerComponent.start();

        framePump = new PresentXFramePump(xServer, sink);
        xServerView.setOnFrameRequest(framePump::requestPulse);
        framePump.start();

        started = true;
        Log.i(TAG, "started X socket=" + xCfg.path + " shm=" + shmCfg.path);
    }

    public synchronized void stop() {
        if (!started) return;
        try {
            if (framePump != null) framePump.shutdown();
        } catch (Throwable ignored) {}
        try {
            if (xServerComponent != null) xServerComponent.stop();
        } catch (Throwable ignored) {}
        try {
            if (sysVComponent != null) sysVComponent.stop();
        } catch (Throwable ignored) {}
        framePump = null;
        xServerComponent = null;
        sysVComponent = null;
        xServer = null;
        started = false;
        Log.i(TAG, "stopped");
    }

    public boolean isStarted() {
        return started;
    }

    /** Absolute host path for /tmp/.X11-unix (parent of X0 socket file). */
    public File x11UnixDir() {
        return new File(imageFsRoot, "tmp/.X11-unix");
    }

    public File sysvShmDir() {
        return new File(imageFsRoot, "tmp/.sysvshm");
    }
}
