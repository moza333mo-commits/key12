package com.linguakey.keyboard.latin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.inputmethod.InputConnection;

import com.linguakey.keyboard.compat.PreferenceManagerCompat;
import com.linguakey.keyboard.latin.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core Real-Time Source-Strip to Chat-Box Translation Engine for LinguaKey v4.0.
 *
 * Architecture:
 * - While Live Translation Mode is ON:
 *   1. The user types their original text in the borderless Source Buffer strip above the number row.
 *   2. LiveTranslationEngine translates the source text in real time (using LruCache + Fast Low-Latency
 *      provisional translation + Gemini 2.5 Flash natural Syrian/Levantine contextual refinement).
 *   3. The translated text is atomically written/updated directly inside the target chat app's
 *      InputConnection (WhatsApp, HelloTalk, Telegram, Messenger, etc.) using tracked span replacement
 *      inside beginBatchEdit()/endBatchEdit() with strict stale-response rejection.
 */
public final class LiveTranslationEngine {
    private static final String TAG = "LiveTranslationEngine";

    public interface InputConnectionProvider {
        InputConnection get();
    }

    public interface StatusListener {
        void onStateChanged(
                boolean translationActive,
                String sourceLang,
                String targetLang,
                String dialectMode,
                boolean isTranslating,
                String sourceBufferText,
                String currentTranslatedText,
                List<String> alternatives
        );
    }

    public static final class TranslationOutcome {
        public final String primary;
        public final List<String> alternatives;

        public TranslationOutcome(final String primary, final List<String> alternatives) {
            this.primary = primary;
            this.alternatives = alternatives != null ? alternatives : Collections.<String>emptyList();
        }
    }

    public static final String[] SUPPORTED_LANG_CODES = new String[]{
            "ar", "en", "es", "ko", "tr", "fr", "de", "ru", "zh", "ja", "it", "pt"
    };

    public static final String[] SUPPORTED_LANG_NAMES_AR = new String[]{
            "العربية (سوري/فصحى)",
            "English (الإنجليزية)",
            "Español (الإسبانية)",
            "한국어 (الكورية)",
            "Türkçe (التركية)",
            "Français (الفرنسية)",
            "Deutsch (الألمانية)",
            "Русский (الروسية)",
            "中文 (الصينية)",
            "日本語 (اليابانية)",
            "Italiano (الإيطالية)",
            "Português (البرتغالية)"
    };

    private static final Map<String, String> SUPPORTED_LANG_BADGES = new HashMap<>();
    private static final Map<String, String> SUPPORTED_LANG_ENGLISH_NAMES = new HashMap<>();

    static {
        SUPPORTED_LANG_BADGES.put("ar", "AR");
        SUPPORTED_LANG_BADGES.put("en", "EN");
        SUPPORTED_LANG_BADGES.put("es", "ES");
        SUPPORTED_LANG_BADGES.put("ko", "KO");
        SUPPORTED_LANG_BADGES.put("tr", "TR");
        SUPPORTED_LANG_BADGES.put("fr", "FR");
        SUPPORTED_LANG_BADGES.put("de", "DE");
        SUPPORTED_LANG_BADGES.put("ru", "RU");
        SUPPORTED_LANG_BADGES.put("zh", "ZH");
        SUPPORTED_LANG_BADGES.put("ja", "JA");
        SUPPORTED_LANG_BADGES.put("it", "IT");
        SUPPORTED_LANG_BADGES.put("pt", "PT");

        SUPPORTED_LANG_ENGLISH_NAMES.put("ar", "Arabic (natural Syrian Levantine or Modern Standard Arabic)");
        SUPPORTED_LANG_ENGLISH_NAMES.put("en", "Natural Conversational English");
        SUPPORTED_LANG_ENGLISH_NAMES.put("es", "Natural Conversational Spanish");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ko", "Natural Polite Conversational Korean (해요체)");
        SUPPORTED_LANG_ENGLISH_NAMES.put("tr", "Natural Conversational Turkish");
        SUPPORTED_LANG_ENGLISH_NAMES.put("fr", "Natural Conversational French");
        SUPPORTED_LANG_ENGLISH_NAMES.put("de", "Natural Conversational German");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ru", "Natural Conversational Russian");
        SUPPORTED_LANG_ENGLISH_NAMES.put("zh", "Natural Simplified Chinese");
        SUPPORTED_LANG_ENGLISH_NAMES.put("ja", "Natural Polite Japanese");
        SUPPORTED_LANG_ENGLISH_NAMES.put("it", "Natural Conversational Italian");
        SUPPORTED_LANG_ENGLISH_NAMES.put("pt", "Natural Conversational Portuguese");
    }

