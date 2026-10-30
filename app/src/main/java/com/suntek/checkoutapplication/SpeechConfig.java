package com.suntek.checkoutapplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音功能全局配置。
 *
 * - 云端 STT：当前默认对接【讯飞开放平台 · 语音听写(iat)】WebSocket 接口（无需 GMS，适合 RK 板）。
 *   凭证见下方 XUNFEI_* 常量；如需切回 Google Cloud Speech-to-Text，把 PROVIDER 改成 GOOGLE 即可。
 * - 多语言：点餐支持的语言列表与每种语言对应的 BCP-47 编码、TTS Locale 集中在此处维护。
 *
 * 关于讯飞的语言支持：iat 官方稳定支持中文(zh_cn)、英文(en_us)等，
 * 德语(de_de) 在部分账号/版本可用，若返回错误码可在界面上看到提示。
 */
public final class SpeechConfig {

    // ======================= 讯飞开放平台凭证 =======================
    /** 讯飞控制台 → 应用 → APPID */
    public static final String XUNFEI_APP_ID = "b77b0c3c";
    /** APIKey（用于 HMAC 签名） */
    public static final String XUNFEI_API_KEY = "9af251f5053c91ade4ac1ae80597acb7";
    /** APISecret（用于 HMAC 签名） */
    public static final String XUNFEI_API_SECRET = "OWQ2OTE4NzA3ZDg4MzI2NzVmZDA3ODhk";

    // ======================= 讯飞在线语音合成（TTS） =======================
    /**
     * 中文发音人：xiaoyan（小燕，女声）。
     * 其它可选：aisjiuxu / aisxping / xiaofeng 等，见讯飞控制台。
     */
    public static final String TTS_VCN_ZH = "xiaoyan";
    /** 英文发音人：aisjiuxu（小旭）。 */
    public static final String TTS_VCN_EN = "aisjiuxu";
    /** 是否启用语音播报（讯飞在线合成）。关掉则只弹 Toast。 */
    public static final boolean TTS_ENABLED = true;

    // ======================= 语音唤醒 =======================
    /**
     * 唤醒词配置：{ 拼音音素（空格分隔）, 中文显示 }。
     * 音素必须使用 sougou-pinyin 风格的声母+韵母，且要在 assets/kws/tokens.txt 里存在。
     * 想换唤醒词时改这里即可（例如「小收点餐」= x iǎo sh ōu d iǎn c ān）。
     */
    public static final String[][] WAKE_WORDS = {
            {"n ǐ h ǎo d iǎn c ān", "你好点餐"},
            {"n ǐ h ǎo d iǎn d ān", "你好点单"},
    };

    /** 是否默认开启语音唤醒（开机自动常驻监听）。 */
    public static final boolean WAKE_ENABLED_BY_DEFAULT = true;

    // ======================= Google 云端 STT（备用） =======================
    /** Google Cloud STT 的 API Key；留空时该通道不可用。 */
    public static final String CLOUD_API_KEY = "";

    /**
     * 当前启用的云端 STT 提供方。
     * XUNFEI = 讯飞语音听写（默认，已接入）
     * GOOGLE = Google Cloud Speech-to-Text（备用，需填 CLOUD_API_KEY）
     */
    public enum Provider { XUNFEI, GOOGLE }

    public static final Provider PROVIDER = Provider.XUNFEI;

    /** 单种点餐语言的描述。 */
    public static class Lang {
        /** 内部标记，例如 "zh" / "en" / "de" */
        public final String tag;
        /** 下拉框中展示的名称，例如 "中文" / "English" / "Deutsch" */
        public final String display;
        /** 传给云端 STT 的语言码（BCP-47），例如 "zh-CN" / "en-US" / "de-DE" */
        public final String bcp47;
        /** 语音播报（TTS）使用的 Locale */
        public final Locale locale;

        public Lang(String tag, String display, String bcp47, Locale locale) {
            this.tag = tag;
            this.display = display;
            this.bcp47 = bcp47;
            this.locale = locale;
        }
    }

    /** 支持的点餐语言（顺序即下拉框顺序）。 */
    public static final List<Lang> LANGUAGES = new ArrayList<>();

    static {
        LANGUAGES.add(new Lang("zh", "中文", "zh-CN", Locale.SIMPLIFIED_CHINESE));
        LANGUAGES.add(new Lang("en", "English", "en-US", Locale.US));
        LANGUAGES.add(new Lang("de", "Deutsch", "de-DE", Locale.GERMANY));
    }

    private SpeechConfig() {
    }

    /** 按下拉框位置取语言；越界时回退到第一项。 */
    public static Lang getLang(int position) {
        if (position >= 0 && position < LANGUAGES.size()) {
            return LANGUAGES.get(position);
        }
        return LANGUAGES.get(0);
    }

    public static Lang getLangByTag(String tag) {
        for (Lang l : LANGUAGES) {
            if (l.tag.equals(tag)) {
                return l;
            }
        }
        return LANGUAGES.get(0);
    }
}
