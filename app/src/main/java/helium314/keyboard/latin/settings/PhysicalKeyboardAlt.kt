// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import helium314.keyboard.latin.utils.ToolbarMode

/**
 * What a lone Alt press does on a hardware keyboard.
 * An old on/off emoji switch is read once and stored as this choice.
 */
object PhysicalKeyboardAlt {
    const val NONE = "none"
    const val EMOJI = "emoji"
    const val TOOLBAR = "toolbar"

    /** True while [migrate] is writing, so that write does not reload settings from inside itself. */
    @JvmField
    var migrating: Boolean = false

    @JvmStatic
    fun read(prefs: SharedPreferences): String {
        migrate(prefs)
        if (!prefs.contains(Settings.PREF_PHYSICAL_KEYBOARD_ALT)) return NONE
        return normalize(prefs.getString(Settings.PREF_PHYSICAL_KEYBOARD_ALT, NONE))
    }

    /** Copies the old emoji switch into [Settings.PREF_PHYSICAL_KEYBOARD_ALT] when that switch is still stored. */
    @JvmStatic
    fun migrate(prefs: SharedPreferences) {
        if (prefs.contains(Settings.PREF_PHYSICAL_KEYBOARD_ALT)) return
        if (!prefs.contains(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)) return
        val value = try {
            if (prefs.getBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, false)) EMOJI else NONE
        } catch (_: ClassCastException) {
            NONE
        }
        migrating = true
        try {
            prefs.edit()
                .putString(Settings.PREF_PHYSICAL_KEYBOARD_ALT, value)
                .remove(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
                .commit()
        } finally {
            migrating = false
        }
    }

    /**
     * The next toolbar mode when Alt hides or shows the whole panel.
     * Hiding stores [current] separately and sets Hidden. Showing restores that mode.
     */
    @JvmStatic
    fun toggledToolbarMode(current: String?, savedRestore: String?): String {
        if (current == ToolbarMode.HIDDEN.name) {
            return rememberedToolbarMode(savedRestore)
        }
        return ToolbarMode.HIDDEN.name
    }

    /** Mode to put back when the panel was hidden. Hidden is not a mode to restore. */
    @JvmStatic
    fun rememberedToolbarMode(savedRestore: String?): String {
        if (savedRestore == null || savedRestore == ToolbarMode.HIDDEN.name) {
            return ToolbarMode.EXPANDABLE.name
        }
        return try {
            ToolbarMode.valueOf(savedRestore).name
        } catch (_: IllegalArgumentException) {
            ToolbarMode.EXPANDABLE.name
        }
    }

    /** Remember the mode Alt is about to replace. Hidden has nothing new to remember. */
    @JvmStatic
    fun restoreToRemember(current: String?): String? {
        if (current == null || current == ToolbarMode.HIDDEN.name) return null
        return rememberedToolbarMode(current)
    }

    /**
     * What Alt does to the hardware panel.
     * The symbols keyboard is not the toolbar. A reload would put that page back,
     * so it has to be cleared on the way to the strip and on the way to a closed window.
     * Alphabet keys stay only when the system asked for a soft keyboard.
     */
    class HardwareToolbarToggle(
        @JvmField val showPanel: Boolean,
        @JvmField val leaveSymbols: Boolean,
        @JvmField val keepAlphabetKeys: Boolean,
    )

    @JvmStatic
    fun planHardwareToolbarToggle(
        chromeShown: Boolean,
        symbolsCurrent: Boolean,
        systemSoftKeyboard: Boolean,
    ): HardwareToolbarToggle {
        if (!chromeShown) {
            return HardwareToolbarToggle(
                showPanel = true,
                leaveSymbols = symbolsCurrent,
                keepAlphabetKeys = false,
            )
        }
        return HardwareToolbarToggle(
            showPanel = false,
            leaveSymbols = symbolsCurrent,
            keepAlphabetKeys = systemSoftKeyboard && !symbolsCurrent,
        )
    }

    @JvmStatic
    fun normalize(value: String?): String = when (value) {
        EMOJI, TOOLBAR -> value
        else -> NONE
    }
}
