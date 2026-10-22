/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.LayoutDirective;
import helium314.keyboard.keyboard.internal.ShiftMode;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.CapsMode;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsKt;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private View mEmojiTabStripView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private TextView mFakeToastView;
    private ImageView mBackgroundGatheringIndicator;
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;
    /** Letter keys are hidden, so the strip must clear the navigation / gesture bar. */
    private boolean mHardwareStripNeedsBottomInset;

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;
    /** reloadMainKeyboard restores symbols after the alphabet load. That load must not hide the window. */
    private boolean mReloadingMainKeyboard;
    /** Double-tap Sym. Space must not leave this symbols page, and keys type the printed symbol. */
    private boolean mHardwareSymbolsLocked;
    /** Set while opening the locked symbols page, so that open does not clear the lock it just took. */
    private boolean mApplyingHardwareSymbolLock;
    @Nullable private Runnable mOnHardwareSymbolLockCleared;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(new SwitchActions());
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale, settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null && mLatinIME.hasSuggestionStripView()
                    == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context, final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.Companion.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(EditorInfo editorInfo, SettingsValues settingsValues, int currentAutoCapsState,
                             @Nullable RecapitalizeMode currentRecapitalizeState, KeyboardLayoutSet.InternalAction internalAction) {
        KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(mThemeContext, editorInfo, settingsValues);
        int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        int keyboardHeight = ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues);
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(mRichImm.getCurrentSubtype())
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, settingsValues.mOneHandedModeEnabled);
        } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.getKeyboardId(), e.getCause());
            try {
                InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE.createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, false);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSet.Companion.KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.getKeyboardId(), e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState();
        }
    }

    public void onHideWindow() {
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    @Nullable public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet() {
        mState.onResetKeyboardStateToAlphabet(mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState());
    }

    public void onPressKey(int code, int pointerCount, int currentAutoCapsState,
            @Nullable RecapitalizeMode currentRecapitalizeState) {
        mState.onPressKey(code, pointerCount, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    public void setEmojiKeyboard() {
        mState.setLayout(LayoutDirective.Utility.EMOJI);
    }

    public void setClipboardKeyboard() {
        mState.setLayout(LayoutDirective.Utility.CLIPBOARD);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    /**
     * Top of the strip inside a full-screen input window, above the navigation-bar inset.
     * Returns 0 when that top would be above the window.
     */
    public static int visibleTopAboveNavigationBar(final int inputHeight, final int stripHeight, final int bottomInset) {
        return Math.max(0, inputHeight - Math.max(0, stripHeight) - Math.max(0, bottomInset));
    }

    public int getHardwareStripBottomInset() {
        if (!mHardwareStripNeedsBottomInset || mMainKeyboardFrame == null) {
            return 0;
        }
        return mMainKeyboardFrame.getPaddingBottom();
    }

    private void clearHardwareStripBottomInset() {
        mHardwareStripNeedsBottomInset = false;
        applyHardwareStripBottomInset(mMainKeyboardFrame, ViewCompat.getRootWindowInsets(mMainKeyboardFrame));
    }

    private void applyHardwareStripBottomInset(@Nullable final View frame, @Nullable final WindowInsetsCompat insets) {
        if (frame == null) {
            return;
        }
        final int bottom = mHardwareStripNeedsBottomInset ? navigationBarBottom(insets) : 0;
        if (frame.getPaddingBottom() != bottom) {
            frame.setPadding(frame.getPaddingLeft(), frame.getPaddingTop(), frame.getPaddingRight(), bottom);
        }
    }

    private static int navigationBarBottom(@Nullable final WindowInsetsCompat insets) {
        if (insets == null) {
            return 0;
        }
        return insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState,
            final boolean alphabetLayout,
            final boolean dismissAlphabet) {
        // Alphabet keys stay down while the hardware strip is up, and while "only toolbar" is on.
        // Symbol, number, emoji, and d-pad layouts are requested from the toolbar and stay visible.
        // Leaving symbols while the toolbar is hidden must not put the letter keys back.
        final boolean hideLetterKeys = alphabetLayout
                && (dismissAlphabet
                || mLatinIME.shouldHideHardwareLetterKeys()
                || Settings.getInstance().readShowToolbarOnly());
        final boolean suppressed = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState)
                && !hideLetterKeys;
        final int stripVisibility = (dismissAlphabet || !mLatinIME.hasSuggestionStripView())
                ? View.GONE : View.VISIBLE;
        final int keyVisibility = (suppressed || hideLetterKeys) ? View.GONE : View.VISIBLE;
        final int frameVisibility = suppressed ? View.GONE : View.VISIBLE;
        final int wrapperVisibility = (suppressed || hideLetterKeys) ? View.GONE : View.VISIBLE;
        mStripContainer.setVisibility(stripVisibility);
        // The letter keys normally keep the strip above the gesture bar. With them gone, pad
        // the frame by the navigation-bar inset or the strip is drawn underneath it.
        // A window that is about to close does not need that pad.
        mHardwareStripNeedsBottomInset = hideLetterKeys && !dismissAlphabet;
        applyHardwareStripBottomInset(mMainKeyboardFrame, ViewCompat.getRootWindowInsets(mMainKeyboardFrame));
        PointerTracker.switchTo(mKeyboardView);
        mKeyboardView.setVisibility(keyVisibility);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame.setVisibility(frameVisibility);
        mKeyboardViewWrapper.setVisibility(wrapperVisibility);
        mEmojiPalettesView.setVisibility(View.GONE);
        mEmojiPalettesView.stopEmojiPalettes();
        mEmojiTabStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(stripVisibility);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardHistoryView.stopClipboardHistory();
    }

    public void toggleLayout(@NonNull LayoutDirective.Utility layout, int autoCapsFlags, @Nullable RecapitalizeMode recapitalizeMode) {
        mState.toggleLayout(layout, autoCapsFlags, recapitalizeMode);
    }

    public void onLongPressAlphaSymbolForNumpad() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "onLongPressAlphaSymbol");
        }
        mState.onLongPressAlphaSymbolForNumpad();
    }

    public enum KeyboardSwitchState {
        HIDDEN(null),
        SYMBOLS_SHIFTED(KeyboardElement.SYMBOLS_SHIFTED),
        EMOJI(KeyboardElement.EMOJI_RECENTS),
        CLIPBOARD(KeyboardElement.CLIPBOARD),
        OTHER(null);

        @Nullable final KeyboardElement mKeyboardElement;

        KeyboardSwitchState(@Nullable KeyboardElement keyboardElement) {
            mKeyboardElement = keyboardElement;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory()
                && (mKeyboardLayoutSet == null
                || mKeyboardView == null
                || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingKeyboardId(KeyboardElement.SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            // The letter keys are gone, so this state looks hidden. Closing emoji must not
            // dismiss the suggestion strip; a hardware keyboard will not ask for it again.
            if (mLatinIME.hardwareCandidateBarWanted() && !mLatinIME.isSoftKeyboardShown()) {
                resetKeyboardStateToAlphabet();
                return;
            }
            mLatinIME.stopShowingInputView();
            mLatinIME.hideWindow();
            resetKeyboardStateToAlphabet();
        } else {
            // The strip's window is already up. startShowingInputView would flash the letter keys.
            if (!mLatinIME.isInputViewShown()) {
                mLatinIME.startShowingInputView(true);
            }
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard();
            } else {
                mEmojiPalettesView.stopEmojiPalettes();
                mEmojiPalettesView.setVisibility(View.GONE);

                mClipboardHistoryView.stopClipboardHistory();
                mClipboardHistoryView.setVisibility(View.GONE);

                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                if (toggleState == KeyboardSwitchState.SYMBOLS_SHIFTED)
                    // possible other states OTHER and HIDDEN have keyboardElement null, which we just ignore
                    // might need to be adjusted when functionality is extended
                    mState.setLayout(LayoutDirective.Utility.SYMBOLS_SHIFTED);
            }
        }
    }

    /** Lone Alt, when that physical-keyboard choice is the toolbar. */
    public void toggleToolbarFromPhysicalAlt() {
        mLatinIME.toggleToolbarFromPhysicalAlt();
    }

    /** The suggestion strip, emoji, or clipboard is on screen. The symbols keyboard is not this panel. */
    public boolean isHardwarePanelShown() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory()) return true;
        return mStripContainer != null && mStripContainer.isShown();
    }

    /**
     * Drop the symbols page before Alt shows or hides the toolbar.
     * [reloadMainKeyboard] would put that page back.
     * Hidden dismisses the window from here. A restored toolbar mode shows the strip.
     */
    public void leaveSymbolsForHardwareToolbar() {
        if (mState == null || !hasSymbolsKeyboard()) return;
        mState.forceAlphabetKeyboard(
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState());
    }

    /** Remove the strip and every panel in it. Letter keys stay untouched until the caller hides or reloads. */
    public void hideHardwareCandidatePanel() {
        if (mStripContainer != null) mStripContainer.setVisibility(View.GONE);
        if (mSuggestionStripView != null) mSuggestionStripView.setVisibility(View.GONE);
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
            mEmojiPalettesView.setVisibility(View.GONE);
        }
        if (mEmojiTabStripView != null) mEmojiTabStripView.setVisibility(View.GONE);
        if (mClipboardStripScrollView != null) mClipboardStripScrollView.setVisibility(View.GONE);
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
            mClipboardHistoryView.setVisibility(View.GONE);
        }
        if (mKeyboardView != null) mKeyboardView.setVisibility(View.GONE);
        if (mKeyboardViewWrapper != null) mKeyboardViewWrapper.setVisibility(View.GONE);
        if (mMainKeyboardFrame != null) mMainKeyboardFrame.setVisibility(View.GONE);
        clearHardwareStripBottomInset();
    }

    public void updateShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "updateShiftState: " + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags) + " recapitalizeMode=" + recapitalizeMode);
        }
        mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        // oneHandeMode is always disabled when floating, and we shouldn't mess up the setting
        if (enabled != settings.getCurrent().mOneHandedModeEnabled)
            settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
            !settings.getCurrent().mIsSplitKeyboardEnabled,
            mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
            FoldableUtils.INSTANCE.isFolded()
        );
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout.
        // The alphabet load in the middle must not treat that as leaving the symbols page.
        final boolean wasEmoji = isShowingEmojiPalettes();
        final boolean wasClipboard = isShowingClipboardHistory();
        final boolean wasSymbols = isShowingSymbolsKeyboard();
        // The alphabet keyboard loaded below must not flash while symbols are put back.
        final boolean holdLetters = wasSymbols && mLatinIME.shouldDismissAlphabetAfterSymbols();
        if (holdLetters) mLatinIME.setOpeningHardwareSymbols(true);
        mReloadingMainKeyboard = true;
        try {
            loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                    mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
            if (wasEmoji) {
                setEmojiKeyboard();
            } else if (wasClipboard) {
                setClipboardKeyboard();
            } else if (wasSymbols) {
                showSymbolsKeyboard();
            }
        } finally {
            mReloadingMainKeyboard = false;
            if (holdLetters) mLatinIME.setOpeningHardwareSymbols(false);
        }
    }

    /** The on-screen symbols keyboard, or its second page, is showing. */
    public boolean isShowingSymbolsKeyboard() {
        return isShowingKeyboardId(KeyboardElement.SYMBOLS, KeyboardElement.SYMBOLS_SHIFTED);
    }

    /**
     * The symbols layout is the current keyboard. Unlike {@link #isShowingSymbolsKeyboard()},
     * this is true before the window has attached, which is when a hidden toolbar used to
     * throw the page away.
     */
    public boolean hasSymbolsKeyboard() {
        if (mKeyboardView == null) return false;
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) return false;
        return isSymbolsElement(keyboard.mId.getElement());
    }

    /** Show the existing symbols layout. The symbols key on that layout opens more symbols. */
    public void showSymbolsKeyboard() {
        if (mKeyboardView == null || mState == null) return;
        mState.setLayout(LayoutDirective.Utility.SYMBOLS);
    }

    /**
     * Short Sym press. Opens the existing symbols keyboard, or closes it.
     * The hardware strip stays when that strip is why the window is up.
     * A hidden toolbar hides the window instead of leaving the letter keys.
     */
    public void toggleSymbolsFromPhysicalSym() {
        if (isShowingSymbolsKeyboard()) {
            // Alphabet layout sees the symbols keyboard that is still attached and,
            // when the toolbar is hidden, drops the window after the keys are gone.
            resetKeyboardStateToAlphabet();
            return;
        }
        openSymbolsFromPhysicalSym();
    }

    /** Second tap of a Sym double-tap. Keeps the symbols page up. */
    public void lockSymbolsFromPhysicalSym() {
        mApplyingHardwareSymbolLock = true;
        try {
            mHardwareSymbolsLocked = true;
            if (!isShowingSymbolsKeyboard() && !hasSymbolsKeyboard()) {
                openSymbolsFromPhysicalSym();
            }
        } finally {
            mApplyingHardwareSymbolLock = false;
        }
    }

    /** A later Sym tap drops the lock and leaves the symbols page. */
    public void unlockSymbolsFromPhysicalSym() {
        mHardwareSymbolsLocked = false;
        if (isShowingSymbolsKeyboard() || hasSymbolsKeyboard()) {
            resetKeyboardStateToAlphabet();
        }
    }

    public boolean isHardwareSymbolsLocked() {
        return mHardwareSymbolsLocked;
    }

    /** The field changed. The engine already dropped its lock. */
    public void clearHardwareSymbolsLock() {
        mHardwareSymbolsLocked = false;
    }

    public void setOnHardwareSymbolLockCleared(@Nullable final Runnable listener) {
        mOnHardwareSymbolLockCleared = listener;
    }

    /**
     * The symbols page is going away on its own.
     * Tell the hardware layout to stop typing printed symbols.
     */
    public void onHardwareSymbolsLeft() {
        if (mApplyingHardwareSymbolLock) return;
        mHardwareSymbolsLocked = false;
        if (mOnHardwareSymbolLockCleared != null) mOnHardwareSymbolLockCleared.run();
    }

    private void openSymbolsFromPhysicalSym() {
        // isShowingSymbolsKeyboard() is false until the input frame is visible.
        // Hold that frame open across the gap, including a Hidden toolbar.
        mLatinIME.setOpeningHardwareSymbols(true);
        try {
            if (!mLatinIME.isInputViewShown()) {
                // Do not use startShowingInputView. That forces the letter keys on for one pass.
                // showHardwareWindow does not restart the editor session.
                mLatinIME.showHardwareWindow();
            }
            showSymbolsKeyboard();
            mLatinIME.updateInputViewShown();
        } finally {
            mLatinIME.setOpeningHardwareSymbols(false);
        }
        if (!hasSymbolsKeyboard()) {
            // The page did not attach. Do not leave an empty frame from the opening flag.
            mLatinIME.updateInputViewShown();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it will last longer.
     */
    public void showToast(final String text, final boolean briefToast){
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().isSecondaryStripVisible()? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView == null || mFakeToastView.getVisibility() == View.VISIBLE) return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            if (mFakeToastView == null) return;
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    public void setBackgroundGatheringIndicator(boolean enabled, boolean hasData, boolean saving) {
        if (mCurrentInputView == null) return;
        mBackgroundGatheringIndicator.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        mBackgroundGatheringIndicator.setImageResource(hasData ? R.drawable.btn_keyboard_key_action_normal_lxx_base : R.drawable.ring);
        setBackgroundGatheringIndicatorPosition();
        if (!saving) return;
        mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xff00a000));
        mBackgroundGatheringIndicator.postDelayed(() -> mBackgroundGatheringIndicator.setImageTintList(ColorStateList.valueOf(0xffa00000)), 1500);
    }

    private void setBackgroundGatheringIndicatorPosition() {
        if (mBackgroundGatheringIndicator == null || mBackgroundGatheringIndicator.getVisibility() != View.VISIBLE) return;
        if (mBackgroundGatheringIndicator.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margin) {
            Keyboard kb = mKeyboardView.getKeyboard();
            if (kb != null)
                margin.topMargin = kb.mOccupiedHeight - KtxKt.dpToPx(16, mCurrentInputView.getResources());
            mBackgroundGatheringIndicator.setLayoutParams(mBackgroundGatheringIndicator.getLayoutParams());
        }
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull KeyboardElement... keyboardElements) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        KeyboardElement activeKeyboardId = keyboard.mId.getElement();
        for (KeyboardElement keyboardElement : keyboardElements) {
            if (activeKeyboardId == keyboardElement) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isShown();
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.isShown();
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            return false;
        }
        return mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer.isShown();
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() { return mStripContainer; }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, boolean isHardwareAcceleratedDrawingEnabled) {
        Log.d(TAG, "create new input view");
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        PointerTracker.clearOldViewData();
        SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale, Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView)LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        ViewCompat.setOnApplyWindowInsetsListener(mMainKeyboardFrame, (view, insets) -> {
            applyHardwareStripBottomInset(view, insets);
            return insets;
        });
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mBackgroundGatheringIndicator = mCurrentInputView.findViewById(R.id.backgroundGatheringIndicator);

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    public CapsMode getKeyboardCapsMode() {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return CapsMode.OFF;
        }
        return keyboard.mId.getElement().getCapsMode();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    /** Marks the theme as outdated. The theme will be reloaded next time the keyboard is shown.
     *  If the keyboard is currently showing, theme will be reloaded immediately. */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws "IllegalStateException: Window token is not set yet."
        }
    }

    // private SwitchActions implementation so e.g. setEmojiKeyboard can only be called via KeyboardState (avoid inconsistencies!)
    private class SwitchActions implements KeyboardState.SwitchActions {
        @Override
        public void setAlphabetKeyboard(@NonNull ShiftMode shiftMode) {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setAlphabetKeyboard");
            }
            setKeyboard(shiftMode.element, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setSymbolsShiftedKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setSymbolsShiftedKeyboard");
            }
            setKeyboard(KeyboardElement.SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
        }

        @Override
        public void setEmojiKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setEmojiKeyboard");
            }
            clearHardwareStripBottomInset();
            mKeyboardViewWrapper.setVisibility(View.VISIBLE);
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mKeyboardView.setVisibility(View.GONE);
            mSuggestionStripView.setVisibility(View.GONE);
            mStripContainer.setVisibility(getSecondaryStripVisibility());
            mClipboardStripScrollView.setVisibility(View.GONE);
            mEmojiTabStripView.setVisibility(View.VISIBLE);
            mClipboardHistoryView.setVisibility(View.GONE);
            mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            mEmojiPalettesView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setClipboardKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setClipboardKeyboard");
            }
            clearHardwareStripBottomInset();
            mKeyboardViewWrapper.setVisibility(View.VISIBLE);
            mMainKeyboardFrame.setVisibility(View.VISIBLE);
            // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
            // @see #getVisibleKeyboardView() and
            // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
            mKeyboardView.setVisibility(View.GONE);
            mEmojiTabStripView.setVisibility(View.GONE);
            mSuggestionStripView.setVisibility(View.GONE);
            mStripContainer.setVisibility(getSecondaryStripVisibility());
            mClipboardStripScrollView.post(() -> mClipboardStripScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
            mClipboardStripScrollView.setVisibility(View.VISIBLE);
            mEmojiPalettesView.setVisibility(View.GONE);
            mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(), mKeyboardView.getKeyVisualAttribute(),
                mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
            mClipboardHistoryView.setVisibility(View.VISIBLE);
        }

        @Override
        public void setNumpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setNumpadKeyboard");
            }
            setKeyboard(KeyboardElement.NUMPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void setDpadKeyboard() {
            if (DEBUG_ACTION) {
                Log.d(TAG, "setDpadKeyboard");
            }
            setKeyboard(KeyboardElement.DPAD, KeyboardSwitchState.OTHER);
        }

        @Override
        public void startDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "startDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.startDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void cancelDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "cancelDoubleTapShiftKeyTimer");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.cancelDoubleTapShiftKeyTimer();
            }
        }

        @Override
        public void setOneHandedModeEnabled(boolean enabled) {
            KeyboardSwitcher.this.setOneHandedModeEnabled(enabled, false);
        }

        @Override
        public void switchOneHandedMode() {
            mKeyboardViewWrapper.switchOneHandedModeSide();
            Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
        }

        @Override
        public void setFloatingKeyboardEnabled(boolean enabled) {
            if (enabled != Settings.getValues().mIsFloatingKeyboard)
                // mIsFloatingKeyboard is always disabled when device is locked, and we shouldn't mess up the setting
                SettingsKt.setFloatingKeyboardEnabled(mThemeContext, enabled);
            if (enabled) FloatingKeyboardUtils.setFloating(mCurrentInputView);
            else FloatingKeyboardUtils.disableFloating(mCurrentInputView);
            setBackgroundGatheringIndicatorPosition();
        }

        @Override
        public boolean popDoubleTapShiftKeyTimer() {
            if (DEBUG_TIMER_ACTION) {
                Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
            }
            MainKeyboardView keyboardView = getMainKeyboardView();
            return keyboardView != null && keyboardView.popDoubleTapShiftKeyTimer();
        }

        // not a SwitchAction, but should only be called from a SwitchAction to avoid inconsistent state / actual layout
        private void setKeyboard(KeyboardElement keyboardElement, @NonNull KeyboardSwitchState toggleState) {
            // with a hardware keyboard we might get here without ever calling onCreateInputView, so don't crash
            if (mKeyboardView == null || mKeyboardLayoutSet == null) return;

            final Keyboard previous = mKeyboardView.getKeyboard();
            final boolean leavingSymbols = previous != null && isSymbolsElement(previous.mId.getElement());
            // The ABC key and a second Sym press both land here. A hidden toolbar must not
            // keep the window up with the letter keys. A reload restores symbols afterwards.
            final boolean dismissAlphabet = keyboardElement.isAlphabet()
                    && leavingSymbols
                    && !mReloadingMainKeyboard
                    && mLatinIME.shouldDismissAlphabetAfterSymbols();
            // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
            SettingsValues currentSettingsValues = Settings.getValues();
            setMainKeyboardFrame(currentSettingsValues, toggleState, keyboardElement.isAlphabet(), dismissAlphabet);
            // TODO: pass this object to setKeyboard instead of getting the current values.
            MainKeyboardView keyboardView = mKeyboardView;
            Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardElement);
            keyboardView.setKeyboard(newKeyboard);
            mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
            keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
            keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
            boolean subtypeChanged = (previous == null) || !newKeyboard.mId.getSubtype().equals(previous.mId.getSubtype());
            int languageOnSpacebarFormatType = LanguageOnSpacebarUtils.getLanguageOnSpacebarFormatType(newKeyboard.mId.getSubtype());
            boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
            keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);

            if (currentSettingsValues.needsToLookupSuggestions()
                && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
                EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
            }
            if (dismissAlphabet) {
                // hideWindow leaves the show request set, and the decor comes back as an empty bar.
                mLatinIME.hideWindow();
                mLatinIME.requestHideSelf(0);
            }
        }
    }

    private static boolean isSymbolsElement(@NonNull final KeyboardElement element) {
        return element == KeyboardElement.SYMBOLS || element == KeyboardElement.SYMBOLS_SHIFTED;
    }
}
