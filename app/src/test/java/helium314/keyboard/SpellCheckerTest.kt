package helium314.keyboard

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerService
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerSession
import helium314.keyboard.latin.spellcheck.SentenceLevelAdapter
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class,
    ShadowDictionaryFacilitatorImpl::class,
])
class SpellCheckerTest {
    private val checkService = Robolectric.setupService(AndroidSpellCheckerService::class.java)
    private val checker = AndroidSpellCheckerSession(checkService)

    @Test fun `number is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("123"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }

    @Test fun `$ is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("$"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }

    @Test fun `unchecked spell result does not cover the word`() {
        val info = reconstruct("helo", AndroidSpellCheckerService.getUncheckedSuggestions().apply { setCookieAndSequence(7, 9) })
        assertEquals(0, info.getLengthAt(0))
    }

    @Test fun `typo spell result keeps the word length`() {
        val typo = SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, arrayOf("hello"))
        typo.setCookieAndSequence(7, 9)
        val info = reconstruct("helo", typo)
        assertEquals(4, info.getLengthAt(0))
    }

    @Test fun `unchecked result with a different sequence keeps the word length`() {
        val unchecked = AndroidSpellCheckerService.getUncheckedSuggestions().apply { setCookieAndSequence(7, 1) }
        val info = reconstruct("helo", unchecked)
        assertEquals(4, info.getLengthAt(0))
    }

    private fun reconstruct(word: String, result: SuggestionsInfo): SentenceSuggestionsInfo {
        val textInfo = TextInfo(word, 0, word.length, 7, 9)
        val params = SentenceLevelAdapter.SentenceTextInfoParams(
            textInfo,
            arrayListOf(SentenceLevelAdapter.SentenceWordItem(textInfo, 0, word.length)),
        )
        return SentenceLevelAdapter.reconstructSuggestions(params, arrayOf(result))
    }
}
