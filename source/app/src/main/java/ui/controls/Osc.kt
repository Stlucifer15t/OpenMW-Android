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

import android.content.Context
import android.preference.PreferenceManager
import android.view.KeyEvent
import android.view.View
import android.widget.RelativeLayout
import com.libopenmw.openmw.R
import org.libsdl.app.SDLActivity

/** Historical virtual coordinate space all default layouts are authored in. */
const val VIRTUAL_SCREEN_WIDTH = 1024
const val VIRTUAL_SCREEN_HEIGHT = 768

/** Default analogue stick diameter, in virtual units. */
const val JOYSTICK_SIZE = 240

/**
 * Keys the overlay sends for the shortcut bar. The ones with no default OpenMW
 * binding are consumed by the companion bridge script
 * (scripts/touchhud/player.lua) to open a specific inventory page.
 */
private const val ATTACK_DRAG_SCALE = 6f

private val KEY_INVENTORY_PAGE = KeyEvent.KEYCODE_U
private val KEY_JOURNAL_PAGE = KeyEvent.KEYCODE_K
private val KEY_STATS_PAGE = KeyEvent.KEYCODE_F6
private val KEY_MAGIC_PAGE = KeyEvent.KEYCODE_F7
private val KEY_MAP_PAGE = KeyEvent.KEYCODE_F8

/**
 * The Android touch HUD.
 *
 * Z-order, bottom to top:
 *  1. [cameraPad]  invisible right-hand camera area
 *  2. [stick]      compact analogue movement stick
 *  3. action buttons and the shortcut bar
 *
 * Buttons are added after the camera pad, and Android dispatches a touch to the
 * most recently added child first, so a touched button always wins: the camera
 * can never steal a combat press, and the finger that grabbed a button keeps
 * steering that button even if it slides over the pad.
 *
 * Every action is delivered through an existing OpenMW input path:
 *  - movement    -> virtual gamepad left stick (OpenMW analogue movement)
 *  - attack      -> left mouse button ("Use", the default attack binding)
 *  - block       -> the same attack input held down (block pose)
 *  - jump        -> E, the default "Jump" binding
 *  - sneak       -> Left Ctrl, the default "Sneak" binding
 *  - ready weapon/magic -> F / R, the default bindings
 *  - activate    -> Space, the default "Activate" binding
 *  - shortcut bar-> regular key events (the bridge script handles the rest)
 *  - camera      -> relative mouse motion, the same path mouse-look uses
 */
class Osc {

    private val osk = Osk()

    /** Mode where only the on-screen keyboard is visible. */
    var keyboardVisible = false

    /** Mouse mode: the game shows its own pointer, so only menus stay usable. */
    var mouseVisible = false

    private var visibilityState = -1

    private var context: Context? = null

    private val elements = arrayListOf<TouchHudElement>()

    private var cameraPad: CameraPadElement? = null
    private var stick: StickElement? = null
    private var attackButton: ActionButtonElement? = null

    private var attackDownAt = 0L
    private var attackDragging = false

    /**
     * The attack and block buttons both hold the "Use" mouse button. A plain
     * reference count keeps the engine from seeing a release while the other
     * button is still held down.
     */
    private var useHolds = 0

    /** True between placeConfigurableElements() and the editor's finish(). */
    private var editing = false

    // ---------------------------------------------------------------- layout

