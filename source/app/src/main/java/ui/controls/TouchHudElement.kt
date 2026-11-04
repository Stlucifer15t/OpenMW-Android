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
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.preference.PreferenceManager
import android.view.View
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import constants.Constants
import java.io.File

/**
 * Visibility is used as a bitmask.
 */
enum class OscVisibility(val v: Int) {
    // Mark as should not be touched by visibility handling
    NULL(0),
    // Widgets that must stay visible while a menu is open
    ESSENTIAL(1),
    // Widgets visible during gameplay
    NORMAL(2),
}

/**
 * One positionable on-screen control.
 *
 * Positions and sizes are expressed in the historical 1024x768 "virtual"
 * coordinate space, so layouts written by earlier versions of this port still
 * land in the same place; everything is scaled to the real surface when laid
 * out.
 *
 * Per-element preferences keep their original `osc:<id>:` keys and add a
 * `hidden` flag so individual buttons can be switched off.
 */
open class TouchHudElement(
    /** Preference key suffix; also the label shown in the layout editor. */
    val id: String,
    var visibility: OscVisibility,
    val defaultX: Int,
    val defaultY: Int,
    val defaultSize: Int,
    val defaultOpacity: Float = 0.85f,
    /** File below `<user storage>/icons/` used as a custom icon, when present. */
    val iconAsset: String? = null
) {

    var x = defaultX
    var y = defaultY
    var size = defaultSize
    var opacity = defaultOpacity
    var hidden = false

    var view: View? = null
        private set

    /** Context captured the first time the element is placed or loaded. */
    protected var context: Context? = null

    /** False for controls that are configured from their own dialog. */
    open val resizable: Boolean = true

    /** Size limits, in virtual units; overridden by the wider/taller elements. */
    open val minSize: Int get() = 32
    open val maxSize: Int get() = 400

    /**
     * While the layout editor is open every control is shown, including the
     * ones the player hid, so they can be moved back or restored.
     */
    var editorMode = false

    protected open fun createView(ctx: Context): View = View(ctx)

    /** Called after the view exists but before it is attached to the layout. */
    protected open fun onViewCreated(v: View) {}

    fun place(target: RelativeLayout) {
        if (view != null)
            return
        context = target.context
        val v = createView(target.context)
        v.tag = this
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        onViewCreated(v)
        view = v
        target.addView(v)
        updateView()
    }

    fun placeConfigurable(target: RelativeLayout, listener: View.OnTouchListener) {
        place(target)
        view?.setOnTouchListener(listener)
    }

    open fun updateView() {
        val v = view ?: return
        val parent = v.parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0)
            return

        // Positions stretch with the screen so the controls stay in their
        // corners, but the *size* follows the shortest axis: on a 20:9 phone
        // scaling by width would make every button taller than it is meant to
        // be and push the lower row off the screen.
        val realX = x * parentWidth / VIRTUAL_SCREEN_WIDTH
        val realY = y * parentHeight / VIRTUAL_SCREEN_HEIGHT
        val realSize = (size.toFloat() * parentHeight / VIRTUAL_SCREEN_HEIGHT).toInt()
            .coerceAtLeast(24)

        val params = RelativeLayout.LayoutParams(realSize, realSize)
        params.leftMargin = realX
        params.topMargin = realY
        v.layoutParams = params
        v.alpha = opacity
        applyHiddenState(v)
    }

    protected fun applyHiddenState(v: View) {
        // INVISIBLE keeps the element laid out (so the editor can still target
        // it) while GONE would collapse the control entirely during play.
        v.visibility = if (hidden && !editorMode) View.INVISIBLE else View.VISIBLE
    }

    open fun changeOpacity(delta: Float) {
        opacity = (opacity + delta).coerceIn(0.15f, 1f)
        savePrefs()
        updateView()
    }

    open fun changeSize(delta: Int) {
        if (!resizable)
            return
        size = (size + delta).coerceIn(minSize, maxSize)
        savePrefs()
        updateView()
    }

    open fun changePosition(virtualX: Int, virtualY: Int) {
        // Keep the whole control reachable: it must never be dragged partly or
        // entirely off the screen where it cannot be grabbed again.
        x = virtualX.coerceIn(0, (VIRTUAL_SCREEN_WIDTH - size).coerceAtLeast(0))
        y = virtualY.coerceIn(0, (VIRTUAL_SCREEN_HEIGHT - size).coerceAtLeast(0))
        savePrefs()
        updateView()
    }

    open fun setHidden(value: Boolean) {
        hidden = value
        savePrefs()
        view?.let { applyHiddenState(it) }
    }

    fun loadPrefs(ctx: Context) {
        context = ctx
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        opacity = prefs.getFloat(ControlSettings.opacityKey(id), defaultOpacity).coerceIn(0.15f, 1f)
        size = prefs.getInt(ControlSettings.sizeKey(id), defaultSize).coerceIn(minSize, maxSize)
        x = prefs.getInt(ControlSettings.xKey(id), defaultX)
        y = prefs.getInt(ControlSettings.yKey(id), defaultY)
        hidden = prefs.getBoolean(ControlSettings.hiddenKey(id), false)
        updateView()
    }

    fun savePrefs() {
        val ctx = context ?: view?.context ?: return
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putFloat(ControlSettings.opacityKey(id), opacity)
            .putInt(ControlSettings.sizeKey(id), size)
            .putInt(ControlSettings.xKey(id), x)
            .putInt(ControlSettings.yKey(id), y)
            .putBoolean(ControlSettings.hiddenKey(id), hidden)
            .putBoolean(ControlSettings.KEY_CUSTOM_LAYOUT, true)
            .apply()
    }

    fun resetPrefs(ctx: Context) {
        Preferences.remove(this, ctx)
        opacity = defaultOpacity
        size = defaultSize
        x = defaultX
        y = defaultY
        hidden = false
        updateView()
    }

    /** Custom icon from the user's icon directory, if they installed one. */
    protected fun customIcon(ctx: Context): Drawable? {
        val name = iconAsset ?: return null
        val file = File(Constants.USER_FILE_STORAGE + "/icons/" + name)
        if (!file.exists())
            return null
        return try {
            // The tabler/md icons the old overlay documented are 128px white
            // glyphs; they are tinted like the stock artwork so one icon set
            // matches every button.
            BitmapDrawable(ctx.resources, BitmapFactory.decodeFile(file.absolutePath)).mutate()
        } catch (e: Exception) {
            // A corrupt or unreadable custom icon must never take the HUD down;
            // the caller falls back to the bundled vector.
            null
        }
    }

    protected fun icon(ctx: Context, resId: Int): Drawable? =
        customIcon(ctx) ?: ContextCompat.getDrawable(ctx, resId)?.mutate()

    /** Human readable name, used by the layout editor. */
    fun displayName(ctx: Context): String {
        val resId = ctx.resources.getIdentifier("control_$id", "string", ctx.packageName)
        return if (resId != 0) {
            try {
                ctx.getString(resId)
            } catch (e: Exception) {
                id
            }
        } else {
            id
        }
    }

    /** True when [icon] came from the user's own icon folder. */
    protected fun usesCustomIcon(ctx: Context): Boolean {
        val name = iconAsset ?: return false
        return File(Constants.USER_FILE_STORAGE + "/icons/" + name).exists()
    }
}

