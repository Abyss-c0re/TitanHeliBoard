// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.content.Context
import android.util.Xml
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class HwMappingParserTest {
    @Test fun `compiled titan maps match pocketboard positions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = load(context, R.xml.keyboard_mapping_en_us_titan)
        val q = english.get(KeyEvent.KEYCODE_Q)!!
        assertEquals('q'.code, q.get(shift = false, alt = false, index = 0))
        assertEquals('Q'.code, q.get(shift = true, alt = false, index = 0))
        assertEquals(':'.code, q.get(shift = false, alt = true, index = 0))
        assertEquals(';'.code, q.get(shift = false, alt = true, index = 1))
        assertEquals('\\'.code, english.get(KeyEvent.KEYCODE_W)!!.get(shift = false, alt = true, index = 1))

        val russian = load(context, R.xml.keyboard_mapping_ru_bbp_titan).get(KeyEvent.KEYCODE_Q)!!
        assertEquals('й'.code, russian.get(shift = false, alt = false, index = 0))
        assertEquals('Й'.code, russian.get(shift = true, alt = false, index = 0))
        assertEquals('ц'.code, russian.get(shift = false, alt = false, index = 1))
        assertEquals('Ц'.code, russian.get(shift = true, alt = false, index = 1))

        val numeric = load(context, R.xml.keyboard_mapping_numeric_titan)
        assertEquals('1'.code, numeric.get(KeyEvent.KEYCODE_U)!!.get(shift = false, alt = false, index = 0))
        assertEquals('0'.code, numeric.get(KeyEvent.KEYCODE_P)!!.get(shift = false, alt = false, index = 0))
    }

    @Test fun `raw escapes keep at-sign and a real backslash`() {
        val parsed = HwMappingParser.parse(pullParser("""
            <KeyboardMapping>
              <Key code="33" value="e" shiftValue="E">
                <Alt value="\@" />
                <Alt value="\\" />
              </Key>
            </KeyboardMapping>
        """.trimIndent()))
        val key = parsed.get(KeyEvent.KEYCODE_E)!!
        assertEquals('@'.code, key.get(shift = false, alt = true, index = 0))
        assertEquals('\\'.code, key.get(shift = false, alt = true, index = 1))
    }

    @Test fun `missing shift falls back to the unshifted character`() {
        val key = HwKeyMapping(listOf(HwKeyValue('q'.code, 0)), emptyList())
        assertEquals('q'.code, key.get(shift = true, alt = false, index = 0))
    }

    private fun load(context: Context, resId: Int): HwKeyboardMapping {
        val xml = context.resources.getXml(resId)
        return try {
            HwMappingParser.parse(xml)
        } finally {
            xml.close()
        }
    }

    private fun pullParser(xml: String): XmlPullParser =
        Xml.newPullParser().apply { setInput(StringReader(xml)) }
}
