package com.linguakey.keyboard.latin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.latin.common.Constants;
import com.linguakey.keyboard.latin.utils.DialogUtils;

import java.util.List;

/**
 * Manages the LinguaKey v4.0 Ultra-Clean Top Strip:
 * - Left: Gear/Tools Icon (toggles collapsible tools drawer)
 * - Center: Borderless Source Buffer Strip (displays native source text directly on keyboard background
 *   with adaptive font sizing and smooth multi-line expansion when Live Translation is ON; completely
 *   invisible and empty when OFF)
 * - Right: Translation Toggle Button (Gray when OFF, Vibrant Blue #0D6EFD when ON)
 */
public final class LinguaKeySmartBarController implements LiveTranslationEngine.StatusListener {
    private final Context mContext;
    private final View mInputView;
    private final LiveTranslationEngine mLiveEngine;
    private final LiveTranslationEngine.InputConnectionProvider mInputConnectionProvider;

    private final LinearLayout mLiveBarContainer;
    private final ImageView mToolsMenuBtn;
    private final EditText mSourceBufferInput;
    private final ProgressBar mLiveProgress;
    private final ImageView mTranslateToggleBtn;

    private final LinearLayout mToolsDrawer;
    private final TextView mSourceLangChip;
    private final TextView mSwapDirectionBtn;
    private final TextView mTargetLangChip;
    private final TextView mDialectChip;
    private final TextView mPolishChip;
    private final TextView mUndoChip;

    private final HorizontalScrollView mAlternativesScroll;
    private final LinearLayout mAlternativesContainer;

    private boolean mIsToolsDrawerOpen = false;
    private boolean mUpdatingBufferProgrammatically = false;

    public LinguaKeySmartBarController(
            final Context context,
            final View inputView,
            final LiveTranslationEngine liveEngine,
            final LiveTranslationEngine.InputConnectionProvider inputConnectionProvider
    ) {
        mContext = context;
        mInputView = inputView;
        mLiveEngine = liveEngine;
        mInputConnectionProvider = inputConnectionProvider;

        mLiveBarContainer = inputView.findViewById(R.id.linguakey_live_bar);
        mToolsMenuBtn = inputView.findViewById(R.id.lk_tools_menu_btn);
        mSourceBufferInput = inputView.findViewById(R.id.lk_source_buffer_input);
        mLiveProgress = inputView.findViewById(R.id.lk_live_progress);
        mTranslateToggleBtn = inputView.findViewById(R.id.lk_translate_toggle_btn);

        mToolsDrawer = inputView.findViewById(R.id.lk_tools_drawer);
        mSourceLangChip = inputView.findViewById(R.id.lk_source_lang_chip);
        mSwapDirectionBtn = inputView.findViewById(R.id.lk_swap_direction_btn);
        mTargetLangChip = inputView.findViewById(R.id.lk_target_lang_chip);
        mDialectChip = inputView.findViewById(R.id.lk_dialect_chip);
        mPolishChip = inputView.findViewById(R.id.lk_polish_chip);
        mUndoChip = inputView.findViewById(R.id.lk_undo_chip);

        mAlternativesScroll = inputView.findViewById(R.id.lk_alternatives_scroll);
        mAlternativesContainer = inputView.findViewById(R.id.lk_alternatives_container);

        configureSourceBufferView();
        setupListeners();
        applyGboardTheme();
        mLiveEngine.setStatusListener(this);
    }

    private void configureSourceBufferView() {
        if (mSourceBufferInput == null) return;
        mSourceBufferInput.setBackground(null);
        mSourceBufferInput.setHint("");
        mSourceBufferInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        mSourceBufferInput.setShowSoftInputOnFocus(false);
        mSourceBufferInput.setFocusable(true);
        mSourceBufferInput.setFocusableInTouchMode(true);
        mSourceBufferInput.setCursorVisible(true);
        mSourceBufferInput.setVerticalScrollBarEnabled(false);
        mSourceBufferInput.setMovementMethod(ScrollingMovementMethod.getInstance());
    }

