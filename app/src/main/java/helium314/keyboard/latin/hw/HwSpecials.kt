// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.view.KeyEvent

/**
 * Printed Sym glyphs from the AtlasOS Titan key character map (`ralt` column of
 * `Vendor_2533_Product_2533.kcm`). Sym is ALT_RIGHT. Shift+Sym matches `shift+ralt: none`.
 */
internal object HwSpecials {
    private val glyphs = mapOf(
        KeyEvent.KEYCODE_A to '@'.code,
        KeyEvent.KEYCODE_B to '.'.code,
        KeyEvent.KEYCODE_C to '8'.code,
        KeyEvent.KEYCODE_D to '5'.code,
        KeyEvent.KEYCODE_E to '2'.code,
        KeyEvent.KEYCODE_F to '6'.code,
        KeyEvent.KEYCODE_G to '*'.code,
        KeyEvent.KEYCODE_H to '#'.code,
        KeyEvent.KEYCODE_I to '_'.code,
        KeyEvent.KEYCODE_J to '+'.code,
        KeyEvent.KEYCODE_K to '"'.code,
        KeyEvent.KEYCODE_L to '\''.code,
        KeyEvent.KEYCODE_M to '?'.code,
        KeyEvent.KEYCODE_N to ','.code,
        KeyEvent.KEYCODE_O to '/'.code,
        KeyEvent.KEYCODE_P to ':'.code,
        KeyEvent.KEYCODE_Q to '0'.code,
        KeyEvent.KEYCODE_R to '3'.code,
        KeyEvent.KEYCODE_S to '4'.code,
        KeyEvent.KEYCODE_T to '('.code,
        KeyEvent.KEYCODE_U to '-'.code,
        KeyEvent.KEYCODE_V to '9'.code,
        KeyEvent.KEYCODE_W to '1'.code,
        KeyEvent.KEYCODE_X to '7'.code,
        KeyEvent.KEYCODE_Y to ')'.code,
        KeyEvent.KEYCODE_Z to '!'.code,
    )

    fun glyph(keyCode: Int): Int? = glyphs[keyCode]
}
