package com.winlator.xenvironment.components;

import android.content.Context;

import com.winlator.alsaserver.ALSAClient;
import com.winlator.alsaserver.ALSAClientConnectionHandler;
import com.winlator.alsaserver.ALSARequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;

/** Guest ALSA android_aserver → 宿主 AAudio。 */
public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final ALSAClient.Options options;
    private final Context appCtx;

    public ALSAServerComponent(Context ctx, UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this.appCtx = ctx.getApplicationContext();
        this.socketConfig = socketConfig;
        this.options = options != null ? options : new ALSAClient.Options();
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClient.assignFramesPerBuffer(appCtx);
        connector = new XConnectorEpoll(
                socketConfig,
                new ALSAClientConnectionHandler(options),
                new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }
}