/** Preference key helpers shared by the element classes. */
internal object Preferences {
    fun remove(element: TouchHudElement, ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(ControlSettings.opacityKey(element.id))
            .remove(ControlSettings.sizeKey(element.id))
            .remove(ControlSettings.xKey(element.id))
            .remove(ControlSettings.yKey(element.id))
            .remove(ControlSettings.hiddenKey(element.id))
            .apply()
    }
}

/**
 * A round action button.
 *
 * @param iconRes vector icon drawn inside the button
 * @param label fallback glyph when no icon resource is given
 * @param accent colour used while the button is held
 */
open class ActionButtonElement(
    id: String,
    visibility: OscVisibility,
    x: Int,
    y: Int,
    size: Int,
    private val iconRes: Int?,
    private val label: String,
    private val accent: Int = ControlStyle.ACCENT_COLOR,
    opacity: Float = 0.9f,
    iconAsset: String? = null,
    private val onPress: (TouchButtonView) -> Unit = {},
    private val onRelease: (TouchButtonView) -> Unit = {},
    private val onMove: (TouchButtonView, Float, Float) -> Unit = { _, _, _ -> }
) : TouchHudElement(id, visibility, x, y, size, opacity, iconAsset) {

    var button: TouchButtonView? = null
        private set

    override fun createView(ctx: Context): View {
        val custom = usesCustomIcon(ctx)
        val v = TouchButtonView(ctx, label, iconRes?.let { icon(ctx, it) })
        v.tintIcon = !custom
        v.accentColor = accent
        v.controlAlpha = opacity
        v.listener = object : TouchButtonView.Listener {
            override fun onButtonDown(view: TouchButtonView) = onPress(view)
            override fun onButtonUp(view: TouchButtonView) = onRelease(view)
            override fun onButtonMove(view: TouchButtonView, dx: Float, dy: Float) = onMove(view, dx, dy)
        }
        button = v
        return v
    }

    override fun updateView() {
        super.updateView()
        button?.controlAlpha = opacity
    }

    /** Enables/disables the "held" look (sneak on, weapon drawn, ...). */
    fun setToggled(value: Boolean) {
        button?.toggled = value
    }
}

