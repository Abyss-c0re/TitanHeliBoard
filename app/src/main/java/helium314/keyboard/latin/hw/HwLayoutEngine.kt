// SPDX-License-Identifier: GPL-3.0-only
// Layout switching and sticky modifiers follow PocketBoard, with one change:
// a modifier that already produced a character does not also stick on release.
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
    val passthroughExtra: String
    val longPressMs: Int
    fun setCurrentId(id: String)
}

internal data class HwField(val packageName: String, val inputType: Int)

internal enum class HwModifierMode { OFF, ONCE, LOCK }

internal sealed class HwKeyDecision {
    data object Ignore : HwKeyDecision()
    data object Pass : HwKeyDecision()
    data object Consume : HwKeyDecision()
    data object Enter : HwKeyDecision()
    data class Type(val codePoint: Int, val replaceLast: Boolean) : HwKeyDecision()
    data class Delete(val word: Boolean, val repeat: Boolean) : HwKeyDecision()
    data class Switched(val layoutId: String, val undoSpace: Boolean) : HwKeyDecision()
}

/**
 * Chooses whether the next character uses the shifted slot.
 * A one-shot shift cancels sentence auto-cap. Held shift and shift lock always shift.
 * [autoCap] must already be false when the alt layer is on or the character is not a letter.
 */
internal fun chooseShift(shiftHeld: Boolean, shiftMode: HwModifierMode, autoCap: Boolean): Boolean {
    if (shiftHeld || shiftMode == HwModifierMode.LOCK) return true
    if (shiftMode == HwModifierMode.ONCE) return !autoCap
    return autoCap
}

/**
 * Titan hardware layout state. The feature stays off the legacy hardware decoder:
 * when it is disabled the IME is told to [HwKeyDecision.Ignore] that path,
 * and when the focused app should keep raw keys the result is [HwKeyDecision.Pass].
 */
