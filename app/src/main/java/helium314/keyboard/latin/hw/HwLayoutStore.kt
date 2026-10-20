// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

internal class HwLayoutStore(context: Context) : HwLayoutPreferences {
    private val prefs = context.prefs()

    override val enabled: Boolean
        get() = if (prefs.contains(Settings.PREF_HW_LAYOUT_ENABLED)) {
            prefs.getBoolean(Settings.PREF_HW_LAYOUT_ENABLED, false)
        } else {
            HwLayouts.defaultEnabled(Build.MODEL)
        }

    override val rotation: List<String>
        get() = HwLayouts.sanitizeRotation(
            prefs.getString(Settings.PREF_HW_LAYOUT_ROTATION, Defaults.PREF_HW_LAYOUT_ROTATION)
        )

    override val currentId: String
        get() {
            val rot = rotation
            val raw = prefs.getString(Settings.PREF_HW_LAYOUT_CURRENT, Defaults.PREF_HW_LAYOUT_CURRENT)
            if (raw != null && raw in rot) return raw
            val first = rot.first()
            prefs.edit { putString(Settings.PREF_HW_LAYOUT_CURRENT, first) }
            return first
        }

    override val altShift: Boolean
        get() = prefs.getBoolean(Settings.PREF_HW_LAYOUT_ALT_SHIFT, Defaults.PREF_HW_LAYOUT_ALT_SHIFT)

    override val spaceHold: Boolean
        get() = prefs.getBoolean(Settings.PREF_HW_LAYOUT_SPACE_HOLD, Defaults.PREF_HW_LAYOUT_SPACE_HOLD)

    override val toast: Boolean
        get() = prefs.getBoolean(Settings.PREF_HW_LAYOUT_TOAST, Defaults.PREF_HW_LAYOUT_TOAST)

    override val passthroughExtra: String
        get() = prefs.getString(Settings.PREF_HW_LAYOUT_PASSTHROUGH, Defaults.PREF_HW_LAYOUT_PASSTHROUGH).orEmpty()

    override val longPressMs: Int
        get() = prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
            .coerceIn(50, 5000)

    override fun setCurrentId(id: String) {
        prefs.edit { putString(Settings.PREF_HW_LAYOUT_CURRENT, id) }
    }
}
