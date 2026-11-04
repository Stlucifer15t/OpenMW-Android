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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Compact analogue movement stick.
 *
 * The old floating circle spanned most of the lower-left screen and only
 * reported a direction once the finger was far away from where it landed.
 * This one always shows a base plate with a thumb that follows the finger
 * inside the base radius, supports a proper 360 degree range and feeds the
 * very same virtual-gamepad path the rest of the port already uses
 * ([GamepadEmulator] -> SDL left stick), so no new input plumbing is needed.
 *
 * The view only owns the *left* stick; camera control is handled by
 * [CameraPadView] on the opposite side of the screen.
 */
open class VirtualStick @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 0 = left stick, 1 = right stick of the emulated pad. */
    private var stickId = 0

    /** Range [-1; 1] currently reported to the game. */
    private var valueX = 0f
    private var valueY = 0f

    private var pointerId: Int? = null
    private var down = false

    // Centre of the base in view-local coordinates.
    private var baseX = 0f
    private var baseY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var thumbX = 0f
    private var thumbY = 0f

    /** Dead zone as a fraction of the travel. */
    private var deadZone = 0.08f

    /**
     * When true the base follows the first touch instead of staying fixed.
     * Fixed is the default because it matches modern mobile shooters/RPGs and
     * keeps the controls from wandering around during combat.
     */
    var floating = false

    private var opacity = 1.0f

    private var onChange: ((Float, Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        isFocusable = false
        // Hardware controllers must never move focus onto the overlay.
        isFocusableInTouchMode = false
    }

    fun setStick(id: Int) {
        stickId = id
    }

    /** Registers a listener for stick value changes (used by the layout editor preview). */
    fun setOnValueChanged(listener: (Float, Float) -> Unit) {
        onChange = listener
    }

    fun setStickDeadZone(fraction: Float) {
        deadZone = fraction.coerceIn(0f, 0.6f)
        if (!down)
            return
        // Re-evaluate the current deflection against the new dead zone. The
        // sentinel makes sure the change is reported even when the thumb has
        // not moved.
        valueX = 2f
        valueY = 2f
        updateFromTouch(thumbX, thumbY)
    }

    /** Reads the persisted dead zone once the view is attached. */
    fun loadSettings() {
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        setStickDeadZone(ControlSettings.joystickDeadzone(prefs))
    }

    /** Overrides the drawing opacity, 0..1 (the layout's per-element alpha). */
    fun setControlAlpha(alpha: Float) {
        opacity = alpha.coerceIn(0.15f, 1f)
        invalidate()
    }

    /** Zeroes the stick and releases the emulated axes. */
    fun release() {
        if (!down && valueX == 0f && valueY == 0f)
            return
        down = false
        pointerId = null
        valueX = 0f
        valueY = 0f
        onStickMoved(0f, 0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseRadius = minOf(w, h) * 0.5f
        baseX = w * 0.5f
        baseY = h * 0.5f
        thumbRadius = baseRadius * 0.42f
        if (!down)
            resetThumb()
    }

    private fun resetThumb() {
        thumbX = baseX
        thumbY = baseY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseRadius <= 0f)
            return

        val alpha = (255 * opacity).toInt().coerceIn(0, 255)

        ControlStyle.drawStickBase(canvas, baseX, baseY, baseRadius, (alpha * 0.45f).toInt())
        ControlStyle.drawStickThumb(canvas, thumbX, thumbY, thumbRadius, down)

        if (down) {
            // Directional hint from the centre to the thumb.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = baseRadius * 0.06f
            paint.color = ControlStyle.ACCENT_COLOR
            paint.alpha = (alpha * 0.45f).toInt()
            path.reset()
            path.moveTo(baseX, baseY)
            path.lineTo(thumbX, thumbY)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(event.actionIndex)
                down = true
                if (floating) {
                    // Re-centre the plate under the finger, clamped into the view.
                    baseX = event.x.coerceIn(baseRadius, width - baseRadius)
                    baseY = event.y.coerceIn(baseRadius, height - baseRadius)
                }
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Another control took the first finger; adopt this one so the
                // stick keeps working as long as any finger holds it.
                if (pointerId == null) {
                    pointerId = event.getPointerId(event.actionIndex)
                    down = true
                    updateFromTouch(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = pointerId?.let { event.findPointerIndex(it) } ?: -1
                if (index < 0)
                    return true
                updateFromTouch(event.getX(index), event.getY(index))
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    release()
                    isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                release()
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        var dx = x - baseX
        var dy = y - baseY

        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val travel = baseRadius * 0.92f
        if (distance > travel && distance > 0f) {
            dx *= travel / distance
            dy *= travel / distance
        }
        thumbX = baseX + dx
        thumbY = baseY + dy

        // Normalise to [-1; 1] and cut out the dead zone so small thumb
        // movements do not make the character creep.
        var nx = if (travel > 0f) dx / travel else 0f
        var ny = if (travel > 0f) dy / travel else 0f
        val magnitude = kotlin.math.sqrt(nx * nx + ny * ny)
        if (magnitude <= deadZone || magnitude == 0f) {
            nx = 0f
            ny = 0f
        } else {
            val scaled = ((magnitude - deadZone) / (1f - deadZone)) / magnitude
            nx *= scaled
            ny *= scaled
        }

        // Modern pads are much more forgiving than the old square/dead-zone
        // hybrid: a round gate keeps diagonal speeds identical to cardinal
        // ones, which is what the game expects from an analogue stick.
        // The gate is already circular, so a plain clamp cannot cut corners.
        nx = nx.coerceIn(-1f, 1f)
        ny = ny.coerceIn(-1f, 1f)

        if (nx == valueX && ny == valueY)
            return

        valueX = nx
        valueY = ny
        onStickMoved(nx, ny)
    }

    private fun onStickMoved(x: Float, y: Float) {
        if (stickId >= 0)
            GamepadEmulator.updateStick(stickId, x, y)
        onChange?.invoke(x, y)
    }
}

/**
 * Kept for source compatibility: the overlay and the prefs store refer to the
 * left joystick. Pointer handling lives in [VirtualStick].
 */
class JoystickLeft @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : VirtualStick(context, attrs, defStyle)
