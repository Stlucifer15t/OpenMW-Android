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

package ui.activity

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.libopenmw.openmw.R

import ui.controls.ControlLayoutStore
import ui.controls.ControlPreset
import ui.controls.ControlSettings
import ui.controls.Osc
import ui.controls.TouchHudElement
import ui.controls.VIRTUAL_SCREEN_HEIGHT
import ui.controls.VIRTUAL_SCREEN_WIDTH
import utils.Utils.hideAndroidControls

/**
 * Drag handler used by the editor.
 *
 * One finger on a control moves it; a second finger pinches to resize the
 * control under the first finger. Everything is written to the preferences
 * immediately, so there is no explicit "apply" step.
 */
class ConfigureCallback(activity: Activity) : View.OnTouchListener {

    var currentView: View? = null
        private set

    private val layout: RelativeLayout = activity.findViewById(R.id.controlsContainer)

    private var origX = 0.0f
    private var origY = 0.0f
    private var startX = 0.0f
    private var startY = 0.0f

    private var startDistance = 0f
    private var startSize = 0

    var onSelectionChanged: ((TouchHudElement?) -> Unit)? = null

    private fun elementOf(view: View?): TouchHudElement? = view?.tag as? TouchHudElement

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val element = elementOf(v) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                select(v)
                origX = v.x
                origY = v.y
                startX = event.rawX
                startY = event.rawY
                startSize = element.size
                startDistance = 0f
                if (!element.resizable)
                    return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2)
                    startDistance = distance(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (layout.width <= 0 || layout.height <= 0)
                    return true

                // The camera pad spans to the screen edge and is tuned from the
                // camera dialog instead of being dragged around.
                if (!element.resizable)
                    return true

                if (event.pointerCount >= 2 && startDistance > 0f) {
                    val scale = distance(event) / startDistance
                    menuResize(element, (startSize * scale).toInt())
                } else {
                    val x = ((event.rawX - startX) + origX).toInt()
                    val y = ((event.rawY - startY) + origY).toInt()
                    element.changePosition(
                        x * VIRTUAL_SCREEN_WIDTH / layout.width,
                        y * VIRTUAL_SCREEN_HEIGHT / layout.height
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                // Nothing to commit: every move already wrote the preference.
            }
        }

        return true
    }

    /** Applies a resize without rescanning every element of the parent. */
    private fun menuResize(el: TouchHudElement, requested: Int) {
        val delta = requested.coerceIn(32, 400) - el.size
        if (delta != 0)
            el.changeSize(delta)
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2)
            return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Marks the control under the last touch as the one the panel edits. */
    private fun select(v: View?) {
        currentView = v
        onSelectionChanged?.invoke(elementOf(v))
    }
}

/**
 * On-device layout editor for the touch HUD.
 *
 * Drag to move, pinch (or the size buttons) to resize, per-control opacity,
 * per-control hide/show, whole-layout presets, and named save/restore of
 * complete layouts. Camera sensitivity and the stick dead zone live in two
 * small dialogs of their own.
 */
class ConfigureControls : Activity() {

    private var callback: ConfigureCallback? = null
    private val osc = Osc()

    private lateinit var selectedLabel: TextView
    private lateinit var hideButton: TextView

