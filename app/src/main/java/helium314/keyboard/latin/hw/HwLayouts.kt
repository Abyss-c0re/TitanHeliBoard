// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.text.InputType

internal data class HwLayoutSpec(val id: String, val language: String)

internal object HwLayouts {
    const val ID_EN = "en_us"
    const val ID_RU_BBP = "ru_bbp"
    const val ID_RU_T = "ru_t"
    const val ID_RU_ALT = "ru_alt"
    const val ID_ES = "es_es"
    const val ID_NUMERIC = "numeric"

    /** How many Space repeats PocketBoard waits before a hold switches layout. The first press still inserts a space. */
    const val SPACE_SWITCH_REPEAT = 3

    val CATALOG = listOf(
        HwLayoutSpec(ID_EN, "en"),
        HwLayoutSpec(ID_RU_BBP, "ru"),
        HwLayoutSpec(ID_RU_T, "ru"),
        HwLayoutSpec(ID_RU_ALT, "ru"),
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

    /**
     * Apps that must receive the original hardware keys. A trailing '*' or '.' in [extra]
     * matches a package prefix. The built-in list is not replaced by [extra].
     */
    fun isDirectInput(packageName: String, extra: String): Boolean {
        if (packageName.isEmpty()) return false
        if (packageName in DIRECT_EXACT) return true
        if (DIRECT_PREFIXES.any { packageName.startsWith(it) }) return true
        return extra.split(',', ' ', '\n', ';', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry -> matchesExtra(packageName, entry) }
    }

    private fun matchesExtra(packageName: String, entry: String): Boolean {
        val stem = when {
            entry.endsWith("*") -> entry.dropLast(1)
            entry.endsWith(".") -> entry
            else -> return packageName == entry
        }
        return stem.isNotEmpty() && packageName.startsWith(stem)
    }
}

private val DIRECT_EXACT = setOf(
    "com.android.calculator2",
    "com.google.android.calculator",
    "org.yuzu.yuzu_emu",
    "org.citra.citra_emu",
    "org.duckstation.duckstation",
    "com.github.stenzek.duckstation",
    "org.mupen64plusae.v3.fzurita",
    "org.scummvm.scummvm",
    "skyline.emu",
    "org.strato.stratoemu",
    "io.github.lime3ds.android",
    "org.azahar_emu.azahar",
    "com.limelight",
    "com.carriez.flutter_hbb",
)

private val DIRECT_PREFIXES = listOf(
    "com.retroarch",
    "org.ppsspp.",
    "org.dolphinemu.",
    "com.explusalpha.",
)
