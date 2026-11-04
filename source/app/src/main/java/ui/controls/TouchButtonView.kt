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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A single round touch action button.
 *
 * It draws itself instead of using a background drawable, so pressing it costs
 * one invalidate of a small view: no state list resolution, no alpha layer and
 * no bitmap scaling. Presses are reported to [listener] as down/up pairs so
 * the caller can hold keys down (sneak, block) as well as send taps.
 */
class TouchButtonView @JvmOverloads constructor(
    context: Context,
    private val label: String = "",
    private val icon: Drawable? = null
) : View(context) {

    interface Listener {
        /** A finger went down on this button. */
        fun onButtonDown(view: TouchButtonView)

        /** The last finger left this button. */
        fun onButtonUp(view: TouchButtonView)

        /**
         * The finger holding this button moved. [dx]/[dy] are the deltas since
         * the previous event in view pixels. Only used by the attack button,
         * which doubles as an analogue aim pad.
         */
        fun onButtonMove(view: TouchButtonView, dx: Float, dy: Float) {}
    }

    var listener: Listener? = null

    /** Colour used while the button is held. */
    var accentColor: Int = ControlStyle.ACCENT_COLOR

    /** Show a "held" state (sneak toggled on, weapon drawn ...). */
    var toggled: Boolean = false
        set(value) {
            if (field == value)
                return
            field = value
            invalidate()
        }

    var controlAlpha: Float = 0.85f
        set(value) {
            val clamped = value.coerceIn(0.15f, 1f)
            if (field == clamped)
                return
            field = clamped
            alpha = clamped
        }

    /** When the icon is a themed vector it is tinted; custom artwork is not. */
    var tintIcon: Boolean = true

    private var pressed = false
    private var pointerId: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    /** Drag-style buttons are not released when the finger slides out. */
    var releaseOnExit: Boolean = false

    init {
        isClickable = true
        isFocusable = false
        // Controllers must keep focus on the SDL surface.
        isFocusableInTouchMode = false
        alpha = controlAlpha
    }

    fun setIcon(newIcon: Drawable?) {
        iconDrawable = newIcon
        invalidate()
    }

    private var iconDrawable: Drawable? = icon

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.9f
        val active = pressed || toggled

        ControlStyle.drawButtonFace(
            canvas,
            cx,
            cy,
            radius,
            pressed = active,
            accent = accentColor,
            strokeWidth = radius * 0.07f
        )

        val iconSize = radius * 1.15f
        val drawable = iconDrawable
        if (drawable != null) {
            val tint = when {
                !tintIcon -> ControlStyle.GLYPH_COLOR
                active -> ControlStyle.ACCENT_COLOR
                else -> ControlStyle.GLYPH_COLOR
            }
            ControlStyle.drawIcon(canvas, drawable, cx, cy, iconSize, tint, tintIcon)
        } else if (label.isNotEmpty()) {
            ControlStyle.drawLabel(
                canvas,
                cx,
                cy,
                label,
                radius * 0.8f,
                if (active) ControlStyle.ACCENT_COLOR else ControlStyle.GLYPH_COLOR
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Android splits multi-touch events per view, so the pointer
                // that started this gesture is not necessarily index 0.
                pointerId = event.getPointerId(event.actionIndex)
                lastX = event.x
                lastY = event.y
                press()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing on this button while another finger
                // holds a different control. Adopt it if we are free.
                if (pointerId == null && event.pointerCount > 0) {
                    pointerId = event.getPointerId(event.actionIndex)
                    lastX = event.getX(event.actionIndex)
                    lastY = event.getY(event.actionIndex)
                    press()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = pointerId?.let { event.findPointerIndex(it) } ?: -1
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    if (dx != 0f || dy != 0f)
                        listener?.onButtonMove(this, dx, dy)
                }
                if (releaseOnExit && !containsLocal(event.x, event.y))
                    release()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId)
                    release()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                release()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun containsLocal(x: Float, y: Float): Boolean =
        x >= 0 && y >= 0 && x <= width && y <= height

    private fun press() {
        if (pressed)
            return
        pressed = true
        isPressed = true
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
        listener?.onButtonDown(this)
    }

    private fun release() {
        if (!pressed && pointerId == null)
            return
        pressed = false
        pointerId = null
        isPressed = false
        invalidate()
        listener?.onButtonUp(this)
    }

    /** Releases the button from code (used when the overlay hides). */
    fun cancel() {
        release()
    }
}
