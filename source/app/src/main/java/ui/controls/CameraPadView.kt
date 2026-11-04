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
import android.graphics.Paint
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import org.libsdl.app.SDLActivity

/**
 * Dedicated right-hand camera area.
 *
 * The old build reused a second joystick view that *also* drew a base circle
 * and grabbed every touch on the right half of the screen, which fought with
 * the action buttons and the attack control. This view is invisible, covers the
 * empty part of the right edge, and only turns drags into relative mouse
 * motion - exactly the path OpenMW's mouse-look already uses, so no new input
 * plumbing is needed.
 *
 * Two properties keep it out of the way of combat:
 *  - it is added *below* the buttons in the layout, and Android delivers a
 *    touch to the topmost child first, so a touched button always wins;
 *  - once a finger is claimed by a button, that view keeps receiving its
 *    events even if it slides across this pad.
 *
 * A small movement threshold means a tap in the empty area never nudges the
 * view, and the movement is expressed in dp so the feel is identical on a phone
 * and on a tablet.
 */
class CameraPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var pointerId: Int? = null
    private var lastX = 0f
    private var lastY = 0f

    /** Total drag distance, used to swallow accidental taps. */
    private var travel = 0f

    private var sensitivity = 1.0f
    private var invertX = false
    private var invertY = false

    /** Relative mouse pixels per dp of finger travel at 100% sensitivity. */
    private val pixelsPerDp = 3.2f

    /** Finger travel (in px) below which nothing is sent at all. */
    private val minPixelsBeforeMove = 3f

    private val density = context.resources.displayMetrics.density

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ControlStyle.ACCENT_COLOR
        alpha = 150
    }

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /** Reads sensitivity/inversion; the editor writes them. */
    fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        sensitivity = ControlSettings.cameraSensitivity(prefs)
        invertX = ControlSettings.cameraInvertX(prefs)
        invertY = ControlSettings.cameraInvertY(prefs)
    }

    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    fun setInversions(invertXAxis: Boolean, invertYAxis: Boolean) {
        invertX = invertXAxis
        invertY = invertYAxis
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(event.actionIndex)
                lastX = event.x
                lastY = event.y
                travel = 0f
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Adopt a finger when the previous one went to a button.
                if (pointerId == null) {
                    pointerId = event.getPointerId(event.actionIndex)
                    lastX = event.getX(event.actionIndex)
                    lastY = event.getY(event.actionIndex)
                    travel = 0f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = pointerId?.let { event.findPointerIndex(it) } ?: -1
                if (index < 0)
                    return true

                val x = event.getX(index)
                val y = event.getY(index)
                val dx = x - lastX
                val dy = y - lastY

                travel += abs(dx) + abs(dy)
                if (travel < minPixelsBeforeMove)
                    return true

                var movementX = dx / density * pixelsPerDp * sensitivity
                var movementY = dy / density * pixelsPerDp * sensitivity

                if (invertX) movementX = -movementX
                if (invertY) movementY = -movementY

                val roundedX = movementX.roundToInt()
                val roundedY = movementY.roundToInt()
                if (roundedX != 0 || roundedY != 0)
                    SDLActivity.sendRelativeMouseMotion(roundedX, roundedY)

                lastX = x
                lastY = y
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId)
                    pointerId = null
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerId = null
                travel = 0f
                return true
            }
        }
        return true
    }

    private var highlight = false

    /** Outlines the pad while the layout editor is open. */
    fun setHighlight(enabled: Boolean) {
        if (highlight == enabled)
            return
        highlight = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!highlight)
            return
        canvas.drawRect(3f, 3f, width - 3f, height - 3f, highlightPaint)
        ControlStyle.drawLabel(
            canvas,
            width / 2f,
            height * 0.25f,
            "CAMERA",
            height * 0.08f,
            0x88F0C060.toInt()
        )
    }
}
