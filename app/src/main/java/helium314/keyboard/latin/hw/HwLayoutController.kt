// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.hw

import android.content.Context
import android.view.KeyEvent
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale

internal enum class HwDispatch { IGNORE, PASS, CONSUME }

/** Names for the built-in Titan layouts. English only; HeliBoard translations go through Weblate. */
fun hwLayoutLabel(context: Context, id: String): String {
    val res = when (id) {
        HwLayouts.ID_EN -> R.string.hw_layout_name_en_us
        HwLayouts.ID_RU_BBP -> R.string.hw_layout_name_ru_bbp
        HwLayouts.ID_ES -> R.string.hw_layout_name_es_es
        else -> 0
    }
    return if (res == 0) id else context.getString(res)
}

/**
 * Applies [HwLayoutEngine] decisions to LatinIME.
 * Mapped characters go through [LatinIME.onEvent] with meta state 0 so they stay text,
 * not functional hardware events. Unmapped keys and direct-input apps are not consumed.
 */
internal class HwLayoutController(private val latinIME: LatinIME) {
    private val store = HwLayoutStore(latinIME)
    private val maps = HashMap<String, HwKeyboardMapping?>()
    private val engine = HwLayoutEngine(store, ::loadMapping)
    private val consumedDown = HashSet<Int>()
    /** A Sym tap that would close the symbols page, waiting to see if the next tap locks it. */
    private var pendingSym: Runnable? = null

    init {
        KeyboardSwitcher.getInstance().setOnHardwareSymbolLockCleared {
            engine.clearSymbolLock()
            cancelPendingSym()
        }
    }

    /** Hardware layouts are handling keys for this field. */
    fun isActive(): Boolean = store.enabled

    fun onKeyDown(keyCode: Int, event: KeyEvent): HwDispatch {
        val field = currentField()
        if (engine.noteField(field)) {
            cancelPendingSym()
            KeyboardSwitcher.getInstance().clearHardwareSymbolsLock()
        } else if (keyCode != KeyEvent.KEYCODE_ALT_RIGHT) {
            // A letter confirms the Sym tap was single. Run a delayed close before that letter.
            flushPendingSym()
        }
        val autoCap = if (field != null) {
            runCatching { latinIME.currentAutoCapsState != 0 }.getOrDefault(false)
        } else {
            false
        }
        return apply(keyCode, event, engine.onKeyDown(event, autoCap))
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val field = currentField()
        if (engine.noteField(field)) {
            cancelPendingSym()
            KeyboardSwitcher.getInstance().clearHardwareSymbolsLock()
        }
        val decision = engine.onKeyUp(event)
        val consumedEarlier = consumedDown.remove(keyCode)
        when (decision) {
            HwKeyDecision.Symbols -> {
                val switcher = KeyboardSwitcher.getInstance()
                if (switcher.isShowingSymbolsKeyboard()) {
                    // The second tap of a double-tap must lock, not close. Wait out that window.
                    scheduleSym {
                        if (switcher.isShowingSymbolsKeyboard()) switcher.toggleSymbolsFromPhysicalSym()
                    }
                } else {
                    cancelPendingSym()
                    try {
                        switcher.toggleSymbolsFromPhysicalSym()
                    } catch (t: Throwable) {
                        Log.e(TAG, "symbols keyboard failed", t)
                    }
                }
                return true
            }
            HwKeyDecision.SymbolLock -> {
                try {
                    cancelPendingSym()
                    KeyboardSwitcher.getInstance().lockSymbolsFromPhysicalSym()
                    showSymLockToast()
                } catch (t: Throwable) {
                    Log.e(TAG, "symbol lock failed", t)
                }
                return true
            }
            HwKeyDecision.SymbolUnlock -> {
                // Same window as the lock, so a second tap can keep the lock instead of closing it.
                scheduleSym { KeyboardSwitcher.getInstance().unlockSymbolsFromPhysicalSym() }
                return true
            }
            else -> Unit
        }
        return consumedEarlier
    }

    /** A text session is active. Lone Alt can be consumed instead of reaching the editor. */
    fun ownsTextField(): Boolean = currentField()?.inputForced == true

    private fun currentField(): HwField? {
        val info = latinIME.currentInputEditorInfo ?: return null
        if (latinIME.currentInputConnection == null) return null
        return HwField(
            packageName = info.packageName.orEmpty(),
            inputType = info.inputType,
            // isShowInputRequested is false while the strip is hidden. The editor is still ours.
            inputForced = hwFieldIsForced(info.inputType, latinIME.currentInputStarted),
        )
    }

