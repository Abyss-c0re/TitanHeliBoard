// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.text.InputType
import android.view.KeyEvent
import helium314.keyboard.ShadowInputMethodManager2
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class HwLayoutEngineTest {
    private val qwerty = HwKeyMapping(
        values = listOf(HwKeyValue('q'.code, 'Q'.code), HwKeyValue('1'.code, '!'.code)),
        altValues = listOf(HwKeyValue(':'.code, ';'.code), HwKeyValue('/'.code, 0)),
    )
    private val plain = HwKeyMapping(listOf(HwKeyValue('a'.code, 'A'.code)), emptyList())
    private val letterU = HwKeyMapping(listOf(HwKeyValue('u'.code, 'U'.code)), emptyList())
    private val letters = HwKeyboardMapping(mapOf(
        KeyEvent.KEYCODE_Q to qwerty,
        KeyEvent.KEYCODE_A to plain,
        KeyEvent.KEYCODE_U to letterU,
    ))
    private val numeric = HwKeyboardMapping(mapOf(
        KeyEvent.KEYCODE_U to HwKeyMapping(listOf(HwKeyValue('1'.code, 0)), emptyList()),
    ))

    @Test fun `titan models enable the feature by default`() {
        assertTrue(HwLayouts.defaultEnabled("Titan_2"))
        assertTrue(HwLayouts.defaultEnabled("TITAN"))
        assertFalse(HwLayouts.defaultEnabled("Pixel 8"))
    }

    @Test fun `rotation keeps catalog order and drops unknown ids`() {
        assertEquals(listOf(HwLayouts.ID_EN, HwLayouts.ID_RU_ALT), HwLayouts.sanitizeRotation("ru_alt, nope, en_us"))
        assertEquals(listOf(HwLayouts.ID_EN), HwLayouts.sanitizeRotation(""))
    }

    @Test fun `disabled feature leaves the legacy path alone`() {
        val (prefs, engine) = rig()
        prefs.enabled = false
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Ignore, engine.onKeyDown(down(KeyEvent.KEYCODE_Q), autoCap = false))
    }

    @Test fun `null field type null and emulators pass keys through`() {
        val (_, engine) = rig()
        engine.noteField(null)
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_Q), false))

        engine.noteField(HwField("com.example.game", InputType.TYPE_NULL))
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_Q), false))

        engine.noteField(text("org.yuzu.yuzu_emu"))
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT), false))
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 20), false))
        assertEquals(HwLayouts.ID_EN, enginePrefs.currentId)

        engine.noteField(text("com.retroarch.aarch64"))
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 40), false))
        engine.noteField(text("com.custom.emu"))
        enginePrefs.passthroughExtra = "com.custom.*"
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 60), false))
    }

    @Test fun `letters use the map and ctrl does not`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q), false))
        assertEquals(
            HwKeyDecision.Pass,
            engine.onKeyDown(down(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_CTRL_ON, time = 50), false),
        )
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_DPAD_LEFT, time = 70), false))
    }

    @Test fun `alt with no alt characters types the base character`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT), false)
        assertEquals(HwKeyDecision.Type('a'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_A, time = 30), false))
    }

    @Test fun `quick taps cycle add characters and then wrap`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1000), false))
        assertEquals(HwKeyDecision.Type('1'.code, true), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1100), false))
        assertEquals(HwKeyDecision.Type('q'.code, true), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1200), false))
    }

    @Test fun `a key without add types twice`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type('a'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_A, time = 1000), false))
        assertEquals(HwKeyDecision.Type('a'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_A, time = 1100), false))
    }

    @Test fun `long press replaces with the first alt character once`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1000), false)
        assertEquals(
            HwKeyDecision.Type(':'.code, true),
            engine.onKeyDown(down(KeyEvent.KEYCODE_Q, repeat = 1, time = 1400), false),
        )
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_Q, repeat = 2, time = 1800), false))
    }

    @Test fun `one shot shift cancels auto cap and a plain tap capitalizes`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        tapShift(engine, 1000)
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1200), autoCap = true))

        tapShift(engine, 2000)
        assertEquals(HwKeyDecision.Type('Q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 2200), autoCap = false))
    }

    @Test fun `held shift that typed a letter does not stick`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1000), false)
        assertEquals(HwKeyDecision.Type('Q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1100), false))
        engine.onKeyUp(up(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1200))
        // Past the multi-tap window, so this is a new letter and not the shifted "1".
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1600), false))
    }

    @Test fun `double tap shift locks`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        tapShift(engine, 1000)
        tapShift(engine, 1100)
        assertEquals(HwKeyDecision.Type('Q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1400), false))
    }

    @Test fun `alt then shift switches once and does not stick`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1000), false))
        val switched = engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1100), false)
        assertEquals(HwKeyDecision.Switched(HwLayouts.ID_RU_BBP, false), switched)
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, repeat = 1, time = 1500), false))
        engine.onKeyUp(up(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1600))
        engine.onKeyUp(up(KeyEvent.KEYCODE_ALT_LEFT, time = 1700))
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 2000), false))
    }

    @Test fun `shift then alt switches and the rotation wraps`() {
        val (prefs, engine) = rig(rotation = listOf(HwLayouts.ID_EN, HwLayouts.ID_ES))
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1000), false)
        assertEquals(
            HwKeyDecision.Switched(HwLayouts.ID_ES, false),
            engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1100, meta = KeyEvent.META_SHIFT_ON), false),
        )
        engine.onKeyUp(up(KeyEvent.KEYCODE_ALT_LEFT, time = 1200))
        engine.onKeyUp(up(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1300))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 2000), false)
        assertEquals(
            HwKeyDecision.Switched(HwLayouts.ID_EN, false),
            engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 2100), false),
        )
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `a single layout does not consume alt shift`() {
        val (prefs, engine) = rig(rotation = listOf(HwLayouts.ID_EN))
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1000), false)
        assertEquals(HwKeyDecision.Pass, engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1100), false))
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `ctrl alt shift does not switch`() {
        val (prefs, engine) = rig()
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1000, meta = KeyEvent.META_CTRL_ON), false)
        assertEquals(
            HwKeyDecision.Pass,
            engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1100, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON), false),
        )
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `holding space switches on the third repeat and removes the space`() {
        val (prefs, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type(' '.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, time = 1000), false))
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 1, time = 1100), false))
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 2, time = 1200), false))
        assertEquals(
            HwKeyDecision.Switched(HwLayouts.ID_RU_BBP, true),
            engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 3, time = 1300), false),
        )
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 4, time = 1500), false))
        assertEquals(HwLayouts.ID_RU_BBP, prefs.currentId)
    }

    @Test fun `space hold off inserts a space on every repeat`() {
        val (prefs, engine) = rig()
        prefs.spaceHold = false
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type(' '.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, time = 1000), false))
        assertEquals(HwKeyDecision.Type(' '.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 3, time = 1400), false))
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `one layout consumes the space hold without switching`() {
        val (prefs, engine) = rig(rotation = listOf(HwLayouts.ID_EN))
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, time = 1000), false)
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_SPACE, repeat = 3, time = 1400), false))
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `a missing current layout jumps to the first entry`() {
        val (prefs, engine) = rig()
        prefs.storedId = "missing"
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_Q), false)
        assertEquals(HwLayouts.ID_EN, prefs.currentId)
    }

    @Test fun `alt shift from a missing layout lands on the first layout`() {
        val (prefs, engine) = rig()
        prefs.storedId = "missing"
        engine.noteField(text("com.example.notes"))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1000), false)
        assertEquals(
            HwKeyDecision.Switched(HwLayouts.ID_EN, false),
            engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1100), false),
        )
    }

    @Test fun `number fields use the numeric map and still cycle the remembered layout`() {
        val (prefs, engine) = rig()
        engine.noteField(HwField("com.example.notes", InputType.TYPE_CLASS_NUMBER))
        assertEquals(HwKeyDecision.Type('1'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_U), false))
        engine.onKeyDown(down(KeyEvent.KEYCODE_ALT_LEFT, time = 1000), false)
        assertEquals(
            HwKeyDecision.Switched(HwLayouts.ID_RU_BBP, false),
            engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = 1100), false),
        )
        assertEquals(HwKeyDecision.Type('1'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_U, time = 1200), false))
        engine.noteField(text("com.example.notes"))
        assertEquals(HwKeyDecision.Type('u'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_U, time = 1500), false))
        assertEquals(HwLayouts.ID_RU_BBP, prefs.currentId)
    }

    @Test fun `shift delete is a word delete and plain enter is consumed once`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        assertEquals(
            HwKeyDecision.Delete(word = true, repeat = false),
            engine.onKeyDown(down(KeyEvent.KEYCODE_DEL, meta = KeyEvent.META_SHIFT_ON), false),
        )
        assertEquals(HwKeyDecision.Enter, engine.onKeyDown(down(KeyEvent.KEYCODE_ENTER, time = 50), false))
        assertEquals(HwKeyDecision.Consume, engine.onKeyDown(down(KeyEvent.KEYCODE_ENTER, repeat = 1, time = 200), false))
        assertEquals(
            HwKeyDecision.Pass,
            engine.onKeyDown(down(KeyEvent.KEYCODE_ENTER, time = 300, meta = KeyEvent.META_SHIFT_ON), false),
        )
    }

    @Test fun `changing fields clears a one shot shift`() {
        val (_, engine) = rig()
        engine.noteField(text("com.example.notes"))
        tapShift(engine, 1000)
        engine.noteField(text("com.example.other"))
        assertEquals(HwKeyDecision.Type('q'.code, false), engine.onKeyDown(down(KeyEvent.KEYCODE_Q, time = 1300), false))
    }

    private lateinit var enginePrefs: MemPrefs

    private fun rig(
        rotation: List<String> = HwLayouts.CATALOG_IDS,
        current: String = HwLayouts.ID_EN,
    ): Pair<MemPrefs, HwLayoutEngine> {
        val prefs = MemPrefs(rotation, current)
        enginePrefs = prefs
        val engine = HwLayoutEngine(prefs) { id -> if (id == HwLayouts.ID_NUMERIC) numeric else letters }
        return prefs to engine
    }

    private fun text(packageName: String) = HwField(packageName, InputType.TYPE_CLASS_TEXT)

    private fun tapShift(engine: HwLayoutEngine, time: Long) {
        engine.onKeyDown(down(KeyEvent.KEYCODE_SHIFT_LEFT, time = time), false)
        engine.onKeyUp(up(KeyEvent.KEYCODE_SHIFT_LEFT, time = time + 40))
    }

    private fun down(code: Int, repeat: Int = 0, meta: Int = 0, time: Long = 0L) =
        KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, repeat, meta)

    private fun up(code: Int, time: Long) =
        KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, 0)

    private class MemPrefs(
        override var rotation: List<String>,
        var storedId: String,
    ) : HwLayoutPreferences {
        override var enabled: Boolean = true
        override val currentId: String get() = storedId
        override var altShift: Boolean = true
        override var spaceHold: Boolean = true
        override var toast: Boolean = true
        override var passthroughExtra: String = ""
        override var longPressMs: Int = 300
        override fun setCurrentId(id: String) { storedId = id }
    }
}
