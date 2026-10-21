// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import android.text.Spanned
import android.text.style.SuggestionSpan
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.getTextWithMisspelledSpan
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HardwareSpellTest {
    @Test fun `misspelled span is not composing and can show suggestions`() {
        val text = getTextWithMisspelledSpan(
            ApplicationProvider.getApplicationContext(),
            "helo",
            arrayOf("helo", "hello", "help"),
            Locale.US,
            true,
        ) as Spanned
        val spans = text.getSpans(0, text.length, SuggestionSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(SuggestionSpan.FLAG_MISSPELLED or SuggestionSpan.FLAG_EASY_CORRECT, spans[0].flags)
        assertEquals(listOf("hello", "help"), spans[0].suggestions.toList())
        assertEquals(0, text.getSpanFlags(spans[0]) and Spanned.SPAN_COMPOSING)
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(spans[0]) and Spanned.SPAN_POINT_MARK)
    }

    @Test fun `misspelled span without the suggestion popup omits easy correct`() {
        val text = getTextWithMisspelledSpan(
            ApplicationProvider.getApplicationContext(),
            "helo",
            arrayOf("hello"),
            Locale.US,
            false,
        ) as Spanned
        val span = text.getSpans(0, text.length, SuggestionSpan::class.java).single()
        assertEquals(SuggestionSpan.FLAG_MISSPELLED, span.flags)
        assertEquals(0, text.getSpanFlags(span) and Spanned.SPAN_COMPOSING)
    }

    @Test fun `hardware words use capitalization variants before they are underlined`() {
        val hello = SpellingValidity { it == "hello" }
        assertTrue(isMisspelling("helo", hello, Locale.US))
        assertFalse(isMisspelling("Hello", hello, Locale.US))
        assertFalse(isMisspelling("HELLO", hello, Locale.US))

        val germans = SpellingValidity { it == "Germans" }
        assertFalse(isMisspelling("Germans", germans, Locale.US))
        assertFalse(isMisspelling("GERMANS", germans, Locale.US))
        assertTrue(isMisspelling("Helo", germans, Locale.US))

        assertFalse(hardwareWordLooksCheckable("a"))
        assertFalse(hardwareWordLooksCheckable("123"))
        assertFalse(hardwareWordLooksCheckable("a@b"))
        assertFalse(isMisspelling("123", hello, Locale.US))
        assertFalse(isMisspelling("a@b", hello, Locale.US))
    }
}
