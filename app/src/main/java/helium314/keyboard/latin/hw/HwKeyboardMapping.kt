// SPDX-License-Identifier: GPL-3.0-only
// Hardware maps adapted from PocketBoard (https://github.com/SinuXVR/pocket-board).
package helium314.keyboard.latin.hw

import org.xmlpull.v1.XmlPullParser

/** One base or alt character, with an optional shifted form. A shift value of 0 means "use the unshifted character". */
internal data class HwKeyValue(val value: Int, val shiftValue: Int)

/**
 * PocketBoard key: a list of base/Add characters and a list of Alt characters.
 * [index] cycles with modulo, matching multi-tap on keys that declare extra values.
 */
internal class HwKeyMapping(
    val values: List<HwKeyValue>,
    val altValues: List<HwKeyValue>,
) {
    init {
        require(values.isNotEmpty()) { "key mapping needs a base value" }
    }

    fun hasAdditional(alt: Boolean): Boolean =
        if (alt) altValues.size > 1 else values.size > 1

    fun get(shift: Boolean, alt: Boolean, index: Int): Int {
        val list = if (alt && altValues.isNotEmpty()) altValues else values
        val item = list[index.mod(list.size)]
        if (!shift || item.shiftValue == 0) return item.value
        return item.shiftValue
    }
}

internal class HwKeyboardMapping(val keys: Map<Int, HwKeyMapping>) {
    operator fun get(keyCode: Int): HwKeyMapping? = keys[keyCode]
}

internal object HwMappingParser {
    fun parse(xpp: XmlPullParser): HwKeyboardMapping {
        val keys = HashMap<Int, HwKeyMapping>()
        var code = 0
        val values = ArrayList<HwKeyValue>()
        val alts = ArrayList<HwKeyValue>()
        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (xpp.name) {
                    "Key" -> {
                        code = attr(xpp, "code")?.toIntOrNull() ?: 0
                        values.clear()
                        alts.clear()
                        readValue(xpp)?.let { values.add(it) }
                    }
                    "Add" -> readValue(xpp)?.let { values.add(it) }
                    "Alt" -> readValue(xpp)?.let { alts.add(it) }
                }
                XmlPullParser.END_TAG -> when (xpp.name) {
                    "Key" -> if (values.isNotEmpty()) {
                        keys[code] = HwKeyMapping(values.toList(), alts.toList())
                    }
                    "KeyboardMapping" -> return HwKeyboardMapping(keys)
                }
            }
            event = xpp.next()
        }
        if (keys.isEmpty()) throw IllegalStateException("KeyboardMapping has no keys")
        return HwKeyboardMapping(keys)
    }

    private fun readValue(xpp: XmlPullParser): HwKeyValue? {
        val raw = attr(xpp, "value") ?: return null
        if (raw.isEmpty()) return null
        val shiftRaw = attr(xpp, "shiftValue")
        val shift = if (shiftRaw.isNullOrEmpty()) 0 else mappingCodePoint(shiftRaw)
        return HwKeyValue(mappingCodePoint(raw), shift)
    }

    private fun attr(xpp: XmlPullParser, name: String): String? {
        xpp.getAttributeValue(null, name)?.let { return it }
        for (i in 0 until xpp.attributeCount) {
            if (xpp.getAttributeName(i) == name) return xpp.getAttributeValue(i)
        }
        return null
    }
}

/**
 * AAPT turns a leading `\@`, `\#`, or `\?` into the literal character so the attribute is not
 * read as a resource reference. A raw pull parser still sees the backslash. A real backslash
 * (XML `\\`) stays a backslash.
 */
internal fun mappingCodePoint(raw: String): Int {
    if (raw.length >= 2 && raw[0] == '\\' && raw[1] in "@#?") return raw.codePointAt(1)
    return raw.codePointAt(0)
}
