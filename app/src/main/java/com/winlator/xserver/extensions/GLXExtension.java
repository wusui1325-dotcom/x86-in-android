package com.winlator.xserver.extensions;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/** GLX 暫不接 gladiorenderer；請求回 BadImplementation。Present／MIT-SHM／DRI3 仍可用。 */
public class GLXExtension extends Extension {
    public GLXExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "GLX";
    }

    @Override
    public byte getEventCount() { return 0; }

    @Override
    public byte getErrorCount() { return 0; }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        throw new BadImplementation();
    }
}
