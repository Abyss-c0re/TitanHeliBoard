// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences

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
     * Alt shows the toolbar when it is hidden, and hides it when it is showing.
     * Emoji, a hidden toolbar mode, or a missing strip count as hidden.
     */
    @JvmStatic
    fun showToolbarOnAlt(
        toolbarModeHidden: Boolean,
        inputShown: Boolean,
        hasStrip: Boolean,
        toolbarRowShown: Boolean,
        emojiOrClipboard: Boolean,
    ): Boolean {
        if (toolbarModeHidden || emojiOrClipboard || !inputShown || !hasStrip) return true
        return !toolbarRowShown
    }

    @JvmStatic
    fun normalize(value: String?): String = when (value) {
        EMOJI, TOOLBAR -> value
        else -> NONE
    }
}