    private fun ensureElements() {
        if (elements.isNotEmpty())
            return

        // 1. Camera area, added first so it stays underneath every button.
        val camera = CameraPadElement("cameraPad", OscVisibility.NORMAL, 604, 0, 420)
        cameraPad = camera
        elements.add(camera)

        // 2. Movement stick, bottom left.
        val movement = StickElement(
            "joystickLeft", OscVisibility.NORMAL,
            40, 488, JOYSTICK_SIZE, 0
        )
        stick = movement
        elements.add(movement)

        // 3. Combat cluster: the big attack thumb first, around it the
        //    supporting actions.
        val attack = ActionButtonElement(
            "attack", OscVisibility.NORMAL,
            760, 512, 152,
            R.drawable.oc_attack, "A", ControlStyle.COMBAT_COLOR, 0.95f,
            iconAsset = "attack.png",
            onPress = { beginAttack() },
            onRelease = { endAttack() },
            onMove = { _, dx, dy -> dragAttack(dx, dy) }
        )
        attackButton = attack
        elements.add(attack)

        // Guard button. Morrowind has no dedicated block input: the engine
        // resolves a block whenever the player has a shield in the left hand,
        // the guard is "up" (the Use control held) and the attacker is in
        // front. Holding this button keeps the guard up, which is the block
        // gesture the desktop click performs, while a tap on the big attack
        // button is a single swing.
        elements.add(
            ActionButtonElement(
                "block", OscVisibility.NORMAL,
                668, 424, 84,
                R.drawable.oc_block, "B", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "block.png",
                onPress = { pressUse() },
                onRelease = { releaseUse() }
            )
        )

        elements.add(
            ActionButtonElement(
                "jump", OscVisibility.NORMAL,
                930, 458, 84,
                R.drawable.oc_jump, "J", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "jump.png",
                onPress = { keyDown(KeyEvent.KEYCODE_E) },
                onRelease = { keyUp(KeyEvent.KEYCODE_E) }
            )
        )

        elements.add(
            ActionButtonElement(
                "sneak", OscVisibility.NORMAL,
                930, 360, 76,
                R.drawable.oc_sneak, "S", ControlStyle.ACCENT_COLOR, 0.85f,
                iconAsset = "sneak.png",
                onPress = { keyDown(KeyEvent.KEYCODE_CTRL_LEFT) },
                onRelease = { keyUp(KeyEvent.KEYCODE_CTRL_LEFT) }
            )
        )

        elements.add(
            ActionButtonElement(
                "weapon", OscVisibility.NORMAL,
                660, 524, 84,
                R.drawable.oc_weapon, "W", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "toggle_weapon.png",
                onPress = { tapKey(KeyEvent.KEYCODE_F) },
                onRelease = {}
            )
        )

        elements.add(
            ActionButtonElement(
                "magic", OscVisibility.NORMAL,
                660, 624, 84,
                R.drawable.oc_magic, "M", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "toggle_magic.png",
                onPress = { tapKey(KeyEvent.KEYCODE_R) },
                onRelease = {}
            )
        )

        elements.add(
            ActionButtonElement(
                "activate", OscVisibility.NORMAL,
                856, 668, 88,
                R.drawable.oc_activate, "E", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "use.png",
                onPress = { tapKey(KeyEvent.KEYCODE_SPACE) },
                onRelease = {}
            )
        )

        // 4. Menu button and shortcut bar. Both stay available while a menu is
        //    open, which is how a touch player closes one again.
        elements.add(
            ActionButtonElement(
                "pause", OscVisibility.ESSENTIAL,
                16, 16, 76,
                R.drawable.oc_menu, "≡", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = "pause.png",
                onPress = { tapKey(KeyEvent.KEYCODE_ESCAPE) },
                onRelease = {}
            )
        )

        // Shortcuts live in two compact rows in the top-left corner so they
        // never overlap the camera area on the right and stay clear of the
        // combat cluster.
        val shortcuts = listOf(
            Shortcut("inventory", R.drawable.oc_inventory, "inventory.png", KEY_INVENTORY_PAGE),
            Shortcut("stats", R.drawable.oc_stats, null, KEY_STATS_PAGE),
            Shortcut("spells", R.drawable.oc_magic, "toggle_magic.png", KEY_MAGIC_PAGE),
            Shortcut("journal", R.drawable.oc_journal, "journal.png", KEY_JOURNAL_PAGE),
            Shortcut("map", R.drawable.oc_map, null, KEY_MAP_PAGE),
            Shortcut("quickSave", R.drawable.oc_save, "save.png", KeyEvent.KEYCODE_F5),
            Shortcut("quickLoad", R.drawable.oc_load, "load.png", KeyEvent.KEYCODE_F9),
            Shortcut("viewMode", R.drawable.oc_view, "third_person.png", KeyEvent.KEYCODE_TAB)
        )

        shortcuts.forEachIndexed { index, shortcut ->
            val row = index / 4
            val column = index % 4
            val element = ActionButtonElement(
                // ESSENTIAL: the shortcuts are how a touch player moves
                // between the inventory pages, so they stay visible while a
                // menu is open and go away only for the text keyboard.
                shortcut.id, OscVisibility.ESSENTIAL,
                104 + column * 66, 16 + row * 68, 60,
                shortcut.iconRes, "", ControlStyle.ACCENT_COLOR, 0.9f,
                iconAsset = shortcut.iconAsset,
                onPress = { tapKey(shortcut.keyCode) },
                onRelease = {}
            )
            elements.add(element)
        }

        // 5. Soft-keyboard toggle. It sits at the end of the shortcut rows and
        //    uses NULL visibility, so it also stays tappable while a menu (and
        //    the text keyboard) is open.
        elements.add(
            ActionButtonElement(
                "keyboard", OscVisibility.NULL,
                368, 84, 56,
                R.drawable.keyboard, "⌨", ControlStyle.ACCENT_COLOR, 0.6f,
                iconAsset = "keyboard.png",
                onPress = { toggleKeyboard() },
                onRelease = {}
            )
        )
    }