    private val presetSpinner: Spinner get() = findViewById(R.id.presetSpinner)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.configure_controls)

        // Remember the layout so "Revert" can undo an accidental drag. Only on
        // a fresh editor: recreate() (after a load or a reset) must not
        // overwrite the snapshot with the state it has just restored.
        if (savedInstanceState == null)
            ControlLayoutStore.snapshotForUndo(this)

        val container: RelativeLayout = findViewById(R.id.controlsContainer)
        selectedLabel = findViewById(R.id.selectedLabel)
        hideButton = findViewById(R.id.btnHide)

        val cb = ConfigureCallback(this)
        cb.onSelectionChanged = { element -> updateSelection(element) }
        callback = cb

        osc.placeConfigurableElements(container, cb)

        // The panel is inflated before the controls are added, so without this
        // it would sit *under* them and its buttons would be unreachable where
        // a control overlaps it. Bringing it to the front also means the panel
        // can be collapsed to reach the controls it covers.
        findViewById<View>(R.id.editorPanel).bringToFront()

        setupPresets()
        updateSelection(null)
    }

    private fun setupPresets() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        adapter.add(getString(R.string.preset_none))
        for (preset in ControlPreset.values())
            adapter.add(getString(preset.titleRes))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0)
                    return
                val preset = ControlPreset.values()[position - 1]
                AlertDialog.Builder(this@ConfigureControls)
                    .setTitle(getString(preset.titleRes))
                    .setMessage(R.string.preset_replace_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        osc.applyPreset(preset)
                        presetSpinner.setSelection(0)
                        Toast.makeText(
                            this@ConfigureControls,
                            getString(R.string.preset_applied),
                            Toast.LENGTH_SHORT
                        ).show()
                        updateSelection(callback?.currentView?.tag as? TouchHudElement)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> presetSpinner.setSelection(0) }
                    .show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateSelection(element: TouchHudElement?) {
        if (element == null) {
            selectedLabel.text = getString(R.string.editor_select_hint)
            hideButton.text = getString(R.string.btn_hide)
            return
        }
        selectedLabel.text = getString(R.string.editor_selected, element.displayName(this))
        hideButton.text = getString(
            if (element.isHidden) R.string.btn_show else R.string.btn_hide
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            hideAndroidControls(this)
        }
    }

    private val selected: TouchHudElement?
        get() = callback?.currentView?.tag as? TouchHudElement

    fun clickOpacityPlus(v: View) {
        selected?.changeOpacity(0.1f)
    }

    fun clickOpacityMinus(v: View) {
        selected?.changeOpacity(-0.1f)
    }

    fun clickSizePlus(v: View) {
        selected?.changeSize(6)
    }

    fun clickSizeMinus(v: View) {
        selected?.changeSize(-6)
    }

    fun clickAllOpacityPlus(v: View) = osc.applyToAll(opacityDelta = 0.1f)

    fun clickAllOpacityMinus(v: View) = osc.applyToAll(opacityDelta = -0.1f)

    fun clickAllSizePlus(v: View) = osc.applyToAll(sizeDelta = 6)

    fun clickAllSizeMinus(v: View) = osc.applyToAll(sizeDelta = -6)

    /** Toggles the currently selected control. */
    fun clickHide(v: View) {
        val el = selected ?: return
        el.setHidden(!el.isHidden)
        // The editor keeps hidden controls on screen so they can be brought
        // back without a "show all" reset.
        callback?.currentView?.visibility = View.VISIBLE
        updateSelection(el)
    }

    /** Saves the current arrangement under a user-supplied name. */
    fun clickSaveLayout(v: View) {
        val input = EditText(this)
        input.hint = getString(R.string.layout_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_save_layout)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (ControlLayoutStore.save(this, input.text.toString()))
                    Toast.makeText(this, R.string.layout_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Restores a named layout, or the state before this editor was opened. */
    fun clickLoadLayout(v: View) {
        val entries = mutableListOf(getString(R.string.layout_revert))
        entries.addAll(ControlLayoutStore.savedNames(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_load_layout)
            .setItems(entries.toTypedArray()) { _, which ->
                val restored = if (which == 0) {
                    ControlLayoutStore.restoreSnapshot(this)
                } else {
                    ControlLayoutStore.load(this, entries[which])
                }
                if (restored) {
                    recreate()
                } else {
                    Toast.makeText(this, R.string.layout_load_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Deletes a named layout. */
    fun clickDeleteLayout(v: View) {
        val names = ControlLayoutStore.savedNames(this)
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.layout_none_saved, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete_layout)
            .setItems(names.toTypedArray()) { _, which ->
                ControlLayoutStore.delete(this, names[which])
            }
            .show()
    }

    fun clickResetControls(v: View) {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_resetControlsLayout)
            .setMessage(R.string.reset_controls_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                osc.resetElements(this)
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Camera sensitivity / inversion / swipe area. */
    fun clickCameraSettings(v: View) {
        showCameraDialog()
    }

    fun clickJoystickSettings(v: View) {
        showJoystickDialog()
    }

    private fun labelledSlider(
        content: LinearLayout,
        max: Int,
        progress: Int,
        label: (Int) -> String
    ): Pair<TextView, SeekBar> {
        val text = TextView(this)
        text.text = label(progress)
        content.addView(text)

        val bar = SeekBar(this)
        bar.max = max
        bar.progress = progress
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                text.text = label(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        content.addView(bar)
        return text to bar
    }

    private fun showCameraDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val sensitivity = (ControlSettings.cameraSensitivity(prefs) * 100).toInt()
        val area = (ControlSettings.cameraArea(prefs) * 100).toInt().coerceIn(20, 90)

        val (_, sensitivityBar) = labelledSlider(content, 200, sensitivity) {
            getString(R.string.camera_sensitivity, it)
        }
        val (_, areaBar) = labelledSlider(content, 70, area - 20) {
            getString(R.string.camera_area, it + 20)
        }

        val invertX = CheckBox(this).apply {
            text = getString(R.string.camera_invert_x)
            isChecked = ControlSettings.cameraInvertX(prefs)
        }
        content.addView(invertX)

        val invertY = CheckBox(this).apply {
            text = getString(R.string.camera_invert_y)
            isChecked = ControlSettings.cameraInvertY(prefs)
        }
        content.addView(invertY)

        AlertDialog.Builder(this)
            .setTitle(R.string.camera_settings)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putFloat(ControlSettings.KEY_CAMERA_SENSITIVITY, sensitivityBar.progress / 100f)
                    .putFloat(ControlSettings.KEY_CAMERA_AREA, (areaBar.progress + 20) / 100f)
                    .putBoolean(ControlSettings.KEY_CAMERA_INVERT_X, invertX.isChecked)
                    .putBoolean(ControlSettings.KEY_CAMERA_INVERT_Y, invertY.isChecked)
                    .apply()
                osc.applyCameraArea()
                osc.cameraElement()?.reloadSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showJoystickDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val current = (ControlSettings.joystickDeadzone(prefs) * 100).toInt().coerceIn(0, 40)
        val (_, bar) = labelledSlider(content, 40, current) {
            getString(R.string.joystick_deadzone, it)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.joystick_settings)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deadZone = bar.progress / 100f
                prefs.edit()
                    .putFloat(ControlSettings.KEY_JOYSTICK_DEADZONE, deadZone)
                    .apply()
                osc.stickElement()?.stick?.setStickDeadZone(deadZone)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Collapses the panel so controls hidden behind it can be dragged. */
    fun clickPanelToggle(v: View) {
        val body = findViewById<View>(R.id.editorPanelBody)
        val button = v as? TextView
        val collapse = body.visibility == View.VISIBLE
        body.visibility = if (collapse) View.GONE else View.VISIBLE
        button?.text = getString(if (collapse) R.string.btn_panel_expand else R.string.btn_panel_collapse)
    }

    fun clickBack(v: View) {
        finish()
    }
}
