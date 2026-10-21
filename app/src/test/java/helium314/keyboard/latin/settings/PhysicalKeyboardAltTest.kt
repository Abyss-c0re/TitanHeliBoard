// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class PhysicalKeyboardAltTest {
    private fun prefs(): SharedPreferences {
        return ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("physical-alt-" + System.nanoTime(), Context.MODE_PRIVATE)
    }

    @Test fun missingChoiceIsNothing() {
        val prefs = prefs()
        assertEquals(PhysicalKeyboardAlt.NONE, PhysicalKeyboardAlt.read(prefs))
        assertFalse(prefs.contains(Settings.PREF_PHYSICAL_KEYBOARD_ALT))
    }

    @Test fun oldEmojiSwitchBecomesEmoji() {
        val prefs = prefs()
        prefs.edit().putBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, true).commit()
        assertEquals(PhysicalKeyboardAlt.EMOJI, PhysicalKeyboardAlt.read(prefs))
        assertEquals(PhysicalKeyboardAlt.EMOJI, prefs.getString(Settings.PREF_PHYSICAL_KEYBOARD_ALT, null))
        assertFalse(prefs.contains(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY))
    }

    @Test fun oldOffSwitchBecomesNothing() {
        val prefs = prefs()
        prefs.edit().putBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, false).commit()
        assertEquals(PhysicalKeyboardAlt.NONE, PhysicalKeyboardAlt.read(prefs))
        assertFalse(prefs.contains(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY))
    }

    @Test fun storedToolbarIsNotReplacedByTheOldSwitch() {
        val prefs = prefs()
        prefs.edit()
            .putString(Settings.PREF_PHYSICAL_KEYBOARD_ALT, PhysicalKeyboardAlt.TOOLBAR)
            .putBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, true)
            .commit()
        assertEquals(PhysicalKeyboardAlt.TOOLBAR, PhysicalKeyboardAlt.read(prefs))
        assertEquals(true, prefs.getBoolean(Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, false))
    }

    @Test fun unknownValueIsNothing() {
        assertEquals(PhysicalKeyboardAlt.NONE, PhysicalKeyboardAlt.normalize("symbols"))
        assertEquals(PhysicalKeyboardAlt.EMOJI, PhysicalKeyboardAlt.normalize(PhysicalKeyboardAlt.EMOJI))
    }

    @Test fun altShowsAHiddenToolbarAndHidesAShownOne() {
        assertEquals(true, PhysicalKeyboardAlt.showToolbarOnAlt(
            toolbarModeHidden = true,
            inputShown = true,
            hasStrip = true,
            toolbarRowShown = false,
            emojiOrClipboard = false,
        ))
        assertEquals(true, PhysicalKeyboardAlt.showToolbarOnAlt(
            toolbarModeHidden = false,
            inputShown = true,
            hasStrip = true,
            toolbarRowShown = false,
            emojiOrClipboard = false,
        ))
        assertEquals(false, PhysicalKeyboardAlt.showToolbarOnAlt(
            toolbarModeHidden = false,
            inputShown = true,
            hasStrip = true,
            toolbarRowShown = true,
            emojiOrClipboard = false,
        ))
        assertEquals(true, PhysicalKeyboardAlt.showToolbarOnAlt(
            toolbarModeHidden = false,
            inputShown = false,
            hasStrip = true,
            toolbarRowShown = true,
            emojiOrClipboard = false,
        ))
        assertEquals(true, PhysicalKeyboardAlt.showToolbarOnAlt(
            toolbarModeHidden = false,
            inputShown = true,
            hasStrip = true,
            toolbarRowShown = true,
            emojiOrClipboard = true,
        ))
    }
}