    private class Shortcut(
        val id: String,
        val iconRes: Int,
        val iconAsset: String?,
        val keyCode: Int
    )

    // ----------------------------------------------------------------- input

    private fun tapKey(keyCode: Int) {
        keyDown(keyCode)
        keyUp(keyCode)
    }

    private fun keyDown(keyCode: Int) = SDLActivity.onNativeKeyDown(keyCode)

    private fun keyUp(keyCode: Int) = SDLActivity.onNativeKeyUp(keyCode)

    /**
     * Attack presses the left mouse button, exactly like a desktop click on the
     * "Use" binding: OpenMW's playercontrols.lua turns a held "Use" into an
     * attack for the whole time it is down and into a block when the player can
     * block, so one input covers both without inventing a new action.
     */
    private fun beginAttack() {
        attackDownAt = System.currentTimeMillis()
        attackDragging = false
        pressUse()
    }

    private fun endAttack() {
        attackDragging = false
        releaseUse()
    }

    private fun pressUse() {
        if (useHolds++ == 0)
            SDLActivity.sendMouseButton(1, 1)
    }

    private fun releaseUse() {
        if (useHolds == 0)
            return
        if (--useHolds == 0)
            SDLActivity.sendMouseButton(0, 1)
    }

    /**
     * While the attack button is held the finger can also steer the camera,
     * like the old attack pad. A short grace period keeps a plain tap a plain
     * attack and keeps the block pose stable.
     */
    private fun dragAttack(dx: Float, dy: Float) {
        if (!attackDragging && System.currentTimeMillis() - attackDownAt < 120)
            return
        attackDragging = true
        // Same order of magnitude as the old attack pad (a drag across the
        // button turns the view about as far as the right-hand area does).
        SDLActivity.sendRelativeMouseMotion(
            (dx * ATTACK_DRAG_SCALE).toInt(),
            (dy * ATTACK_DRAG_SCALE).toInt()
        )
    }

    // ------------------------------------------------------------- placement

