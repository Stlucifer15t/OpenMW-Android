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
import android.preference.PreferenceManager
import org.json.JSONObject

/**
 * Saves and restores whole control layouts.
 *
 * A saved layout is the exact set of `osc:` values the overlay reads at
 * startup, serialised into one JSON string in the application preferences.
 * That means "save", "load" and the built-in presets all go through the very
 * same code path the game uses when it starts, and a restored layout is
 * indistinguishable from one the player dragged by hand.
 */
object ControlLayoutStore {

    private const val PREFS_SLOT_PREFIX = "osc:layout:slot:"
    private const val PREF_AUTOSAVE = "osc:layout:autosave"

    /** Names of the user's saved slots, newest first. */
    fun savedNames(ctx: Context): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return prefs.getStringSet(PREFS_SLOT_PREFIX + "names", emptySet())
            ?.sorted()
            ?: emptyList()
    }

    /** Serialises every control-related preference key. */
    fun exportCurrent(ctx: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = JSONObject()
        for ((key, value) in prefs.all) {
            if (!key.startsWith("osc:"))
                continue
            if (key.startsWith(PREFS_SLOT_PREFIX) || key == PREF_AUTOSAVE)
                continue
            when (value) {
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Boolean -> json.put(key, value)
                is String -> json.put(key, value)
            }
        }
        return json.toString()
    }

    /** Writes an exported layout back into the preferences. */
    fun importLayout(ctx: Context, json: String): Boolean {
        val parsed = try {
            JSONObject(json)
        } catch (e: Exception) {
            return false
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val editor = prefs.edit()
        // Drop the current layout first so a restored layout cannot inherit
        // stale keys for controls it does not mention.
        for (key in prefs.all.keys) {
            if (!key.startsWith("osc:"))
                continue
            // Saved slots and the undo snapshot are not part of a layout.
            if (key.startsWith(PREFS_SLOT_PREFIX) || key == PREF_AUTOSAVE)
                continue
            editor.remove(key)
        }
        for (key in parsed.keys()) {
            if (!key.startsWith("osc:"))
                continue
            val value = parsed.opt(key)
            when (value) {
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
        return true
    }

    /** Saves the current layout as [name]. */
    fun save(ctx: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty())
            return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val names = savedNames(ctx).toMutableSet()
        names.add(trimmed)
        prefs.edit()
            .putString(PREFS_SLOT_PREFIX + trimmed, exportCurrent(ctx))
            .putStringSet(PREFS_SLOT_PREFIX + "names", names)
            .commit()
        return true
    }

    fun delete(ctx: Context, name: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val names = savedNames(ctx).toMutableSet()
        if (!names.remove(name))
            return false
        prefs.edit()
            .remove(PREFS_SLOT_PREFIX + name)
            .putStringSet(PREFS_SLOT_PREFIX + "names", names)
            .commit()
        return true
    }

    /** Restores a previously saved layout. */
    fun load(ctx: Context, name: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = prefs.getString(PREFS_SLOT_PREFIX + name, null) ?: return false
        return importLayout(ctx, json)
    }

    /**
     * Remembers the layout right before the editor is opened. Closing the
     * editor without saving restores it, so accidental drags are recoverable.
     */
    fun snapshotForUndo(ctx: Context) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(PREF_AUTOSAVE, exportCurrent(ctx))
            .commit()
    }

    fun restoreSnapshot(ctx: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val json = prefs.getString(PREF_AUTOSAVE, null) ?: return false
        return importLayout(ctx, json)
    }
}