/** The movable analogue stick. */
class StickElement(
    id: String,
    visibility: OscVisibility,
    x: Int,
    y: Int,
    size: Int,
    private val stickId: Int
) : TouchHudElement(id, visibility, x, y, size, 0.95f) {

    var stick: VirtualStick? = null
        private set

    // A stick smaller than this is unusable; larger than that covers the screen
    // on a phone.
    override val minSize: Int get() = 140
    override val maxSize: Int get() = 360

    override fun createView(ctx: Context): View {
        val v = VirtualStick(ctx)
        v.setStick(stickId)
        v.setControlAlpha(0.95f)
        v.loadSettings()
        stick = v
        return v
    }

    override fun updateView() {
        super.updateView()
        stick?.setControlAlpha(opacity)
    }

    fun releaseStick() {
        stick?.release()
    }
}

/** The invisible right-hand camera/look area. */
class CameraPadElement(
    id: String,
    visibility: OscVisibility,
    x: Int,
    y: Int,
    size: Int
) : TouchHudElement(id, visibility, x, y, size, 1.0f) {

    var pad: CameraPadView? = null
        private set

    /** The camera area is sized from the camera dialog, not by dragging. */
    override val resizable: Boolean = false

    override val minSize: Int get() = 120
    override val maxSize: Int get() = VIRTUAL_SCREEN_WIDTH

    override fun createView(ctx: Context): View {
        val v = CameraPadView(ctx)
        v.loadSettings()
        pad = v
        return v
    }

    /** The pad spans from `y` to the bottom of the screen. */
    override fun updateView() {
        val v = view ?: return
        val parent = v.parent as? View ?: return
        val w = parent.width
        val h = parent.height
        if (w <= 0 || h <= 0)
            return
        val realX = x * w / VIRTUAL_SCREEN_WIDTH
        val realY = y * h / VIRTUAL_SCREEN_HEIGHT
        val realW = (size.toFloat() * w / VIRTUAL_SCREEN_WIDTH).toInt()
        val params = RelativeLayout.LayoutParams(realW.coerceAtLeast(1), (h - realY).coerceAtLeast(1))
        params.leftMargin = realX
        params.topMargin = realY
        v.layoutParams = params
        applyHiddenState(v)
    }

    fun reloadSettings() {
        pad?.loadSettings()
    }

    /** Outlines the pad while the layout editor is open. */
    fun setEditorHighlight(enabled: Boolean) {
        pad?.setHighlight(enabled)
    }
}