    private fun apply(keyCode: Int, event: KeyEvent, decision: HwKeyDecision): HwDispatch = when (decision) {
        HwKeyDecision.Ignore -> HwDispatch.IGNORE
        HwKeyDecision.Pass -> finish(keyCode, event.repeatCount, consume = false)
        HwKeyDecision.Consume -> finish(keyCode, event.repeatCount, consume = true)
        is HwKeyDecision.Type -> {
            if (!canCommit()) finish(keyCode, event.repeatCount, consume = false)
            else {
                if (decision.replaceLast) send(Event.NOT_A_CODE_POINT, KeyCode.DELETE, repeat = false)
                send(decision.codePoint, keyCode, repeat = false)
                finish(keyCode, event.repeatCount, consume = true)
            }
        }
        is HwKeyDecision.Delete -> {
            if (!canCommit()) finish(keyCode, event.repeatCount, consume = false)
            else {
                send(
                    Event.NOT_A_CODE_POINT,
                    if (decision.word) KeyCode.DELETE_WORD else KeyCode.DELETE,
                    decision.repeat,
                )
                finish(keyCode, event.repeatCount, consume = true)
            }
        }
        HwKeyDecision.Symbols,
        HwKeyDecision.SymbolLock,
        HwKeyDecision.SymbolUnlock -> finish(keyCode, event.repeatCount, consume = true)
        HwKeyDecision.Enter -> {
            if (!canCommit()) finish(keyCode, event.repeatCount, consume = false)
            else {
                send(Constants.CODE_ENTER, KeyEvent.KEYCODE_ENTER, repeat = false)
                finish(keyCode, event.repeatCount, consume = true)
            }
        }
        is HwKeyDecision.Switched -> {
            // A toast or a subtype reload must not escape this key. An exception here used to
            // kill the process or skip the consume, and the framework then showed its character picker.
            try {
                if (decision.undoSpace && canCommit()) send(Event.NOT_A_CODE_POINT, KeyCode.DELETE, repeat = false)
                syncSubtype(decision.layoutId)
                if (store.toast) showLayoutToast(decision.layoutId)
                Log.i(TAG, "hardware layout -> ${decision.layoutId}")
            } catch (t: Throwable) {
                Log.e(TAG, "hardware layout switch failed", t)
            }
            finish(keyCode, event.repeatCount, consume = true)
        }
    }

    private fun finish(keyCode: Int, repeat: Int, consume: Boolean): HwDispatch {
        if (consume || (repeat > 0 && keyCode in consumedDown)) {
            consumedDown += keyCode
            return HwDispatch.CONSUME
        }
        consumedDown.remove(keyCode)
        return HwDispatch.PASS
    }

    private fun canCommit(): Boolean = Settings.getInstance().current != null

    private fun send(codePoint: Int, keyCode: Int, repeat: Boolean) {
        try {
            latinIME.onEvent(Event.createHardwareKeypressEvent(codePoint, keyCode, 0, null, repeat))
        } catch (t: RuntimeException) {
            Log.e(TAG, "hardware event failed", t)
        }
    }

    private fun syncSubtype(layoutId: String) {
        val language = HwLayouts.languageOf(layoutId) ?: return
        try {
            if (!RichInputMethodManager.isInitialized()) return
            val current = RichInputMethodManager.getInstance().currentSubtypeLocale.language
            if (current == language) return
            val match = SubtypeSettings.getEnabledSubtypes(true)
                .firstOrNull { it.locale().language == language } ?: return
            latinIME.switchToSubtypeFromHardwareLayout(match)
        } catch (t: RuntimeException) {
            Log.w(TAG, "subtype sync skipped", t)
        }
    }

    private fun scheduleSym(action: () -> Unit) {
        cancelPendingSym()
        val task = Runnable {
            pendingSym = null
            try {
                action()
            } catch (t: Throwable) {
                Log.e(TAG, "symbol key failed", t)
            }
        }
        pendingSym = task
        latinIME.mHandler.postDelayed(task, store.longPressMs.toLong())
    }

    private fun flushPendingSym() {
        val task = pendingSym ?: return
        pendingSym = null
        latinIME.mHandler.removeCallbacks(task)
        task.run()
    }

    private fun cancelPendingSym() {
        val task = pendingSym ?: return
        pendingSym = null
        latinIME.mHandler.removeCallbacks(task)
    }

    private fun showSymLockToast() {
        KeyboardSwitcher.getInstance().showToast(latinIME.getString(R.string.hw_sym_locked), true)
    }

    private fun showLayoutToast(layoutId: String) {
        val name = hwLayoutLabel(latinIME, layoutId)
        val text = latinIME.getString(R.string.hw_layout_switched, name)
        // System toasts throw or are dropped from an IME on API 33+. The in-window toast does not.
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun loadMapping(id: String): HwKeyboardMapping? {
        if (id in maps) return maps[id]
        val resId = resourceFor(id)
        val parsed = if (resId == 0) null else try {
            val xml = latinIME.resources.getXml(resId)
            try {
                HwMappingParser.parse(xml)
            } finally {
                xml.close()
            }
        } catch (t: RuntimeException) {
            Log.e(TAG, "failed to parse hardware layout $id", t)
            null
        }
        maps[id] = parsed
        return parsed
    }

    private fun resourceFor(id: String): Int = when (id) {
        HwLayouts.ID_EN -> R.xml.keyboard_mapping_en_us_titan
        HwLayouts.ID_RU_BBP -> R.xml.keyboard_mapping_ru_bbp_titan
        HwLayouts.ID_ES -> R.xml.keyboard_mapping_es_es_titan
        HwLayouts.ID_NUMERIC -> R.xml.keyboard_mapping_numeric_titan
        else -> 0
    }

    private companion object {
        const val TAG = "HwLayout"
    }
}
