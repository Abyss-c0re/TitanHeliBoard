// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.hw.HwLayoutStore
import helium314.keyboard.latin.hw.HwLayouts
import helium314.keyboard.latin.hw.hwLayoutLabel
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.MultiListPickerDialog
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun HardwareLayoutScreen(onClickBack: () -> Unit) {
    val tick = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((tick?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.hw_layout_screen_title),
        settings = listOf(
            Settings.PREF_HW_LAYOUT_ENABLED,
            Settings.PREF_HW_LAYOUT_ROTATION,
            Settings.PREF_HW_LAYOUT_CURRENT,
            Settings.PREF_HW_LAYOUT_ALT_SHIFT,
            Settings.PREF_HW_LAYOUT_SPACE_HOLD,
            Settings.PREF_HW_LAYOUT_HOLD,
            Settings.PREF_HW_LAYOUT_TOAST,
        ),
    )
}

fun createHardwareLayoutSettings(context: Context) = listOf(
    Setting(
        context,
        Settings.PREF_HW_LAYOUT_ENABLED,
        R.string.hw_layout_enabled,
        R.string.hw_layout_enabled_summary,
    ) {
        SwitchPreference(it, HwLayouts.defaultEnabled(Build.MODEL))
    },
    Setting(
        context,
        Settings.PREF_HW_LAYOUT_ROTATION,
        R.string.hw_layout_rotation,
        R.string.hw_layout_rotation_summary,
    ) {
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val selected = HwLayouts.sanitizeRotation(
            prefs.getString(Settings.PREF_HW_LAYOUT_ROTATION, Defaults.PREF_HW_LAYOUT_ROTATION)
        )
        Preference(
            name = it.title,
            description = selected.joinToString { id -> hwLayoutLabel(ctx, id) },
            onClick = { showDialog = true },
        )
        if (showDialog) {
            MultiListPickerDialog(
                onDismissRequest = { showDialog = false },
                onConfirmed = { chosen ->
                    val saved = HwLayouts.CATALOG_IDS.filter { id -> id in chosen }.ifEmpty { listOf(HwLayouts.ID_EN) }
                    val current = prefs.getString(Settings.PREF_HW_LAYOUT_CURRENT, Defaults.PREF_HW_LAYOUT_CURRENT)
                    prefs.edit {
                        putString(Settings.PREF_HW_LAYOUT_ROTATION, saved.joinToString(","))
                        if (current !in saved) putString(Settings.PREF_HW_LAYOUT_CURRENT, saved.first())
                    }
                    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                },
                title = { Text(it.title) },
                items = HwLayouts.CATALOG_IDS,
                initialSelection = selected,
                getItemName = { id -> hwLayoutLabel(ctx, id) },
            )
        }
    },
    Setting(context, Settings.PREF_HW_LAYOUT_CURRENT, R.string.hw_layout_current) { setting ->
        val ctx = LocalContext.current
        val rotation = HwLayoutStore(ctx).rotation
        val items = rotation.map { id -> hwLayoutLabel(ctx, id) to id }
        ListPreference(setting, items, Defaults.PREF_HW_LAYOUT_CURRENT) {
            (ctx.getActivity() as? SettingsActivity)?.prefChanged()
        }
    },
    Setting(context, Settings.PREF_HW_LAYOUT_ALT_SHIFT, R.string.hw_layout_alt_shift) {
        SwitchPreference(it, Defaults.PREF_HW_LAYOUT_ALT_SHIFT)
    },
    Setting(
        context,
        Settings.PREF_HW_LAYOUT_SPACE_HOLD,
        R.string.hw_layout_space_hold,
        R.string.hw_layout_space_hold_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_HW_LAYOUT_SPACE_HOLD)
    },
    Setting(
        context,
        Settings.PREF_HW_LAYOUT_HOLD,
        R.string.hw_layout_hold,
        R.string.hw_layout_hold_summary,
    ) { setting ->
        val items = listOf(
            stringResource(R.string.hw_layout_hold_repeat) to HwLayouts.HOLD_REPEAT,
            stringResource(R.string.hw_layout_hold_symbol) to HwLayouts.HOLD_SYMBOL,
        )
        ListPreference(
            setting,
            items,
            Defaults.PREF_HW_LAYOUT_HOLD,
            description = stringResource(R.string.hw_layout_hold_summary),
        )
    },
    Setting(context, Settings.PREF_HW_LAYOUT_TOAST, R.string.hw_layout_toast) {
        SwitchPreference(it, Defaults.PREF_HW_LAYOUT_TOAST)
    },
)
