package com.winlator.core;

import android.os.Handler;
import android.os.Looper;

public abstract class AppUtils {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void runDelayed(Runnable r, long delayMs) {
        MAIN.postDelayed(r, delayMs);
    }
}
