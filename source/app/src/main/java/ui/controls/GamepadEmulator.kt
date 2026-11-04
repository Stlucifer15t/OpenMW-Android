/*
    Copyright (C) 2018, 2019 Ilya Zhuravlev
    Copyright (C) 2024 OpenMW-Android contributors

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.controls

import org.libsdl.app.SDLControllerManager

/**
 * Feeds the on-screen movement stick into the emulated "Virtual" gamepad that
 * the native side declares in `android_main.cpp`.
 *
 * Movement goes through SDL's normal joystick path, so OpenMW's ICS bindings,
 * its analogue movement code, the "player controls enabled" gating and the
 * controller menu support all behave exactly as they do for a real pad. No
 * parallel input system is introduced, and nothing here touches keyboard or
 * mouse handling.
 */
internal object GamepadEmulator {

    /** Random device id that cannot clash with a real controller. */
    private const val DEVICE_ID = 1384510555

    /**
     * Axes advertised for the emulated pad: left stick, right stick and both
     * triggers, matching the mapping registered by `android_main.cpp`.
     */
    private const val AXIS_COUNT = 6

    private var registered = false

    private fun ensureRegistered() {
        if (registered)
            return
        registered = true
        SDLControllerManager.nativeAddJoystick(
            DEVICE_ID, "Virtual", "Virtual",
            0xbad, 0xf00d,
            false, -0x1,
            AXIS_COUNT, 0, 0
        )
    }

    /** Left (or right) stick, values in [-1; 1]. */
    fun updateStick(stickId: Int, x: Float, y: Float) {
        ensureRegistered()
        SDLControllerManager.onNativeJoy(DEVICE_ID, stickId * 2, x.coerceIn(-1f, 1f))
        SDLControllerManager.onNativeJoy(DEVICE_ID, stickId * 2 + 1, y.coerceIn(-1f, 1f))
    }

    /**
     * Zeroes both sticks without creating the virtual pad when the overlay
     * never used it, so a keyboard/mouse player never sees a phantom
     * controller appear in the game.
     */
    fun releaseAllIfActive() {
        if (!registered)
            return
        updateStick(0, 0f, 0f)
        updateStick(1, 0f, 0f)
    }
}