    private void setupListeners() {
        if (mToolsMenuBtn != null) {
            mToolsMenuBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    mIsToolsDrawerOpen = !mIsToolsDrawerOpen;
                    if (mToolsDrawer != null) {
                        mToolsDrawer.setVisibility(mIsToolsDrawerOpen ? View.VISIBLE : View.GONE);
                    }
                    styleToolsButton();
                }
            });
        }

        if (mTranslateToggleBtn != null) {
            mTranslateToggleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    boolean enabled = mLiveEngine.toggleAutoTranslate(mInputConnectionProvider);
                    styleTranslateToggleButton(enabled);
                    updateSourceBufferVisibility(enabled);
                }
            });
            mTranslateToggleBtn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    vibrateTick();
                    showLanguagePickerDialog(false);
                    return true;
                }
            });
        }

        if (mSourceLangChip != null) {
            mSourceLangChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    showLanguagePickerDialog(true);
                }
            });
        }

        if (mTargetLangChip != null) {
            mTargetLangChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    showLanguagePickerDialog(false);
                }
            });
        }

        if (mSwapDirectionBtn != null) {
            mSwapDirectionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    String[] pair = mLiveEngine.swapDirection(mInputConnectionProvider);
                    Toast.makeText(
                            mContext,
                            mLiveEngine.getBadge(pair[0]) + " ➔ " + mLiveEngine.getBadge(pair[1]),
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        if (mDialectChip != null) {
            mDialectChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    String mode = mLiveEngine.cycleDialectMode(mInputConnectionProvider);
                    Toast.makeText(mContext, "الأسلوب: " + mLiveEngine.getDialectDisplayName(mode), Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (mPolishChip != null) {
            mPolishChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    String currentSrc = getSourceText();
                    if (!currentSrc.trim().isEmpty()) {
                        mLiveEngine.triggerLiveTranslationNow(currentSrc, mInputConnectionProvider, true);
                    }
                }
            });
        }

        if (mUndoChip != null) {
            mUndoChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    clearSourceBufferAndChatTranslation();
                }
            });
        }
    }

    public boolean isLiveTranslationActive() {
        return mLiveEngine.isAutoTranslateEnabled();
    }

    public boolean hasSourceText() {
        return mSourceBufferInput != null
                && mSourceBufferInput.getText() != null
                && mSourceBufferInput.getText().length() > 0;
    }

    public String getSourceText() {
        if (mSourceBufferInput == null || mSourceBufferInput.getText() == null) return "";
        return mSourceBufferInput.getText().toString();
    }

    public void clearSourceBufferAndChatTranslation() {
        setSourceBufferTextInternal("");
        mLiveEngine.clearSessionAndChatTranslation(mInputConnectionProvider);
    }

    /**
     * Intercepts keystrokes from LatinIME when Live Translation Mode is ON.
     * Routes characters and deletions into the borderless source strip (0ms lag)
     * while LiveTranslationEngine updates the translated text inside the chat app's InputConnection.
     */
    public boolean onInterceptCodeInput(final int codePoint, final boolean isKeyRepeat) {
        if (!isLiveTranslationActive() || mSourceBufferInput == null) {
            return false;
        }

        // 1. Handle Backspace / Delete (-5)
        if (codePoint == Constants.CODE_DELETE) {
            Editable editable = mSourceBufferInput.getText();
            if (editable == null || editable.length() == 0) {
                // Source strip is already empty; let backspace pass through to the chat box if needed
                return false;
            }
            int selStart = mSourceBufferInput.getSelectionStart();
            int selEnd = mSourceBufferInput.getSelectionEnd();
            if (selStart < 0 || selEnd < 0) {
                selStart = editable.length();
                selEnd = editable.length();
            }
            int min = Math.min(selStart, selEnd);
            int max = Math.max(selStart, selEnd);

            mUpdatingBufferProgrammatically = true;
            try {
                if (min != max) {
                    editable.delete(min, max);
                    mSourceBufferInput.setSelection(min);
                } else if (min > 0) {
                    int charCount = Character.charCount(Character.codePointBefore(editable, min));
                    int delStart = Math.max(0, min - charCount);
                    editable.delete(delStart, min);
                    mSourceBufferInput.setSelection(delStart);
                } else {
                    int lastCp = Character.codePointBefore(editable, editable.length());
                    int charCount = Character.charCount(lastCp);
                    editable.delete(Math.max(0, editable.length() - charCount), editable.length());
                    mSourceBufferInput.setSelection(editable.length());
                }
            } finally {
                mUpdatingBufferProgrammatically = false;
            }

            String updated = editable.toString();
            updateSourceBufferTypography(updated);
            mLiveEngine.onSourceBufferChanged(updated, mInputConnectionProvider, false);
            return true;
        }

        // 2. Handle Enter / Action Key ('\n' / CODE_ENTER)
        if (codePoint == Constants.CODE_ENTER) {
            if (hasSourceText()) {
                // Finalize current session and clear source buffer so the user can send or start next line
                setSourceBufferTextInternal("");
                mLiveEngine.onSessionCommittedByEnter();
            }
            return false;
        }

        // 3. Let functional/modifier keys (Shift, Symbol switch, Language switch) pass through to KeyboardSwitcher
        if (codePoint <= 0) {
            return false;
        }

        // 4. Append/Insert printable character into the borderless Source Buffer strip
        String textToInsert = new String(Character.toChars(codePoint));
        insertIntoSourceBuffer(textToInsert, isBoundaryCodePoint(codePoint));
        return true;
    }

    /**
     * Intercepts multi-character text or emoji input when Live Translation Mode is ON.
     */
    public boolean onInterceptTextInput(final String rawText) {
        if (!isLiveTranslationActive() || mSourceBufferInput == null || rawText == null || rawText.isEmpty()) {
            return false;
        }
        insertIntoSourceBuffer(rawText, true);
        return true;
    }

    /**
     * Moves the cursor inside the borderless source strip when the user slides along the Spacebar.
     */
    public boolean onMoveCursorInSourceBuffer(final int steps) {
        if (!isLiveTranslationActive() || !hasSourceText() || mSourceBufferInput == null) {
            return false;
        }
        int len = mSourceBufferInput.getText().length();
        int current = mSourceBufferInput.getSelectionEnd();
        if (current < 0) current = len;
        int next = Math.max(0, Math.min(len, current + steps));
        mSourceBufferInput.setSelection(next);
        return true;
    }

    /**
     * Supports swipe-to-delete on the Backspace key inside the borderless source strip.
     */
    public boolean onSwipeDeleteInSourceBuffer(final int steps) {
        if (!isLiveTranslationActive() || !hasSourceText() || mSourceBufferInput == null) {
            return false;
        }
        if (steps < 0) {
            int count = Math.min(Math.abs(steps), 8);
            for (int i = 0; i < count; i++) {
                if (!hasSourceText()) break;
                onInterceptCodeInput(Constants.CODE_DELETE, true);
            }
        }
        return true;
    }

    private void insertIntoSourceBuffer(final String textToInsert, final boolean isBoundary) {
        if (mSourceBufferInput == null) return;
        if (mSourceBufferInput.getVisibility() != View.VISIBLE) {
            mSourceBufferInput.setVisibility(View.VISIBLE);
        }
        mSourceBufferInput.requestFocus();

        Editable editable = mSourceBufferInput.getText();
        int selStart = mSourceBufferInput.getSelectionStart();
        int selEnd = mSourceBufferInput.getSelectionEnd();
        if (selStart < 0 || selEnd < 0) {
            selStart = editable.length();
            selEnd = editable.length();
        }
        int min = Math.min(selStart, selEnd);
        int max = Math.max(selStart, selEnd);

        mUpdatingBufferProgrammatically = true;
        try {
            editable.replace(min, max, textToInsert);
            int newCursor = min + textToInsert.length();
            if (newCursor <= editable.length()) {
                mSourceBufferInput.setSelection(newCursor);
            }
        } finally {
            mUpdatingBufferProgrammatically = false;
        }

        String updated = editable.toString();
        updateSourceBufferTypography(updated);
        mLiveEngine.onSourceBufferChanged(updated, mInputConnectionProvider, isBoundary);
    }

    private boolean isBoundaryCodePoint(final int codePoint) {
        return codePoint == ' '
                || codePoint == '?'
                || codePoint == '؟'
                || codePoint == '.'
                || codePoint == '!'
                || codePoint == ','
                || codePoint == '،'
                || codePoint == ';';
    }

    private void setSourceBufferTextInternal(final String text) {
        if (mSourceBufferInput == null) return;
        String safe = text != null ? text : "";
        String current = getSourceText();
        if (safe.equals(current)) {
            updateSourceBufferTypography(safe);
            return;
        }
        mUpdatingBufferProgrammatically = true;
        try {
            mSourceBufferInput.setText(safe);
            mSourceBufferInput.setSelection(safe.length());
        } finally {
            mUpdatingBufferProgrammatically = false;
        }
        updateSourceBufferTypography(safe);
    }

    /**
     * Smart Adaptive Typography for Long Text:
     * - Starts compact & crisp (13.5sp, 1 line) for short phrases.
     * - Scales down smoothly (12.0sp -> 10.8sp) and expands up to 3 lines for longer paragraphs
     *   so text is never cut off or lost and the keyboard height stays stable.
     */
    private void updateSourceBufferTypography(final String text) {
        if (mSourceBufferInput == null) return;
        int len = text != null ? text.length() : 0;
        float targetSp;
        int targetMaxLines;
        if (len <= 38) {
            targetSp = 13.5f;
            targetMaxLines = 1;
        } else if (len <= 78) {
            targetSp = 12.0f;
            targetMaxLines = 2;
        } else {
            targetSp = 10.8f;
            targetMaxLines = 3;
        }
        mSourceBufferInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp);
        mSourceBufferInput.setMaxLines(targetMaxLines);
    }

    private void updateSourceBufferVisibility(final boolean translationActive) {
        if (mSourceBufferInput == null) return;
        if (translationActive) {
            mSourceBufferInput.setVisibility(View.VISIBLE);
            mSourceBufferInput.setHint("");
            mSourceBufferInput.requestFocus();
            mSourceBufferInput.setCursorVisible(true);
        } else {
            setSourceBufferTextInternal("");
            mSourceBufferInput.setHint("");
            mSourceBufferInput.clearFocus();
            mSourceBufferInput.setVisibility(View.INVISIBLE);
        }
    }

    public void closeToolsDrawer() {
        if (mIsToolsDrawerOpen) {
            mIsToolsDrawerOpen = false;
            if (mToolsDrawer != null) {
                mToolsDrawer.setVisibility(View.GONE);
            }
            styleToolsButton();
        }
    }

    @Override
    public void onStateChanged(
            final boolean translationActive,
            final String sourceLang,
            final String targetLang,
            final String dialectMode,
            final boolean isTranslating,
            final String sourceBufferText,
            final String currentTranslatedText,
            final List<String> alternatives
    ) {
        styleTranslateToggleButton(translationActive);

        if (mSourceBufferInput != null) {
            if (!translationActive) {
                if (mSourceBufferInput.getVisibility() != View.INVISIBLE || hasSourceText()) {
                    updateSourceBufferVisibility(false);
                }
            } else {
                if (mSourceBufferInput.getVisibility() != View.VISIBLE) {
                    updateSourceBufferVisibility(true);
                }
                if (!mUpdatingBufferProgrammatically && sourceBufferText != null && !sourceBufferText.equals(getSourceText())) {
                    setSourceBufferTextInternal(sourceBufferText);
                }
            }
        }

        if (mLiveProgress != null) {
            mLiveProgress.setVisibility((translationActive && isTranslating) ? View.VISIBLE : View.GONE);
        }

        if (mSourceLangChip != null) mSourceLangChip.setText(mLiveEngine.getBadge(sourceLang));
        if (mTargetLangChip != null) mTargetLangChip.setText(mLiveEngine.getBadge(targetLang));
        if (mDialectChip != null) mDialectChip.setText(mLiveEngine.getDialectDisplayName(dialectMode));

        if (mUndoChip != null) {
            boolean hasAny = translationActive && sourceBufferText != null && !sourceBufferText.trim().isEmpty();
            mUndoChip.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        }

        renderAlternatives(translationActive ? alternatives : null);
    }

    private void renderAlternatives(final List<String> alternatives) {
        if (mAlternativesContainer == null) return;
        mAlternativesContainer.removeAllViews();

        if (alternatives == null || alternatives.isEmpty()) {
            if (mAlternativesScroll != null) {
                mAlternativesScroll.setVisibility(View.GONE);
            }
            return;
        }

        if (mAlternativesScroll != null) {
            mAlternativesScroll.setVisibility(View.VISIBLE);
        }

        TextView labelView = new TextView(mContext);
        labelView.setText("بدائل:");
        labelView.setTextSize(10.5f);
        labelView.setTextColor(0xFF8AB4F8);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        labelView.setPadding((int) dpToPx(4f), (int) dpToPx(2f), (int) dpToPx(6f), (int) dpToPx(2f));
        mAlternativesContainer.addView(labelView);

        for (int i = 0; i < alternatives.size(); i++) {
            final int idx = i;
            String altText = alternatives.get(i);
            TextView altChip = new TextView(mContext);
            altChip.setText(altText);
            altChip.setTextSize(11f);
            altChip.setTextColor(0xFFE8EAED);
            altChip.setSingleLine(true);
            altChip.setGravity(Gravity.CENTER_VERTICAL);
            altChip.setPadding((int) dpToPx(10f), (int) dpToPx(3f), (int) dpToPx(10f), (int) dpToPx(3f));

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dpToPx(12f));
            bg.setColor(0xFF3C4043);
            bg.setStroke((int) dpToPx(1f), 0xFF5F6368);
            altChip.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd((int) dpToPx(6f));
            altChip.setLayoutParams(lp);

            altChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    mLiveEngine.selectAlternativeInChatBox(idx, mInputConnectionProvider);
                }
            });
            mAlternativesContainer.addView(altChip);
        }
    }

    private void showLanguagePickerDialog(final boolean isSource) {
        final String[] codes = LiveTranslationEngine.SUPPORTED_LANG_CODES;
        final String[] names = LiveTranslationEngine.SUPPORTED_LANG_NAMES_AR;
        String currentCode = isSource
                ? LinguaKeySecurityManager.getSourceLang(mContext)
                : LinguaKeySecurityManager.getTargetLang(mContext);
        int checkedIdx = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentCode)) {
                checkedIdx = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(DialogUtils.getPlatformDialogThemeContext(mContext));
        builder.setTitle(isSource ? "اختر لغة الكتابة (المصدر)" : "اختر لغة الترجمة (الهدف)");
        builder.setSingleChoiceItems(names, checkedIdx, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String selectedCode = codes[which];
                if (isSource) {
                    LinguaKeySecurityManager.setSourceLang(mContext, selectedCode);
                } else {
                    LinguaKeySecurityManager.setTargetLang(mContext, selectedCode);
                }
                mLiveEngine.notifyStatus();
                if (isLiveTranslationActive() && hasSourceText()) {
                    mLiveEngine.triggerLiveTranslationNow(getSourceText(), mInputConnectionProvider, false);
                }
                dialog.dismiss();
            }
        });

        AlertDialog dialog = builder.create();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.token = mInputView.getWindowToken();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        dialog.show();
    }

    private void styleToolsButton() {
        if (mToolsMenuBtn == null) return;
        if (mIsToolsDrawerOpen) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dpToPx(9f));
            bg.setColor(0xFF3C4043);
            mToolsMenuBtn.setBackground(bg);
            mToolsMenuBtn.setColorFilter(0xFF8AB4F8, PorterDuff.Mode.SRC_IN);
        } else {
            mToolsMenuBtn.setBackground(null);
            mToolsMenuBtn.setColorFilter(0xFFE8EAED, PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * Styles the right-side Translation Toggle Button matching the user's reference design:
     * - Active (ON): Vibrant Blue (#0D6EFD) rounded square with crisp white icon (#FFFFFF).
     * - Inactive (OFF): Subtle Dark Gray (#3C4043) rounded square with light gray icon (#E8EAED).
     */
    private void styleTranslateToggleButton(final boolean active) {
        if (mTranslateToggleBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(9f));
        if (active) {
            bg.setColor(0xFF0D6EFD);
            mTranslateToggleBtn.setBackground(bg);
            mTranslateToggleBtn.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        } else {
            bg.setColor(0xFF3C4043);
            mTranslateToggleBtn.setBackground(bg);
            mTranslateToggleBtn.setColorFilter(0xFFE8EAED, PorterDuff.Mode.SRC_IN);
        }
    }

    public void applyGboardTheme() {
        if (mLiveBarContainer != null) {
            mLiveBarContainer.setBackgroundColor(0xFF1F1F1F);
        }
        if (mSourceBufferInput != null) {
            mSourceBufferInput.setBackground(null);
            mSourceBufferInput.setTextColor(0xFFFFFFFF);
            mSourceBufferInput.setHint("");
        }
        styleToolsButton();
        styleTranslateToggleButton(isLiveTranslationActive());
        updateSourceBufferVisibility(isLiveTranslationActive());

        stylePill(mSourceLangChip, 0xFF2D3034, 0xFF3C4043, 0xFFE8EAED);
        stylePill(mSwapDirectionBtn, Color.TRANSPARENT, Color.TRANSPARENT, 0xFF8AB4F8);
        stylePill(mTargetLangChip, 0xFF2D3034, 0xFF8AB4F8, 0xFF8AB4F8);
        stylePill(mDialectChip, 0xFF3C4043, 0xFF5F6368, 0xFFE8EAED);
        stylePill(mPolishChip, 0xFF174EA6, 0xFF8AB4F8, 0xFFD2E3FC);
        stylePill(mUndoChip, 0xFF3C4043, 0xFFF28B82, 0xFFF28B82);

        if (mToolsDrawer != null) {
            GradientDrawable drawerBg = new GradientDrawable();
            drawerBg.setShape(GradientDrawable.RECTANGLE);
            drawerBg.setCornerRadius(dpToPx(12f));
            drawerBg.setColor(0xFF28292C);
            mToolsDrawer.setBackground(drawerBg);
        }
    }

    public void applyPremiumMinimalTheme() {
        applyGboardTheme();
    }

    private void stylePill(final TextView view, final int fillColor, final int strokeColor, final int textColor) {
        if (view == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(fillColor);
        if (strokeColor != Color.TRANSPARENT) {
            bg.setStroke((int) dpToPx(1f), strokeColor);
        }
        view.setBackground(bg);
        view.setTextColor(textColor);
    }

    private void vibrateTick() {
        try {
            Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                } else {
                    vibrator.vibrate(12);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private float dpToPx(final float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                mContext.getResources().getDisplayMetrics()
        );
    }
}