    fun placeElements(target: RelativeLayout) {
        context = target.context
        ensureElements()

        for (element in elements)
            element.place(target)
        for (element in elements) {
            element.loadPrefs(target.context)
            element.updateView()
        }

        // The soft keyboard lives in this layout too. It stays hidden until the
        // keyboard toggle is used.
        osk.placeElements(target)

        // Pick up sensitivity/inversion and the chosen swipe area.
        cameraPad?.reloadSettings()
        applyCameraArea()

        target.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob)
                relayout()
        }

        visibilityState = -1
        showBasedOnState()
    }

    fun placeConfigurableElements(target: RelativeLayout, listener: View.OnTouchListener) {
        context = target.context
        editing = true
        ensureElements()

        for (element in elements) {
            element.editorMode = true
            element.placeConfigurable(target, listener)
            element.loadPrefs(target.context)
            element.updateView()
            // The editor always shows every control so it can be moved.
            element.view?.visibility = View.VISIBLE
        }
        applyCameraArea()
        cameraPad?.setEditorHighlight(true)

        // The first updateView() happens before the container has been
        // measured, so lay out again as soon as it has a size.
        target.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob)
                relayout()
        }
    }

    private fun relayout() {
        for (element in elements)
            element.updateView()
        if (editing) {
            // The editor manages visibility itself (see setVisibility).
            cameraPad?.setEditorHighlight(true)
            return
        }
        // updateView() re-applies the per-element hide flag, so the menu state
        // has to be forced back on top of it.
        visibilityState = -1
        showBasedOnState()
    }

    fun resetElements(ctx: Context) {
        context = ctx
        ensureElements()
        for (element in elements)
            element.resetPrefs(ctx)

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(ControlSettings.KEY_CAMERA_SENSITIVITY)
            .remove(ControlSettings.KEY_CAMERA_INVERT_X)
            .remove(ControlSettings.KEY_CAMERA_INVERT_Y)
            .remove(ControlSettings.KEY_CAMERA_AREA)
            .remove(ControlSettings.KEY_JOYSTICK_DEADZONE)
            .remove(ControlSettings.KEY_CUSTOM_LAYOUT)
            .apply()

        cameraPad?.reloadSettings()
        stick?.stick?.loadSettings()
        applyCameraArea()
        relayout()
    }

    /** Applies one of the built-in layouts. */
    fun applyPreset(preset: ControlPreset) {
        ensureElements()
        preset.applyTo(this)
        for (element in elements) {
            element.hiddenState = preset.hidden.contains(element.id)
            element.savePrefs()
            element.updateView()
        }
        relayout()
    }

    // ------------------------------------------------------------ lifecycle

    fun toggleKeyboard() {
        osk.toggle()
        keyboardVisible = !keyboardVisible
        showBasedOnState()
    }

    /**
     * Hides gameplay controls while a menu owns the screen. The shortcut bar
     * and the menu button (ESSENTIAL) stay available so a menu can always be
     * closed with one tap.
     */
    fun showBasedOnState() {
        val state = when {
            // The on-screen keyboard covers the lower half of the screen.
            keyboardVisible -> 0
            mouseVisible || SDLActivity.isMouseShown() != 0 -> OscVisibility.ESSENTIAL.v
            else -> OscVisibility.ESSENTIAL.v or OscVisibility.NORMAL.v
        }
        setVisibility(state)
    }

    private fun setVisibility(newState: Int) {
        // The editor owns visibility: it must show every control, including
        // the ones the player hid, so they can be brought back.
        if (editing)
            return
        if (visibilityState == newState)
            return
        visibilityState = newState

        for (element in elements) {
            val view = element.view ?: continue
            if (element.hiddenState) {
                view.visibility = View.INVISIBLE
                continue
            }
            if (element.visibility == OscVisibility.NULL) {
                view.visibility = View.VISIBLE
                continue
            }
            // INVISIBLE rather than GONE: a GONE child is skipped by the
            // layout pass, and turning it back on would leave the control at
            // (0,0) until the next size change.
            view.visibility =
                if (newState and element.visibility.v != 0) View.VISIBLE else View.INVISIBLE
        }

        // Never leave a held key, mouse button or displaced stick behind when
        // the gameplay controls disappear behind a menu.
        if (newState and OscVisibility.NORMAL.v == 0) {
            stick?.releaseStick()
            GamepadEmulator.releaseAllIfActive()
            for (element in elements) {
                if (element is ActionButtonElement)
                    element.button?.cancel()
            }
            // Belt and braces: a button whose listener was detached mid-press
            // must not leave the attack mouse button stuck down.
            useHolds = 0
            SDLActivity.sendMouseButton(0, 1)
        }
    }

    // --------------------------------------------------------------- editor

    fun elementById(id: String): TouchHudElement? = elements.firstOrNull { it.id == id }

    fun cameraElement(): CameraPadElement? = cameraPad

    fun stickElement(): StickElement? = stick

    /** Bulk opacity/size changes, used by the editor. */
    fun applyToAll(opacityDelta: Float = 0f, sizeDelta: Int = 0) {
        for (element in elements) {
            if (opacityDelta != 0f)
                element.opacity = (element.opacity + opacityDelta).coerceIn(0.15f, 1f)
            if (sizeDelta != 0 && element.resizable)
                element.changeSize(sizeDelta)
            element.savePrefs()
            element.updateView()
        }
    }

    /**
     * Re-reads the camera panel geometry: the invisible pad covers the share of
     * the screen width the player picked in the camera dialog.
     */
    fun applyCameraArea() {
        val ctx = context ?: return
        val pad = cameraPad ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val percent = (ControlSettings.cameraArea(prefs) * 100).toInt().coerceIn(20, 90)
        val width = VIRTUAL_SCREEN_WIDTH * percent / 100
        pad.size = width
        pad.x = VIRTUAL_SCREEN_WIDTH - width
        pad.updateView()
    }
}

