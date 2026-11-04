/*
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

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Shared drawing helpers for the on-screen controls.
 *
 * Everything here is plain Canvas drawing: a handful of arcs and circles per
 * control per frame, no bitmaps to decode at runtime and no layout inflation.
 * That keeps the overlay cheap on low-end devices while still looking like a
 * modern mobile RPG pad (soft dark glass, a slim highlight ring and a bright
 * thumbstick).
 */
object ControlStyle {

    /** Panel colour used behind buttons and the joystick base. */
    val PANEL_COLOR = 0xFF10161C.toInt()

    /** Accent used for the "pressed" state of buttons and the joystick thumb. */
    val ACCENT_COLOR = 0xFFF0C060.toInt()

    /** Secondary accent for combat controls. */
    val COMBAT_COLOR = 0xFFDE5B3A.toInt()

    /** Text/icon colour drawn on top of a button. */
    val GLYPH_COLOR = 0xFFF2F4F6.toInt()

    /** Outline colour of the idle button ring. */
    val OUTLINE_COLOR = 0x55FFFFFF

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GLYPH_COLOR
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val rectF = RectF()
    private val bounds = Rect()

    /**
     * Draws a round glass button.
     *
     * @param pressed whether a finger is currently down on the control
     * @param accent the colour used while pressed
     */
    fun drawButtonFace(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        pressed: Boolean,
        accent: Int = ACCENT_COLOR,
        strokeWidth: Float = 3f
    ) {
        fill.color = if (pressed) blend(PANEL_COLOR, accent, 0.5f) else PANEL_COLOR
        fill.alpha = if (pressed) 215 else 165
        canvas.drawCircle(cx, cy, radius, fill)

        outline.strokeWidth = strokeWidth
        outline.color = if (pressed) accent else OUTLINE_COLOR
        canvas.drawCircle(cx, cy, radius - strokeWidth / 2f, outline)

        if (pressed) {
            outline.strokeWidth = strokeWidth * 3f
            // Same accent, a third of the opacity: a soft halo rather than a
            // second hard ring.
            outline.color = (accent and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(cx, cy, radius - strokeWidth * 1.5f, outline)
        }
    }

    /**
     * Draws the icon of a button, centred and scaled to [size] pixels. The
     * drawable's bounds are set each frame, so the same instance can be reused
     * for every size change.
     */
    fun drawIcon(
        canvas: Canvas,
        icon: Drawable?,
        cx: Float,
        cy: Float,
        size: Float,
        tint: Int = GLYPH_COLOR,
        applyTint: Boolean = true
    ) {
        if (icon == null)
            return
        val half = size / 2f
        bounds.set((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.setBounds(bounds)
        // Every stock icon - the vector art and the shipped PNGs alike - is
        // white, so tinting it keeps every button readable and lets the pressed
        // state flash in the accent colour. Player-supplied artwork is drawn
        // untinted, see TouchButtonView.tintIcon.
        if (applyTint)
            icon.setTint(tint)
        icon.draw(canvas)
    }

    /** Draws a centred text label, used for abbreviated actions. */
    fun drawLabel(canvas: Canvas, cx: Float, cy: Float, label: String, textSize: Float, color: Int = GLYPH_COLOR) {
        text.textSize = textSize
        text.color = color
        val baseline = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, cx, baseline, text)
    }

    /** Draws the ring that marks the joystick base. */
    fun drawStickBase(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        fill.color = PANEL_COLOR
        fill.alpha = alpha
        canvas.drawCircle(cx, cy, radius, fill)

        outline.strokeWidth = radius * 0.045f
        outline.color = blend(OUTLINE_COLOR, ACCENT_COLOR, 0.25f)
        canvas.drawCircle(cx, cy, radius * 0.96f, outline)

        // Cardinal ticks: four short lines that make the range readable.
        outline.strokeWidth = radius * 0.03f
        outline.color = (OUTLINE_COLOR and 0x00FFFFFF) or 0x40000000
        val tickOuter = radius * 0.9f
        val tickInner = radius * 0.74f
        for (i in 0 until 4) {
            val angle = i * Math.PI.toFloat() / 2f
            val dx = kotlin.math.cos(angle)
            val dy = kotlin.math.sin(angle)
            canvas.drawLine(cx + dx * tickInner, cy + dy * tickInner, cx + dx * tickOuter, cy + dy * tickOuter, outline)
        }
    }

    /** Draws the movable thumb of the joystick. */
    fun drawStickThumb(canvas: Canvas, cx: Float, cy: Float, radius: Float, active: Boolean) {
        fill.color = if (active) blend(GLYPH_COLOR, ACCENT_COLOR, 0.55f) else GLYPH_COLOR
        fill.alpha = if (active) 235 else 170
        canvas.drawCircle(cx, cy, radius, fill)

        outline.strokeWidth = radius * 0.16f
        outline.color = blend(PANEL_COLOR, ACCENT_COLOR, if (active) 0.7f else 0.25f)
        canvas.drawCircle(cx, cy, radius - outline.strokeWidth / 2f, outline)
    }

    /** Linearly blends two ARGB colours. */
    fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }
}

/**
 * Access to the user-tweakable part of the touch layout that is shared by the
 * gameplay overlay and the layout editor: per-element size/opacity/visibility,
 * joystick tuning and the right-side camera pad.
 *
 * Element positions and the hide flags live in the same `osc:` keys the
 * original controls used, so existing layouts keep working.
 */
object ControlSettings {

    const val KEY_CAMERA_SENSITIVITY = "osc:camera:sensitivity"
    const val KEY_CAMERA_INVERT_X = "osc:camera:invertX"
    const val KEY_CAMERA_INVERT_Y = "osc:camera:invertY"
    const val KEY_CAMERA_AREA = "osc:camera:area"
    const val KEY_JOYSTICK_DEADZONE = "osc:joystick:deadzone"
    const val KEY_CUSTOM_LAYOUT = "osc:customLayout"

    fun opacityKey(id: String) = "osc:$id:opacity"
    fun sizeKey(id: String) = "osc:$id:size"
    fun xKey(id: String) = "osc:$id:x"
    fun yKey(id: String) = "osc:$id:y"
    fun hiddenKey(id: String) = "osc:$id:hidden"

    fun cameraSensitivity(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_CAMERA_SENSITIVITY, 1.0f)

    fun cameraInvertX(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CAMERA_INVERT_X, false)

    fun cameraInvertY(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CAMERA_INVERT_Y, false)

    /** Share of the screen width (from the right edge) used by the camera pad. */
    fun cameraArea(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_CAMERA_AREA, 0.45f)

    /** Dead zone of the virtual stick, as a fraction of its travel. */
    fun joystickDeadzone(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_JOYSTICK_DEADZONE, 0.08f)
}
