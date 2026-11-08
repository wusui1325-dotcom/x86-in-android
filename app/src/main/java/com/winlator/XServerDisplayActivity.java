package com.winlator;

import android.app.Activity;
import android.content.Context;

import com.winlator.contentdialog.DebugDialog;
import com.winlator.winhandler.WinHandler;

/**
 * Minimal host activity stand-in for Winlator XServer (LGPL-2.1 vendor).
 * Our SessionActivity creates/holds an instance without starting it as the UI Activity.
 */
public class XServerDisplayActivity extends Activity {
    public int frameRatingWindowId = -1;
    private DebugDialog debugDialog;
    private Context appContext;

    public XServerDisplayActivity() {}

    public void attachHostContext(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public Context getApplicationContext() {
        return appContext != null ? appContext : super.getApplicationContext();
    }

    public DebugDialog getDebugDialog() {
        if (debugDialog == null) debugDialog = new DebugDialog();
        return debugDialog;
    }

    public void changeFrameRatingVisibility(Object window, boolean visible) {
        // no-op
    }

    public WinHandler getWinHandler() {
        return null;
    }
}