internal class HwLayoutEngine(
    private val prefs: HwLayoutPreferences,
    private val loadMapping: (String) -> HwKeyboardMapping?,
) {
    private var field: HwField? = null
    private var shiftHeld = false
    private var altHeld = false
    private var shiftMode = HwModifierMode.OFF
    private var altMode = HwModifierMode.OFF
    private var shiftPressAt = -1L
    private var altPressAt = -1L
    private var lastShiftUpAt = -1L
    private var lastAltUpAt = -1L
    private var shiftTyped = false
    private var altTyped = false
    private var shiftChord = false
    private var altChord = false

    private var lastKey = -1
    private var lastDownAt = -1L
    private var index = 0
    private var seqShift = false
    private var seqAlt = false
    private var iterating = false
    private var longPressDone = false

    fun noteField(next: HwField?) {
        if (next == field) return
        field = next
        resetTransient()
    }

    fun onKeyDown(event: KeyEvent, autoCap: Boolean): HwKeyDecision {
        if (!prefs.enabled) {
            resetTransient()
            return HwKeyDecision.Ignore
        }
        val current = field
        if (current == null || current.inputType == InputType.TYPE_NULL
            || HwLayouts.isDirectInput(current.packageName, prefs.passthroughExtra)
        ) {
            return HwKeyDecision.Pass
        }

        val code = event.keyCode
        val time = event.eventTime
        val repeat = event.repeatCount
        if (isShift(code)) return onShiftDown(event, time, repeat)
        if (isAlt(code)) return onAltDown(event, time, repeat)
        if (isCtrl(code) || event.isCtrlPressed) return HwKeyDecision.Pass
        if (code == KeyEvent.KEYCODE_SYM || code == KeyEvent.KEYCODE_PICTSYMBOLS) return HwKeyDecision.Pass
        if (code == KeyEvent.KEYCODE_DEL) return onDelete(event, repeat)
        if (code == KeyEvent.KEYCODE_ENTER) return onEnter(event, repeat)
        if (code == KeyEvent.KEYCODE_SPACE) return onSpace(repeat)
        return onCharacter(current, code, event, autoCap, time, repeat)
    }

    fun onKeyUp(event: KeyEvent) {
        if (!prefs.enabled || field == null) return
        val time = event.eventTime
        when {
            isShift(event.keyCode) -> onModifierUp(
                time = time,
                pressAt = shiftPressAt,
                lastUpAt = lastShiftUpAt,
                chord = shiftChord,
                typed = shiftTyped,
                mode = shiftMode,
                clearHeld = { shiftHeld = false },
                clearChord = { shiftChord = false },
                clearTyped = { shiftTyped = false },
                setMode = { shiftMode = it },
                setLastUp = { lastShiftUpAt = it },
            )
            isAlt(event.keyCode) -> onModifierUp(
                time = time,
                pressAt = altPressAt,
                lastUpAt = lastAltUpAt,
                chord = altChord,
                typed = altTyped,
                mode = altMode,
                clearHeld = { altHeld = false },
                clearChord = { altChord = false },
                clearTyped = { altTyped = false },
                setMode = { altMode = it },
                setLastUp = { lastAltUpAt = it },
            )
        }
    }

    private fun onModifierUp(
        time: Long,
        pressAt: Long,
        lastUpAt: Long,
        chord: Boolean,
        typed: Boolean,
        mode: HwModifierMode,
        clearHeld: () -> Unit,
        clearChord: () -> Unit,
        clearTyped: () -> Unit,
        setMode: (HwModifierMode) -> Unit,
        setLastUp: (Long) -> Unit,
    ) {
        clearHeld()
        // A field change forgets the press. The matching key-up must not stick a new one-shot.
        if (pressAt < 0L) return
        if (chord) {
            clearChord()
            setLastUp(-1L)
            return
        }
        if (typed) {
            clearTyped()
            setLastUp(-1L)
            return
        }
        if (time - pressAt < prefs.longPressMs) {
            if (lastUpAt >= 0L && time - lastUpAt < prefs.longPressMs) setMode(HwModifierMode.LOCK)
            else setMode(toggle(mode))
        }
        setLastUp(time)
    }

    private fun onShiftDown(event: KeyEvent, time: Long, repeat: Int): HwKeyDecision {
        if (repeat != 0) return HwKeyDecision.Pass
        shiftHeld = true
        shiftPressAt = time
        shiftTyped = false
        if (event.isCtrlPressed) return HwKeyDecision.Pass
        if (prefs.altShift && (altHeld || event.isAltPressed)) {
            cycle()?.let {
                shiftChord = true
                altChord = true
                return HwKeyDecision.Switched(it, undoSpace = false)
            }
        }
        return HwKeyDecision.Pass
    }

    private fun onAltDown(event: KeyEvent, time: Long, repeat: Int): HwKeyDecision {
        if (repeat != 0) return HwKeyDecision.Pass
        altHeld = true
        altPressAt = time
        altTyped = false
        if (event.isCtrlPressed) return HwKeyDecision.Pass
        if (prefs.altShift && (shiftHeld || event.isShiftPressed)) {
            cycle()?.let {
                shiftChord = true
                altChord = true
                return HwKeyDecision.Switched(it, undoSpace = false)
            }
        }
        return HwKeyDecision.Pass
    }

    private fun onDelete(event: KeyEvent, repeat: Int): HwKeyDecision {
        resetIteration()
        val word = event.isShiftPressed || shiftHeld
        if (word) shiftTyped = true
        afterInput()
        return HwKeyDecision.Delete(word, repeat != 0)
    }

    private fun onEnter(event: KeyEvent, repeat: Int): HwKeyDecision {
        if (event.isShiftPressed || shiftHeld) {
            shiftTyped = true
            return HwKeyDecision.Pass
        }
        resetIteration()
        afterInput()
        if (repeat > 0) return HwKeyDecision.Consume
        return HwKeyDecision.Enter
    }

    private fun onSpace(repeat: Int): HwKeyDecision {
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
        if (repeat > 0) {
            if (longPressDone) return HwKeyDecision.Consume
            if (!numeric && !seqAlt && key.altValues.isNotEmpty() && time - lastDownAt > prefs.longPressMs) {
                seqAlt = true
                index = 0
                longPressDone = true
                lastDownAt = time
                afterInput()
                return HwKeyDecision.Type(key.get(seqShift, alt = true, index = 0), replaceLast = true)
            }
            return HwKeyDecision.Consume
        }

        val altOn = altHeld || event.isAltPressed || altMode != HwModifierMode.OFF
        val isNew = lastKey != code
        val quick = lastDownAt >= 0L && time - lastDownAt <= prefs.longPressMs
        val continueSeq = iterating && !isNew && quick && key.hasAdditional(seqAlt)
        val replace = continueSeq && !numeric
        if (continueSeq) {
            index++
        } else {
            val raw = key.get(shift = false, alt = altOn, index = 0)
            val allowAuto = !altOn && Character.isLetter(raw)
            seqShift = chooseShift(shiftHeld || event.isShiftPressed, shiftMode, autoCap && allowAuto)
            seqAlt = altOn
            index = 0
            iterating = key.hasAdditional(seqAlt)
            longPressDone = false
        }
        lastKey = code
        lastDownAt = time
        val codePoint = key.get(seqShift, seqAlt, index)
        afterInput()
        return HwKeyDecision.Type(codePoint, replace)
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
        if (altHeld) altTyped = true
        if (shiftMode == HwModifierMode.ONCE) shiftMode = HwModifierMode.OFF
        if (altMode == HwModifierMode.ONCE) altMode = HwModifierMode.OFF
        lastShiftUpAt = -1L
        lastAltUpAt = -1L
    }

    private fun resetIteration() {
        lastKey = -1
        lastDownAt = -1L
        index = 0
        seqShift = false
        seqAlt = false
        iterating = false
        longPressDone = false
    }

    private fun resetTransient() {
        shiftHeld = false
        altHeld = false
        shiftMode = HwModifierMode.OFF
        altMode = HwModifierMode.OFF
        shiftTyped = false
        altTyped = false
        shiftChord = false
        altChord = false
        shiftPressAt = -1L
        altPressAt = -1L
        lastShiftUpAt = -1L
        lastAltUpAt = -1L
        resetIteration()
    }

    private fun toggle(mode: HwModifierMode): HwModifierMode =
        if (mode == HwModifierMode.OFF) HwModifierMode.ONCE else HwModifierMode.OFF

    private fun isShift(code: Int) = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
    private fun isAlt(code: Int) = code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT
    private fun isCtrl(code: Int) = code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT
}