/**
 * Built-in layouts. A preset only writes positions/sizes/opacities into the
 * normal preference store, so it can still be adjusted afterwards.
 */
enum class ControlPreset(val titleRes: Int) {
    Classic(R.string.preset_classic),
    Compact(R.string.preset_compact),
    Reach(R.string.preset_reach);

    internal var hidden: Set<String> = emptySet()

    internal fun applyTo(osc: Osc) {
        hidden = emptySet()

        fun set(id: String, x: Int, y: Int, size: Int, opacity: Float) {
            val el = osc.elementById(id) ?: return
            el.x = x
            el.y = y
            el.size = size
            el.opacity = opacity
        }

        when (this) {
            Classic -> {
                set("joystickLeft", 40, 488, JOYSTICK_SIZE, 0.95f)
                set("attack", 760, 512, 152, 0.95f)
                set("block", 668, 424, 84, 0.9f)
                set("jump", 930, 458, 84, 0.9f)
                set("sneak", 930, 360, 76, 0.85f)
                set("weapon", 660, 524, 84, 0.9f)
                set("magic", 660, 624, 84, 0.9f)
                set("activate", 856, 668, 88, 0.9f)
            }

            Compact -> {
                // Smaller, tighter cluster: handy on small phones and for
                // players who hold the device low.
                set("joystickLeft", 32, 528, 200, 0.7f)
                set("attack", 824, 552, 132, 0.8f)
                set("block", 748, 484, 70, 0.7f)
                set("jump", 948, 492, 70, 0.7f)
                set("sneak", 948, 404, 64, 0.65f)
                set("weapon", 748, 578, 70, 0.7f)
                set("magic", 748, 656, 70, 0.7f)
                set("activate", 850, 666, 70, 0.7f)
                hidden = setOf("keyboard")
            }

            Reach -> {
                // Big targets biased to the screen edges: for one-handed play
                // and long sessions.
                set("joystickLeft", 56, 440, 288, 1.0f)
                set("attack", 732, 492, 176, 1.0f)
                set("block", 620, 396, 92, 0.95f)
                set("jump", 916, 436, 96, 0.95f)
                set("sneak", 916, 328, 88, 0.9f)
                set("weapon", 620, 500, 92, 0.95f)
                set("magic", 620, 604, 92, 0.95f)
                set("activate", 800, 664, 96, 0.95f)
            }
        }
    }
}
