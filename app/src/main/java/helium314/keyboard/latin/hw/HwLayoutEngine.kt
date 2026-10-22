// SPDX-License-Identifier: GPL-3.0-only
// Layout switching follows PocketBoard. Free Alt is ALT_LEFT. Sym is ALT_RIGHT.
// Hold either repeats the key or, once, replaces it with the symbol printed on
// that key. Later repeats do not cycle. Alt or Sym types that symbol directly.
// A shift that already produced a character does not also stick on release.
// Double-tap Shift locks capitals. Double-tap Sym locks symbols the same way.
package helium314.keyboard.latin.hw

import android.text.InputType
import android.view.KeyEvent

internal interface HwLayoutPreferences {
    val enabled: Boolean
    val rotation: List<String>
    val currentId: String
    val altShift: Boolean
    val spaceHold: Boolean
    val toast: Boolean
    /** When true, Sym or a held key types the active layout's symbol. Otherwise the key repeats. */
    val holdForSymbol: Boolean
    /** A short Sym press opens the on-screen symbols keyboard. Sym with another key still types. */
    val symOpensSymbols: Boolean
    val longPressMs: Int
    fun setCurrentId(id: String)
}

internal data class HwField(
    val packageName: String,
    val inputType: Int,
    /**
     * True when this IME is in a text session.
     * Hiding the strip clears the soft-input show request. That is not this flag.
     */
    val inputForced: Boolean = true,
)

/**
 * A connected text session keeps the hardware layout.
 * [InputType.TYPE_NULL] and a session that never started stay raw, so games are left alone.
 * The show request is the wrong signal: it is false whenever the strip is hidden.
 */
internal fun hwFieldIsForced(inputType: Int, inputStarted: Boolean): Boolean =
    inputStarted && inputType != InputType.TYPE_NULL

internal enum class HwModifierMode { OFF, ONCE, LOCK }

internal sealed class HwKeyDecision {
    data object Ignore : HwKeyDecision()
    data object Pass : HwKeyDecision()
    data object Consume : HwKeyDecision()
    data object Enter : HwKeyDecision()
    /** A short press of Sym, with no other key, opens or closes the symbols keyboard. */
    data object Symbols : HwKeyDecision()
    /** The second tap of a Sym double-tap. Symbols stay up, and keys type the printed symbol. */
    data object SymbolLock : HwKeyDecision()
    /** A Sym tap while symbols are locked. Drops the lock. */
    data object SymbolUnlock : HwKeyDecision()
    data class Type(val codePoint: Int, val replaceLast: Boolean) : HwKeyDecision()
    data class Delete(val word: Boolean, val repeat: Boolean) : HwKeyDecision()
    data class Switched(val layoutId: String, val undoSpace: Boolean) : HwKeyDecision()
}

/**
 * Chooses whether the next character uses the shifted slot.
 * A one-shot shift cancels sentence auto-cap. Held shift and shift lock always shift.
 * [autoCap] must already be false when the character is not a letter.
 */
internal fun chooseShift(shiftHeld: Boolean, shiftMode: HwModifierMode, autoCap: Boolean): Boolean {
    if (shiftHeld || shiftMode == HwModifierMode.LOCK) return true
    if (shiftMode == HwModifierMode.ONCE) return !autoCap
    return autoCap
}

/**
 * Titan hardware layout state. Disabled input leaves the legacy decoder alone.
 * Keys stay raw unless a text field requested the IME.
 */
