// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.text.InputType

internal data class HwLayoutSpec(val id: String, val language: String)

internal object HwLayouts {
    const val ID_EN = "en_us"
    const val ID_RU_BBP = "ru_bbp"
    const val ID_ES = "es_es"
    const val ID_NUMERIC = "numeric"

    /** How many Space repeats PocketBoard waits before a hold switches layout. The first press still inserts a space. */
    const val SPACE_SWITCH_REPEAT = 3

    /** Held keys repeat. */
    const val HOLD_REPEAT = "repeat"

    /** Sym, or holding a key, types the symbol declared by the current layout. */
    const val HOLD_SYMBOL = "symbol"

    val CATALOG = listOf(
        HwLayoutSpec(ID_EN, "en"),
        HwLayoutSpec(ID_RU_BBP, "ru"),
        HwLayoutSpec(ID_ES, "es"),
    )

    val CATALOG_IDS = CATALOG.map { it.id }

    fun defaultEnabled(model: String): Boolean = model.contains("titan", ignoreCase = true)

    fun languageOf(id: String): String? = CATALOG.firstOrNull { it.id == id }?.language

    /** Keep catalog order. Drop unknown ids. An empty selection still leaves English. */
    fun sanitizeRotation(raw: String?): List<String> {
        val wanted = raw.orEmpty()
            .split(',', ' ', '\n', ';', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return CATALOG_IDS.filter { it in wanted }.ifEmpty { listOf(ID_EN) }
    }

    fun isNumericInputType(inputType: Int): Boolean =
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
}