    private static final LruCache<String, TranslationOutcome> sTranslationCache = new LruCache<>(250);

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorkerExecutor = Executors.newCachedThreadPool();
    private final AtomicInteger mRequestCounter = new AtomicInteger(0);

    private StatusListener mStatusListener;
    private Runnable mDebounceRunnable;

    private String mCurrentSourceText = "";
    private String mLastCommittedTranslation = "";
    private List<String> mCurrentAlternatives = Collections.emptyList();
    private boolean mIsCurrentlyTranslating = false;
    private volatile boolean mIsInternalReplacement = false;

    public LiveTranslationEngine(final Context context) {
        mContext = context;
    }

    public void setStatusListener(final StatusListener listener) {
        mStatusListener = listener;
        notifyStatus();
    }

    public boolean isAutoTranslateEnabled() {
        return LinguaKeySecurityManager.isAutoTranslateEnabled(mContext);
    }

    public boolean toggleAutoTranslate(final InputConnectionProvider provider) {
        boolean next = !isAutoTranslateEnabled();
        LinguaKeySecurityManager.setAutoTranslateEnabled(mContext, next);
        if (!next) {
            cancelPendingTranslation();
            mCurrentSourceText = "";
            mLastCommittedTranslation = "";
            mCurrentAlternatives = Collections.emptyList();
        } else if (provider != null) {
            // If user already started typing a few letters in the chat box before tapping the translate button,
            // seamlessly pull that uncommitted text into the source buffer and translate it immediately!
            InputConnection ic = provider.get();
            if (ic != null) {
                CharSequence beforeSeq = ic.getTextBeforeCursor(300, 0);
                CharSequence afterSeq = ic.getTextAfterCursor(100, 0);
                String before = beforeSeq != null ? beforeSeq.toString() : "";
                String after = afterSeq != null ? afterSeq.toString() : "";
                String existing = (before + after).trim();
                if (!existing.isEmpty() && existing.length() <= 200) {
                    mIsInternalReplacement = true;
                    try {
                        ic.beginBatchEdit();
                        ic.finishComposingText();
                        ic.deleteSurroundingText(before.length(), after.length());
                        ic.endBatchEdit();
                    } catch (Exception ignored) {
                    } finally {
                        mIsInternalReplacement = false;
                    }
                    mCurrentSourceText = existing;
                    mLastCommittedTranslation = "";
                    notifyStatus();
                    triggerLiveTranslationNow(existing, provider, false);
                    return true;
                }
            }
        }
        notifyStatus();
        return next;
    }

    public String[] swapDirection(final InputConnectionProvider provider) {
        String src = LinguaKeySecurityManager.getSourceLang(mContext);
        String tgt = LinguaKeySecurityManager.getTargetLang(mContext);
        LinguaKeySecurityManager.setSourceLang(mContext, tgt);
        LinguaKeySecurityManager.setTargetLang(mContext, src);
        notifyStatus();
        if (isAutoTranslateEnabled() && !mCurrentSourceText.trim().isEmpty()) {
            triggerLiveTranslationNow(mCurrentSourceText, provider, false);
        }
        return new String[]{tgt, src};
    }

    public String cycleDialectMode(final InputConnectionProvider provider) {
        String current = LinguaKeySecurityManager.getDialectMode(mContext);
        String next;
        if ("SYRIAN_NATURAL".equals(current)) {
            next = "FORMAL_PRO";
        } else if ("FORMAL_PRO".equals(current)) {
            next = "CASUAL_FRIENDLY";
        } else {
            next = "SYRIAN_NATURAL";
        }
        LinguaKeySecurityManager.setDialectMode(mContext, next);
        notifyStatus();
        if (isAutoTranslateEnabled() && !mCurrentSourceText.trim().isEmpty()) {
            triggerLiveTranslationNow(mCurrentSourceText, provider, false);
        }
        return next;
    }