internal class HwLayoutEngine(
    private val prefs: HwLayoutPreferences,
    private val loadMapping: (String) -> HwKeyboardMapping?,
) {
    private var field: HwField? = null
    private var shiftHeld = false
    private var altLeftHeld = false
    private var symHeld = false
    private var symDownAt = -1L
    /** True until another key, a repeat, or a long hold shows this Sym press is not a tap. */
    private var symTapArmed = false
    private var symMode = HwModifierMode.OFF
    private var lastSymUpAt = -1L
    private var shiftMode = HwModifierMode.OFF
    private var shiftPressAt = -1L
    private var lastShiftUpAt = -1L
    private var shiftTyped = false
    private var shiftChord = false

    private var lastKey = -1
    private var lastDownAt = -1L
    private var index = 0
    private var seqShift = false
    private var iterating = false
    /** The letter was replaced by its printed symbol. Further repeats must not rewrite it. */
    private var symbolLatched = false

    /** @return true when the field changed and transient state, including symbol lock, was cleared. */
    fun noteField(next: HwField?): Boolean {
        if (next == field) return false
        field = next
        resetTransient()
        return true
    }

    /** The symbols page was left by something other than this engine. Drop the lock. */
    fun clearSymbolLock() {
        symMode = HwModifierMode.OFF
        lastSymUpAt = -1L
    }

    fun onKeyDown(event: KeyEvent, autoCap: Boolean): HwKeyDecision {
        if (!prefs.enabled) {
            resetTransient()
            return HwKeyDecision.Ignore
        }
        val current = field
        // No editor, or a field that does not take text: leave every key raw.
        if (current == null || current.inputType == InputType.TYPE_NULL) {
            symTapArmed = false
            lastSymUpAt = -1L
            return HwKeyDecision.Pass
        }
        val code = event.keyCode
        val time = event.eventTime
        val repeat = event.repeatCount
        // No text session: leave the key raw. Sym can still open the symbols keyboard
        // when a field exists but the session has not started.
        if (!current.inputForced && !symWhileWindowHidden(code)) {
            symTapArmed = false
            lastSymUpAt = -1L
            return HwKeyDecision.Pass
        }
        // Another key ends the double-tap window, the same way a letter ends a shift double-tap.
        if (!isAltRight(code)) {
            symTapArmed = false
            lastSymUpAt = -1L
        }
        if (isShift(code)) return onShiftDown(event, time, repeat)
        if (isAltLeft(code)) return onAltLeftDown(event, repeat)
        if (isAltRight(code)) return onSymDown(event, repeat)
        if (isCtrl(code) || event.isCtrlPressed) return HwKeyDecision.Pass
        if (code == KeyEvent.KEYCODE_SYM || code == KeyEvent.KEYCODE_PICTSYMBOLS) return HwKeyDecision.Pass
        if (code == KeyEvent.KEYCODE_DEL) return onDelete(event, repeat)
        if (code == KeyEvent.KEYCODE_ENTER) return onEnter(event, repeat)
        if (code == KeyEvent.KEYCODE_SPACE) return onSpace(event, repeat)
        return onCharacter(current, code, event, autoCap, time, repeat)
    }

    fun onKeyUp(event: KeyEvent): HwKeyDecision {
        val current = field
        if (!prefs.enabled || current == null) {
            if (isAltRight(event.keyCode)) releaseSym(event.eventTime, false)
            return HwKeyDecision.Pass
        }
        val time = event.eventTime
        // The window may already be hidden. A text field is enough for this tap.
        val openSymbols = current.inputType != InputType.TYPE_NULL
        return when {
            isShift(event.keyCode) -> {
                onShiftUp(time)
                HwKeyDecision.Pass
            }
            isAltLeft(event.keyCode) -> {
                altLeftHeld = false
                HwKeyDecision.Pass
            }
            isAltRight(event.keyCode) -> releaseSym(time, openSymbols)
            else -> HwKeyDecision.Pass
        }
    }

    private fun onShiftUp(time: Long) {
        val pressAt = shiftPressAt
        val lastUpAt = lastShiftUpAt
        val chord = shiftChord
        val typed = shiftTyped
        val mode = shiftMode
        shiftHeld = false
        if (pressAt < 0L) return
        if (chord) {
            shiftChord = false
            lastShiftUpAt = -1L
            return
        }
        if (typed) {
            shiftTyped = false
            lastShiftUpAt = -1L
            return
        }
        if (time - pressAt < prefs.longPressMs) {
            shiftMode = if (lastUpAt >= 0L && time - lastUpAt < prefs.longPressMs) {
                HwModifierMode.LOCK
            } else {
                if (mode == HwModifierMode.OFF) HwModifierMode.ONCE else HwModifierMode.OFF
            }
        }
        lastShiftUpAt = time
    }

    private fun onShiftDown(event: KeyEvent, time: Long, repeat: Int): HwKeyDecision {
        if (repeat != 0) return HwKeyDecision.Pass
        shiftHeld = true
        shiftPressAt = time
        shiftTyped = false
        if (event.isCtrlPressed) return HwKeyDecision.Pass
        val freeAlt = altLeftHeld || (event.metaState and KeyEvent.META_ALT_LEFT_ON) != 0
        if (prefs.altShift && freeAlt) {
            cycle()?.let {
                shiftChord = true
                return HwKeyDecision.Switched(it, undoSpace = false)
            }
        }
        return HwKeyDecision.Pass
    }

    private fun onAltLeftDown(event: KeyEvent, repeat: Int): HwKeyDecision {
        if (repeat != 0) return HwKeyDecision.Pass
        altLeftHeld = true
        if (event.isCtrlPressed) return HwKeyDecision.Pass
        if (prefs.altShift && (shiftHeld || event.isShiftPressed)) {
            cycle()?.let {
                shiftChord = true
                return HwKeyDecision.Switched(it, undoSpace = false)
            }
        }
        return HwKeyDecision.Pass
    }

    /**
     * Toolbar hidden means the IME window was dismissed, not that this stopped being a text field.
     * A Sym chord still has to type the printed symbol after that window is gone.
     */
    private fun symWhileWindowHidden(code: Int): Boolean {
        if (!prefs.symOpensSymbols) return false
        return isAltRight(code) || symHeld
    }

    /**
     * Sym is held so another key can type the printed symbol.
     * A short press by itself is armed only while that setting is on.
     */
    private fun onSymDown(event: KeyEvent, repeat: Int): HwKeyDecision {
        if (repeat != 0) {
            symTapArmed = false
            return if (symHeld && prefs.symOpensSymbols) HwKeyDecision.Consume else HwKeyDecision.Pass
        }
        symHeld = true
        symDownAt = event.eventTime
        val arm = prefs.symOpensSymbols && !event.isCtrlPressed
        symTapArmed = arm
        return if (arm) HwKeyDecision.Consume else HwKeyDecision.Pass
    }

    private fun releaseSym(time: Long, openSymbols: Boolean): HwKeyDecision {
        val armed = symTapArmed
        val downAt = symDownAt
        symHeld = false
        symTapArmed = false
        symDownAt = -1L
        if (!openSymbols || !armed || !prefs.symOpensSymbols || downAt < 0L || time - downAt >= prefs.longPressMs) {
            lastSymUpAt = -1L
            return HwKeyDecision.Pass
        }
        // Same window as caps lock: the second short tap locks, a later single tap unlocks.
        val doubleTap = lastSymUpAt >= 0L && time - lastSymUpAt < prefs.longPressMs
        lastSymUpAt = time
        if (doubleTap) {
            symMode = HwModifierMode.LOCK
            return HwKeyDecision.SymbolLock
        }
        if (symMode == HwModifierMode.LOCK) {
            symMode = HwModifierMode.OFF
            return HwKeyDecision.SymbolUnlock
        }
        return HwKeyDecision.Symbols
    }

    private fun onDelete(event: KeyEvent, repeat: Int): HwKeyDecision {
        resetIteration()
        val word = event.isShiftPressed || shiftHeld
        if (word) shiftTyped = true
        afterInput()
        return HwKeyDecision.Delete(word, repeat != 0)
    }

    private fun onEnter(event: KeyEvent, repeat: Int): HwKeyDecision {
        if (isSym(event)) return HwKeyDecision.Pass
        if (event.isShiftPressed || shiftHeld) {
            shiftTyped = true
            return HwKeyDecision.Pass
        }
        resetIteration()
        afterInput()
        if (repeat > 0) return HwKeyDecision.Consume
        return HwKeyDecision.Enter
    }

    private fun onSpace(event: KeyEvent, repeat: Int): HwKeyDecision {
        // Sym+Space is the device's private space hook, not a layout switch.
        if (isSym(event)) return HwKeyDecision.Pass
        resetIteration()
        afterInput()
        if (!prefs.spaceHold) return HwKeyDecision.Type(' '.code, replaceLast = false)
        if (repeat == 0) return HwKeyDecision.Type(' '.code, replaceLast = false)
        if (repeat != HwLayouts.SPACE_SWITCH_REPEAT) return HwKeyDecision.Consume
        val next = cycle() ?: return HwKeyDecision.Consume
        return HwKeyDecision.Switched(next, undoSpace = true)
    }

    private fun onCharacter(
        current: HwField,
        code: Int,
        event: KeyEvent,
        autoCap: Boolean,
        time: Long,
        repeat: Int,
    ): HwKeyDecision {
        val numeric = HwLayouts.isNumericInputType(current.inputType)
        val layoutId = if (numeric) HwLayouts.ID_NUMERIC else activeLayoutId()
        val key = loadMapping(layoutId)?.get(code) ?: return HwKeyDecision.Pass
        val symbolLocked = symMode == HwModifierMode.LOCK
        if (key.altValues.isNotEmpty() && (isSym(event) || isAltLeftDown(event))) {
            return onHardwareSymbol(key, event, repeat)
        }
        if (repeat > 0) {
            if (symbolLocked && key.altValues.isNotEmpty()) return onHardwareSymbol(key, event, repeat)
            return onHeldLetter(key, time)
        }

        val isNew = lastKey != code
        val quick = lastDownAt >= 0L && time - lastDownAt <= prefs.longPressMs
        if (symbolLatched && !isNew && quick && key.hasAdditional(alt = true)) {
            index++
            lastKey = code
            lastDownAt = time
            val codePoint = key.get(seqShift, alt = true, index)
            afterInput()
            return HwKeyDecision.Type(codePoint, replaceLast = true)
        }
        if (symbolLocked && key.altValues.isNotEmpty()) return onHardwareSymbol(key, event, repeat)
        symbolLatched = false
        val continueSeq = iterating && !isNew && quick && key.hasAdditional(alt = false)
        val replace = continueSeq && !numeric
        if (continueSeq) {
            index++
        } else {
            val raw = key.get(shift = false, alt = false, index = 0)
            val allowAuto = Character.isLetter(raw)
            seqShift = chooseShift(shiftHeld || event.isShiftPressed, shiftMode, autoCap && allowAuto)
            index = 0
            iterating = key.hasAdditional(alt = false)
        }
        lastKey = code
        lastDownAt = time
        val codePoint = key.get(seqShift, alt = false, index)
        afterInput()
        return HwKeyDecision.Type(codePoint, replace)
    }

    /**
     * Repeat the current character, or replace it once with the symbol printed on the key.
     * PocketBoard does that replacement a single time. Cycling on each repeat flickers.
     */
    private fun onHeldLetter(key: HwKeyMapping, time: Long): HwKeyDecision {
        if (prefs.holdForSymbol && key.altValues.isNotEmpty()) {
            if (symbolLatched) return HwKeyDecision.Consume
            symbolLatched = true
            index = 0
            iterating = false
            lastDownAt = time
            val codePoint = key.get(seqShift, alt = true, index = 0)
            afterInput()
            return HwKeyDecision.Type(codePoint, replaceLast = true)
        }
        symbolLatched = false
        val codePoint = key.get(seqShift, alt = false, index)
        afterInput()
        return HwKeyDecision.Type(codePoint, replaceLast = false)
    }

    /** Alt or Sym types the symbol printed on the key. Shift uses that symbol's shifted form. */
    private fun onHardwareSymbol(key: HwKeyMapping, event: KeyEvent, repeat: Int): HwKeyDecision {
        val shifted = chooseShift(shiftHeld || event.isShiftPressed, shiftMode, autoCap = false)
        if (repeat > 0) {
            // A held Sym still types once. Symbol lock repeats, the way caps lock repeats.
            if (symMode != HwModifierMode.LOCK || isSym(event) || isAltLeftDown(event)) {
                return HwKeyDecision.Consume
            }
            val repeated = key.get(shifted, alt = true, index)
            afterInput()
            return HwKeyDecision.Type(repeated, replaceLast = false)
        }
        val codePoint = key.get(shifted, alt = true, index = 0)
        index = 0
        seqShift = shifted
        iterating = false
        symbolLatched = key.hasAdditional(alt = true)
        lastKey = event.keyCode
        lastDownAt = event.eventTime
        afterInput()
        return HwKeyDecision.Type(codePoint, replaceLast = false)
    }

    /** Advance the remembered layout. Returns null when there is nothing to switch to. */
    fun cycle(): String? {
        val rot = rotation()
        if (rot.size < 2) return null
        val cur = prefs.currentId
        val next = if (cur !in rot) rot.first() else rot[(rot.indexOf(cur) + 1) % rot.size]
        prefs.setCurrentId(next)
        resetIteration()
        return next
    }

    private fun activeLayoutId(): String {
        val rot = rotation()
        val cur = prefs.currentId
        if (cur in rot) return cur
        prefs.setCurrentId(rot.first())
        return rot.first()
    }

    private fun rotation(): List<String> =
        prefs.rotation.filter { it in HwLayouts.CATALOG_IDS }.ifEmpty { listOf(HwLayouts.ID_EN) }

    private fun afterInput() {
        if (shiftHeld) shiftTyped = true
        if (shiftMode == HwModifierMode.ONCE) shiftMode = HwModifierMode.OFF
        lastShiftUpAt = -1L
    }

    private fun resetIteration() {
        lastKey = -1
        lastDownAt = -1L
        index = 0
        seqShift = false
        iterating = false
        symbolLatched = false
    }

    private fun isAltLeftDown(event: KeyEvent): Boolean =
        altLeftHeld || (event.metaState and KeyEvent.META_ALT_LEFT_ON) != 0

    private fun resetTransient() {
        shiftHeld = false
        altLeftHeld = false
        symHeld = false
        symDownAt = -1L
        symTapArmed = false
        symMode = HwModifierMode.OFF
        lastSymUpAt = -1L
        shiftMode = HwModifierMode.OFF
        shiftTyped = false
        shiftChord = false
        shiftPressAt = -1L
        lastShiftUpAt = -1L
        resetIteration()
    }

    private fun isSym(event: KeyEvent): Boolean =
        symHeld || (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0

    private fun isShift(code: Int) = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
    private fun isAltLeft(code: Int) = code == KeyEvent.KEYCODE_ALT_LEFT
    private fun isAltRight(code: Int) = code == KeyEvent.KEYCODE_ALT_RIGHT
    private fun isCtrl(code: Int) = code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT
}
