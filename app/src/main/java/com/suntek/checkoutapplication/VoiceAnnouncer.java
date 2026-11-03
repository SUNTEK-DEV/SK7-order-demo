package com.suntek.checkoutapplication;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Locale;

/**
 * 语音播报封装。三条通路，按优先级自动选择：
 *
 *  1) 【讯飞在线语音合成】{@link XunfeiTtsEngine} —— 默认通路，零模型体积、中英文都念得出，
 *     适配本机（RK 板没有系统 TTS 引擎）这种场景；
 *  2) 【系统 TextToSpeech】—— 设备装了 TTS 引擎时作为备用；
 *  3) 都不行时 {@link #isAvailable()} 返回 false，调用方退化为 Toast + 状态栏文字。
 *
 * 说明：早期版本只用了系统 TTS，在这台板子上日志一直是
 * `TTS 引擎初始化失败(status=-1)` / `TTS 缺少中文语音`，播报完全不出声，
 * 所以才补了在线合成这一层。
 */
public class VoiceAnnouncer {

    private static final String TAG = "VoiceAnnouncer";

    private final Context context;
    private final XunfeiTtsEngine xunfeiTts;

    private TextToSpeech tts;
    private boolean sysReady = false;
    private Locale currentLocale = Locale.SIMPLIFIED_CHINESE;
    private String lastDiag = "未初始化";
    /** 估算的"播报占用"截止时间，避免唤醒监听把自己念的话当成唤醒词 */
    private volatile long busyUntilMs = 0;

    public VoiceAnnouncer(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.xunfeiTts = new XunfeiTtsEngine(this.context);
    }

    /** 初始化（系统 TTS 异步就绪；讯飞通道无需预热）。 */
    public void init() {
        if (SpeechConfig.TTS_ENABLED
                && !isBlank(SpeechConfig.XUNFEI_APP_ID)
                && !isBlank(SpeechConfig.XUNFEI_API_KEY)
                && !isBlank(SpeechConfig.XUNFEI_API_SECRET)) {
            lastDiag = "播报就绪（讯飞在线合成）";
            Log.i(TAG, lastDiag);
        } else {
            Log.w(TAG, "讯飞合成未配置或已关闭，回退系统 TTS");
        }
        initSystemTts();
    }

    private void initSystemTts() {
        try {
            tts = new TextToSpeech(context, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    sysReady = true;
                    Log.i(TAG, "系统 TTS 就绪（备用通路）");
                    applyLocale(currentLocale);
                } else {
                    sysReady = false;
                    Log.w(TAG, "系统 TTS 初始化失败(status=" + status + ")，将只用讯飞在线合成");
                }
            });
        } catch (Exception e) {
            sysReady = false;
            Log.e(TAG, "系统 TTS 创建失败", e);
        }
    }

    /** 是否具备播报能力（讯飞在线优先，其次系统 TTS）。 */
    public boolean isAvailable() {
        return isXunfeiUsable() || (sysReady && tts != null);
    }

    private boolean isXunfeiUsable() {
        return SpeechConfig.TTS_ENABLED
                && !isBlank(SpeechConfig.XUNFEI_APP_ID)
                && !isBlank(SpeechConfig.XUNFEI_API_KEY)
                && !isBlank(SpeechConfig.XUNFEI_API_SECRET);
    }

    public void setLocale(Locale locale) {
        if (locale != null) {
            currentLocale = locale;
            applyLocale(locale);
        }
    }

    /** 用指定语言播报文本（优先讯飞；失败再退系统 TTS）。 */
    public void announce(String text, Locale locale) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Locale use = (locale != null) ? locale : currentLocale;
        // 估算"播报占用"时长，用于播报期间别开唤醒/开麦。
        // 中文约 190ms/字；英文按 ~70ms/字符（200ms 会把英文句子估成好几秒，导致开麦过晚）。
        boolean cjk = use != null && "zh".equalsIgnoreCase(use.getLanguage());
        long perChar = cjk ? 190L : 70L;
        busyUntilMs = System.currentTimeMillis() + Math.max(900L, text.length() * perChar);
        if (isXunfeiUsable()) {
            String vcn = pickVcn(use);
            Log.i(TAG, "播报(讯飞 vcn=" + vcn + "): " + text);
            try {
                xunfeiTts.speak(text, vcn);
                return;
            } catch (Exception e) {
                Log.e(TAG, "讯飞合成调用失败，回退系统 TTS", e);
            }
        }
        announceWithSystem(text, use);
    }

    public void announce(String text) {
        announce(text, currentLocale);
    }

    /** 立即停止播报。 */
    public void stop() {
        try {
            xunfeiTts.stop();
        } catch (Exception ignored) {
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isSpeaking() {
        return xunfeiTts.isSpeaking();
    }

    /** 只合成不播放（调试唤醒词用，绕开回声消除）。 */
    public byte[] synthesizeToPcm(String text, String vcn) {
        return xunfeiTts.synthesizeToPcm(text, vcn);
    }

    /** 是否还在"播报中"（含刚开始合成的缓冲期）。唤醒监听应等它为 false 再恢复。 */
    public boolean isBusy() {
        return System.currentTimeMillis() < busyUntilMs || xunfeiTts.isSpeaking();
    }

    private void announceWithSystem(String text, Locale locale) {
        if (tts == null || !sysReady) {
            Log.w(TAG, "无可用播报通路，跳过：" + text);
            return;
        }
        applyLocale(locale);
        try {
            Log.i(TAG, "播报(系统 TTS " + locale + "): " + text);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_order_utterance");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "系统 TTS 播报失败", e);
        }
    }

    /** 语言 → 讯飞发音人。德语没有授权发音人，用英文音色兜底（能听出来是德语词）。 */
    private String pickVcn(Locale locale) {
        String lang = (locale == null) ? "zh" : locale.getLanguage();
        if ("en".equalsIgnoreCase(lang)) {
            return SpeechConfig.TTS_VCN_EN;
        }
        if ("de".equalsIgnoreCase(lang)) {
            return SpeechConfig.TTS_VCN_EN;
        }
        return SpeechConfig.TTS_VCN_ZH;
    }

    private void applyLocale(Locale locale) {
        if (tts == null || !sysReady) {
            return;
        }
        try {
            int r = tts.setLanguage(locale);
            boolean langOk = (r != TextToSpeech.LANG_MISSING_DATA
                    && r != TextToSpeech.LANG_NOT_SUPPORTED);
            Voice best = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                java.util.Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (locale.getLanguage().equalsIgnoreCase(v.getLocale().getLanguage())) {
                            best = v;
                            break;
                        }
                    }
                }
            }
            if (best != null) {
                tts.setVoice(best);
            }
            if (!langOk) {
                lastDiag = "系统 TTS 无" + locale.getDisplayLanguage() + "语音";
            }
        } catch (Exception e) {
            Log.w(TAG, "applyLocale failed", e);
        }
    }

    public String getDiagnostics() {
        if (isXunfeiUsable()) {
            return "播报就绪（讯飞在线合成）";
        }
        if (sysReady) {
            return "播报就绪（系统 TTS）";
        }
        return "播报不可用：" + lastDiag;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public void shutdown() {
        stop();
        try {
            xunfeiTts.release();
        } catch (Exception ignored) {
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        sysReady = false;
    }
}