    public String getDialectDisplayName(final String mode) {
        if ("FORMAL_PRO".equals(mode)) {
            return "رسمي احترافي 💼";
        } else if ("CASUAL_FRIENDLY".equals(mode)) {
            return "ودّي شبابي 💬";
        }
        return "شامي طبيعي 🇸🇾";
    }

    public String getBadge(final String code) {
        String b = SUPPORTED_LANG_BADGES.get(code);
        return b != null ? b : (code != null ? code.toUpperCase(Locale.US) : "EN");
    }

    public void cancelPendingTranslation() {
        if (mDebounceRunnable != null) {
            mMainHandler.removeCallbacks(mDebounceRunnable);
            mDebounceRunnable = null;
        }
        mRequestCounter.incrementAndGet();
        mIsCurrentlyTranslating = false;
    }

    /**
     * Resets the source buffer when moving to a new input field or after sending a message.
     */
    public void onStartNewField() {
        cancelPendingTranslation();
        mCurrentSourceText = "";
        mLastCommittedTranslation = "";
        mCurrentAlternatives = Collections.emptyList();
        notifyStatus();
    }

    /**
     * Detects when the user tapped the Send button inside the chat app (WhatsApp, HelloTalk, Telegram, etc.).
     * When the chat app sends the message and empties its EditText (newSelStart == 0 && newSelEnd == 0),
     * we automatically clear our source text strip so the user can immediately start typing their next message!
     */
    public void onExternalSelectionUpdated(
            final int newSelStart,
            final int newSelEnd,
            final InputConnectionProvider provider
    ) {
        if (mIsInternalReplacement) return;
        if (!isAutoTranslateEnabled()) return;
        if (newSelStart == 0 && newSelEnd == 0 && !mLastCommittedTranslation.isEmpty()) {
            InputConnection ic = provider != null ? provider.get() : null;
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(20, 0);
                CharSequence after = ic.getTextAfterCursor(20, 0);
                boolean isBeforeEmpty = (before == null || before.length() == 0);
                boolean isAfterEmpty = (after == null || after.length() == 0);
                if (isBeforeEmpty && isAfterEmpty) {
                    // The chat app just sent/cleared the message! Reset our source strip automatically.
                    cancelPendingTranslation();
                    mCurrentSourceText = "";
                    mLastCommittedTranslation = "";
                    mCurrentAlternatives = Collections.emptyList();
                    notifyStatus();
                }
            }
        }
    }

    /**
     * Called when the user presses Enter/Send on the keyboard itself while Live Translation is ON.
     * Finalizes the current translation in the chat box and clears the source buffer for the next message.
     */
    public void onSessionCommittedByEnter() {
        cancelPendingTranslation();
        mCurrentSourceText = "";
        mLastCommittedTranslation = "";
        mCurrentAlternatives = Collections.emptyList();
        notifyStatus();
    }

    /**
     * Clears both the source buffer strip and the corresponding translated text in the chat box.
     */
    public void clearSessionAndChatTranslation(final InputConnectionProvider provider) {
        cancelPendingTranslation();
        mCurrentSourceText = "";
        mCurrentAlternatives = Collections.emptyList();
        clearCommittedTranslationFromChatBox(provider);
        notifyStatus();
    }

    /**
     * Called whenever the user types, edits, or deletes characters inside the borderless source strip.
     */
    public void onSourceBufferChanged(
            final String newSourceText,
            final InputConnectionProvider provider,
            final boolean isBoundaryChar
    ) {
        mCurrentSourceText = newSourceText != null ? newSourceText : "";
        if (mDebounceRunnable != null) {
            mMainHandler.removeCallbacks(mDebounceRunnable);
            mDebounceRunnable = null;
        }

        final String trimmed = mCurrentSourceText.trim();
        if (trimmed.isEmpty()) {
            cancelPendingTranslation();
            mCurrentAlternatives = Collections.emptyList();
            clearCommittedTranslationFromChatBox(provider);
            notifyStatus();
            return;
        }

        final String srcLang = LinguaKeySecurityManager.getSourceLang(mContext);
        final String tgtLang = LinguaKeySecurityManager.getTargetLang(mContext);
        final String dialectMode = LinguaKeySecurityManager.getDialectMode(mContext);
        final String cacheKey = srcLang + "|" + tgtLang + "|" + dialectMode + "|false|" + trimmed;

        // 1. Instant 0ms LruCache hit if this exact phrase was already translated
        TranslationOutcome cached = sTranslationCache.get(cacheKey);
        if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
            mRequestCounter.incrementAndGet();
            mIsCurrentlyTranslating = false;
            mCurrentAlternatives = cached.alternatives;
            syncTranslationToChatBox(cached.primary, provider);
            notifyStatus();
            return;
        }

        // 2. Smart Adaptive Debounce:
        //    - If the user just typed a space or punctuation (؟ ? . ! ، ,), trigger quickly (120ms).
        //    - Otherwise wait 260ms so fast typing doesn't spam requests on every single letter.
        long delayMs = isBoundaryChar ? 120L : 260L;
        mDebounceRunnable = new Runnable() {
            @Override
            public void run() {
                triggerLiveTranslationNow(mCurrentSourceText, provider, false);
            }
        };
        mMainHandler.postDelayed(mDebounceRunnable, delayMs);
    }

    /**
     * Immediately translates the given source text and writes the result into the chat app's InputConnection.
     */
    public void triggerLiveTranslationNow(
            final String rawSourceText,
            final InputConnectionProvider provider,
            final boolean isPolishMode
    ) {
        final String candidate = rawSourceText != null ? rawSourceText.trim() : "";
        if (candidate.isEmpty()) {
            clearCommittedTranslationFromChatBox(provider);
            notifyStatus();
            return;
        }

        final String srcLang = isPolishMode ? "auto" : LinguaKeySecurityManager.getSourceLang(mContext);
        final String tgtLang = LinguaKeySecurityManager.getTargetLang(mContext);
        final String dialectMode = LinguaKeySecurityManager.getDialectMode(mContext);
        final String cacheKey = srcLang + "|" + tgtLang + "|" + dialectMode + "|" + isPolishMode + "|" + candidate;

        TranslationOutcome cached = sTranslationCache.get(cacheKey);
        if (cached != null && cached.primary != null && !cached.primary.isEmpty()) {
            mIsCurrentlyTranslating = false;
            mCurrentAlternatives = cached.alternatives;
            syncTranslationToChatBox(cached.primary, provider);
            notifyStatus();
            return;
        }

        final int currentReqId = mRequestCounter.incrementAndGet();
        mIsCurrentlyTranslating = true;
        notifyStatus();

        SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        final String apiKey = LinguaKeySecurityManager.readGeminiApiKey(mContext);
        final boolean useProxy = prefs.getBoolean(LinguaKeySecurityManager.PREF_USE_BACKEND_PROXY, false);
        String rawProxyUrl = prefs.getString(LinguaKeySecurityManager.PREF_BACKEND_PROXY_URL, "");
        final String proxyUrl = rawProxyUrl != null ? rawProxyUrl.trim() : "";
        final boolean hasGeminiConfigured = !apiKey.isEmpty() || (useProxy && !proxyUrl.isEmpty());

        String configuredModel = Settings.readGeminiModel(prefs);
        if (configuredModel == null || configuredModel.trim().isEmpty()) {
            configuredModel = "gemini-2.5-flash";
        }
        final String modelName = configuredModel;

        // Stage 1: Ultra-Fast Provisional Translation (~150ms) so the chat box updates almost instantaneously while typing!
        mWorkerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (currentReqId != mRequestCounter.get()) return;
                final TranslationOutcome fastOutcome = callFastFallbackTranslate(candidate, srcLang, tgtLang);
                if (fastOutcome != null && fastOutcome.primary != null && !fastOutcome.primary.trim().isEmpty()) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get() || !isAutoTranslateEnabled()) return;
                            String fastPrimary = fastOutcome.primary.trim();
                            if (!hasGeminiConfigured) {
                                sTranslationCache.put(cacheKey, fastOutcome);
                                mIsCurrentlyTranslating = false;
                            }
                            syncTranslationToChatBox(fastPrimary, provider);
                            notifyStatus();
                        }
                    });
                } else if (!hasGeminiConfigured) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get()) return;
                            mIsCurrentlyTranslating = false;
                            notifyStatus();
                        }
                    });
                }
            }
        });

        // Stage 2: If Gemini API is configured, run context-aware Syrian/Levantine translation & alternatives refinement
        if (hasGeminiConfigured) {
            mWorkerExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (currentReqId != mRequestCounter.get()) return;
                    final TranslationOutcome geminiOutcome = callGeminiStructuredApi(
                            candidate,
                            srcLang,
                            tgtLang,
                            dialectMode,
                            isPolishMode,
                            apiKey,
                            useProxy,
                            proxyUrl,
                            modelName
                    );
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentReqId != mRequestCounter.get() || !isAutoTranslateEnabled()) return;
                            mIsCurrentlyTranslating = false;
                            if (geminiOutcome != null && geminiOutcome.primary != null && !geminiOutcome.primary.trim().isEmpty()) {
                                sTranslationCache.put(cacheKey, geminiOutcome);
                                mCurrentAlternatives = geminiOutcome.alternatives;
                                syncTranslationToChatBox(geminiOutcome.primary.trim(), provider);
                            }
                            notifyStatus();
                        }
                    });
                }
            });
        }
    }

    /**
     * Atomically writes or replaces the translated text inside the target chat app's InputConnection.
     * Uses tracked span replacement inside beginBatchEdit()/endBatchEdit() so:
     * 1. There is zero text duplication.
     * 2. WhatsApp / HelloTalk / Telegram enable their Send button immediately (committed text).
     * 3. Rapid edits replace only the translated segment cleanly without flicker.
     */
    private synchronized void syncTranslationToChatBox(
            final String newTranslation,
            final InputConnectionProvider provider
    ) {
        if (provider == null || newTranslation == null) return;
        final String cleanTranslation = newTranslation.trim();
        if (cleanTranslation.isEmpty()) return;
        if (cleanTranslation.equals(mLastCommittedTranslation)) return;

        final InputConnection ic = provider.get();
        if (ic == null) return;

        mIsInternalReplacement = true;
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();

            CharSequence beforeSeq = ic.getTextBeforeCursor(1000, 0);
            CharSequence afterSeq = ic.getTextAfterCursor(500, 0);
            String before = beforeSeq != null ? beforeSeq.toString() : "";
            String after = afterSeq != null ? afterSeq.toString() : "";

            if (!mLastCommittedTranslation.isEmpty() && before.endsWith(mLastCommittedTranslation)) {
                // Exact match at the cursor: delete the previous translation and commit the updated one
                ic.deleteSurroundingText(mLastCommittedTranslation.length(), 0);
                ic.commitText(cleanTranslation, 1);
            } else if (!mLastCommittedTranslation.isEmpty() && before.contains(mLastCommittedTranslation)) {
                int idx = before.lastIndexOf(mLastCommittedTranslation);
                int deleteLen = before.length() - idx;
                ic.deleteSurroundingText(deleteLen, 0);
                ic.commitText(cleanTranslation, 1);
            } else if (!mLastCommittedTranslation.isEmpty()) {
                // Fallback if cursor moved inside the chat box: replace the entire chat field content
                ic.deleteSurroundingText(before.length(), after.length());
                ic.commitText(cleanTranslation, 1);
            } else {
                // First translation commit of this session
                ic.commitText(cleanTranslation, 1);
            }

            ic.endBatchEdit();
            mLastCommittedTranslation = cleanTranslation;
        } catch (Exception e) {
            Log.e(TAG, "Error syncing translation to chat box", e);
        } finally {
            mIsInternalReplacement = false;
        }
    }

    /**
     * Removes the previously committed translation from the chat box when the user deletes all source text.
     */
    private synchronized void clearCommittedTranslationFromChatBox(final InputConnectionProvider provider) {
        if (mLastCommittedTranslation.isEmpty() || provider == null) {
            mLastCommittedTranslation = "";
            return;
        }
        final InputConnection ic = provider.get();
        if (ic == null) {
            mLastCommittedTranslation = "";
            return;
        }

        mIsInternalReplacement = true;
        try {
            ic.beginBatchEdit();
            ic.finishComposingText();

            CharSequence beforeSeq = ic.getTextBeforeCursor(1000, 0);
            CharSequence afterSeq = ic.getTextAfterCursor(500, 0);
            String before = beforeSeq != null ? beforeSeq.toString() : "";
            String after = afterSeq != null ? afterSeq.toString() : "";

            if (before.endsWith(mLastCommittedTranslation)) {
                ic.deleteSurroundingText(mLastCommittedTranslation.length(), 0);
            } else if (before.contains(mLastCommittedTranslation)) {
                int idx = before.lastIndexOf(mLastCommittedTranslation);
                ic.deleteSurroundingText(before.length() - idx, 0);
            } else {
                ic.deleteSurroundingText(before.length(), after.length());
            }

            ic.endBatchEdit();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing translation from chat box", e);
        } finally {
            mLastCommittedTranslation = "";
            mIsInternalReplacement = false;
        }
    }

    /**
     * Allows selecting an alternative phrasing from the Tools Drawer and immediately updating the chat box.
     */
    public void selectAlternativeInChatBox(final int index, final InputConnectionProvider provider) {
        if (index < 0 || index >= mCurrentAlternatives.size()) return;
        String chosen = mCurrentAlternatives.get(index).trim();
        if (chosen.isEmpty()) return;

        String previousPrimary = mLastCommittedTranslation;
        List<String> updatedAlts = new ArrayList<>(mCurrentAlternatives);
        if (!previousPrimary.isEmpty()) {
            updatedAlts.set(index, previousPrimary);
        }
        mCurrentAlternatives = updatedAlts;
        syncTranslationToChatBox(chosen, provider);
        notifyStatus();
    }

    private TranslationOutcome callGeminiStructuredApi(
            final String text,
            final String sourceLang,
            final String targetLang,
            final String dialectMode,
            final boolean isPolishMode,
            final String apiKey,
            final boolean useProxy,
            final String proxyUrl,
            final String modelName
    ) {
        HttpURLConnection conn = null;
        try {
            String endpoint;
            if (useProxy && !proxyUrl.isEmpty()) {
                endpoint = proxyUrl;
            } else {
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName.trim() + ":generateContent?key=" + apiKey;
            }

            String targetDesc = SUPPORTED_LANG_ENGLISH_NAMES.get(targetLang);
            if (targetDesc == null) targetDesc = targetLang;
            String sourceDesc = "auto".equals(sourceLang)
                    ? "the source language"
                    : (SUPPORTED_LANG_ENGLISH_NAMES.get(sourceLang) != null ? SUPPORTED_LANG_ENGLISH_NAMES.get(sourceLang) : sourceLang);

            String toneInstruction;
            if ("SYRIAN_NATURAL".equals(dialectMode)) {
                toneInstruction = "Understand Syrian/Levantine Arabic idioms deeply (e.g., شو الأخبار، كيفك، تكرم عينك، على راسي، يلا، ماشي الحال، دغري، هلق، بدي، شلونك، يسعد أوقاتك، يعطيك العافية) and translate the true intended meaning and warm conversational tone, NEVER literal word-for-word translation.";
            } else if ("FORMAL_PRO".equals(dialectMode)) {
                toneInstruction = "Use a polite, professional, articulate tone suitable for business or formal messaging while keeping the exact meaning.";
            } else {
                toneInstruction = "Use a friendly, natural, native casual messaging tone suitable for WhatsApp and HelloTalk.";
            }

            String taskInstruction = isPolishMode
                    ? ("Polish, fix grammar/spelling, and make the following text sound 100% natural in " + targetDesc + ".")
                    : ("Translate the following user message from " + sourceDesc + " into " + targetDesc + ".");

            String prompt = "You are LinguaKey, an ultra-fast smart keyboard translation engine.\n"
                    + taskInstruction + "\n"
                    + toneInstruction + "\n"
                    + "Preserve punctuation, emojis, and speaker emotion.\n"
                    + "Return ONLY a valid JSON object with this exact schema (no markdown fences, no extra text):\n"
                    + "{\"primary\":\"best natural translation\",\"alt1\":\"alternative phrasing 1\",\"alt2\":\"alternative phrasing 2\"}\n\n"
                    + "User text:\n"
                    + text;

            JSONObject part = new JSONObject();
            part.put("text", prompt);
            JSONArray parts = new JSONArray();
            parts.put(part);
            JSONObject content = new JSONObject();
            content.put("parts", parts);
            JSONArray contents = new JSONArray();
            contents.put(content);

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.2);
            genConfig.put("maxOutputTokens", 220);
            genConfig.put("responseMimeType", "application/json");

            JSONObject payload = new JSONObject();
            payload.put("contents", contents);
            payload.put("generationConfig", genConfig);

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);
            conn.setDoOutput(true);

            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(payload.toString());
            writer.flush();
            writer.close();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray candidates = root.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    String textOut = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .trim();
                    return parseGeminiJsonResponse(textOut);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Gemini translation error", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private TranslationOutcome parseGeminiJsonResponse(final String rawText) {
        String cleaned = rawText.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7).trim();
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3).trim();
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();

        try {
            JSONObject json = new JSONObject(cleaned);
            String primary = json.optString("primary", "").trim();
            String alt1 = json.optString("alt1", "").trim();
            String alt2 = json.optString("alt2", "").trim();
            if (!primary.isEmpty()) {
                List<String> alts = new ArrayList<>();
                if (!alt1.isEmpty() && !alt1.equals(primary)) {
                    alts.add(alt1);
                }
                if (!alt2.isEmpty() && !alt2.equals(primary) && !alt2.equals(alt1)) {
                    alts.add(alt2);
                }
                return new TranslationOutcome(primary, alts);
            }
        } catch (Exception ignored) {
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            if (!cleaned.isEmpty()) {
                return new TranslationOutcome(cleaned, Collections.<String>emptyList());
            }
        }
        return null;
    }

    private TranslationOutcome callFastFallbackTranslate(
            final String text,
            final String sourceLang,
            final String targetLang
    ) {
        HttpURLConnection conn = null;
        try {
            String sl = (sourceLang == null || sourceLang.trim().isEmpty()) ? "auto" : sourceLang;
            String encoded = URLEncoder.encode(text, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl="
                    + sl + "&tl=" + targetLang + "&dt=t&q=" + encoded;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();

                JSONArray root = new JSONArray(body.toString());
                JSONArray sentences = root.getJSONArray(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sentences.length(); i++) {
                    JSONArray seg = sentences.getJSONArray(i);
                    if (seg.length() > 0 && !seg.isNull(0)) {
                        sb.append(seg.getString(0));
                    }
                }
                String result = sb.toString().trim();
                if (!result.isEmpty()) {
                    return new TranslationOutcome(result, Collections.<String>emptyList());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback translation error", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public void notifyStatus() {
        final StatusListener listener = mStatusListener;
        if (listener == null) return;
        final boolean active = LinguaKeySecurityManager.isAutoTranslateEnabled(mContext);
        final String src = LinguaKeySecurityManager.getSourceLang(mContext);
        final String tgt = LinguaKeySecurityManager.getTargetLang(mContext);
        final String dialect = LinguaKeySecurityManager.getDialectMode(mContext);
        final String sourceBuf = mCurrentSourceText;
        final String committedTrans = mLastCommittedTranslation;
        final List<String> alts = mCurrentAlternatives;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onStateChanged(
                        active,
                        src,
                        tgt,
                        dialect,
                        mIsCurrentlyTranslating,
                        sourceBuf,
                        committedTrans,
                        alts
                );
            }
        });
    }
}
