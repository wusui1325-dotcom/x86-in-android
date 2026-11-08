package com.winlator.inputcontrols;

import android.view.InputDevice;

public class ExternalController {
    public static boolean isGameController(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
