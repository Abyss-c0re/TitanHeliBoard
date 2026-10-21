// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.common.StringUtils
import java.util.Locale

/**
 * A finished hardware-keyboard word can be spell-checked. Fragments, numbers, and
 * mostly-symbol tokens are left to the system spell checker.
 */
fun hardwareWordLooksCheckable(word: String): Boolean {
    if (word.length < 2) return false
    var letters = 0
    var i = 0
    while (i < word.length) {
        val codePoint = word.codePointAt(i)
        if (Character.isLetter(codePoint)) letters++
        i += Character.charCount(codePoint)
    }
    return letters * 4 >= word.length * 3
}

fun interface SpellingValidity {
    fun isValid(word: String): Boolean
}

/**
 * True when [word] and its capitalization variants are absent from the dictionary.
 * Sentence-initial capitals stay valid when the lowercase word is known.
 */
fun isMisspelling(word: String, valid: SpellingValidity, locale: Locale): Boolean {
    if (!hardwareWordLooksCheckable(word)) return false
    if (valid.isValid(word)) return false
    val capitalization = StringUtils.getCapitalizationType(word)
    if (capitalization == StringUtils.CAPITALIZE_NONE) return true
    val lower = word.lowercase(locale)
    if (valid.isValid(lower)) return false
    if (capitalization == StringUtils.CAPITALIZE_FIRST) return true
    return !valid.isValid(StringUtils.capitalizeFirstAndDowncaseRest(lower, locale))
}
