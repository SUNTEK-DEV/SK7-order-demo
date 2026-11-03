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
     * 一条唤醒词。{@code tokens} 是 ppinyin（声母+带调韵母），必须都在 assets/kws/tokens.txt 里；
     * {@code boost} / {@code threshold} 是 sherpa KWS 的每词参数（0 表示用全局默认）：
     *  - boost 越大越容易命中（相当于热词加权）；
     *  - threshold 是「触发所需的最低声学概率」，越小越容易触发（0~1）。
     *
     * 短词（如 hello 只有四个音素）天然比「你好点餐」难命中，
     * 所以给它们单独的 boost/threshold，而不是去改全局阈值
     * （全局调低会让「你好点餐」被环境噪声误触发）。
     */
    public static class WakeWord {
        public final String tokens;
        public final String display;
        public final float boost;
        public final float threshold;

        public WakeWord(String tokens, String display, float boost, float threshold) {
            this.tokens = tokens;
            this.display = display;
            this.boost = boost;
            this.threshold = threshold;
        }
    }

    /**
     * 唤醒词表。
     *
     * 关键经验：这个模型（wenetspeech KWS，ppinyin 词表）解出来的韵母是**带声调**的，
     * 所以关键词里必须写 ǎ/ā/óu 这种带调 token；写成不带调的 "h a l ou" 几乎永不命中
     * —— 这正是之前 hello/哈喽 唤不醒、而「你好点餐」（n ǐ h ǎo …）正常的原因。
     * 英文只能按中文近音拼（hello ≈ 哈喽 / 嗨喽），声调不确定，就多写几种变体。
     */
    public static final List<WakeWord> WAKE_WORDS = new ArrayList<>();

    static {
        // —— 点餐唤醒（中文，已验证） ——
        WAKE_WORDS.add(new WakeWord("n ǐ h ǎo d iǎn c ān", "你好点餐", 1.5f, 0.25f));
        WAKE_WORDS.add(new WakeWord("n ǐ h ǎo d iǎn d ān", "你好点单", 1.5f, 0.25f));
        // —— 支付类快捷唤醒（长词，误触极低）：待机时直接说这些词就进支付流程 ——
        // 四个长词的 token 都已在 assets/kws/tokens.txt 中校验存在；boost 略高于点餐词、
        // threshold 略低于点餐词，保证长词稳定命中（长词本身就极难被环境噪声误触发）。
        WAKE_WORDS.add(new WakeWord("w ǒ y ào j ié s uàn", "我要结算", 2.0f, 0.20f));
        WAKE_WORDS.add(new WakeWord("w ǒ y ào m ǎi d ān", "我要买单", 2.0f, 0.20f));
        WAKE_WORDS.add(new WakeWord("w ǒ y ào zh ī f ù", "我要支付", 2.0f, 0.20f));
        WAKE_WORDS.add(new WakeWord("w ǒ y ào f ù k uǎn", "我要付款", 2.0f, 0.20f));
        // —— 英文 hello / 哈喽（给中文母语者说的「哈喽」）：短词，放宽阈值 + 提高加权 ——
        // 声调来自实测：模型把「哈喽」解成 h ā l óu，写成不带调的 h a l ou 永远不命中。
        // 英文标准 hello / hi 由 assets/kws_en 独立模型处理（见 WAKE_WORDS_EN），
        // 这里不再放 check/pay/bill 等英文词——它们不在中文词表里，写进去只会被静默跳过。
        WAKE_WORDS.add(new WakeWord("h ā l óu", "哈喽", 3.0f, 0.15f));
        WAKE_WORDS.add(new WakeWord("h ǎ l óu", "哈喽", 3.0f, 0.15f));
        WAKE_WORDS.add(new WakeWord("h āi l óu", "哈喽", 3.0f, 0.15f));
        WAKE_WORDS.add(new WakeWord("h ǎi l óu", "哈喽", 3.0f, 0.15f));
    }

    /** 界面提示用的唤醒词说明（中英都给，方便双语顾客）。 */
    public static String wakeHint() {
        return "「你好点餐 / 我要结算」或「Hello / 哈喽」";
    }

    /**
     * 判断一个唤醒词显示名是否属于「支付类快捷唤醒」。
     * 用于让「我要结算 / 我要买单」等直接进支付流程，而不是先播欢迎语再听意图。
     * 英文支付唤醒词（如 checkout / pay）若将来加入，也会在此识别。
     */
    public static boolean isPaymentWake(String keyword) {
        if (keyword == null) {
            return false;
        }
        return keyword.contains("结算") || keyword.contains("支付")
                || keyword.contains("付款") || keyword.contains("买单")
                || keyword.contains("结账")
                || keyword.equalsIgnoreCase("checkout") || keyword.equalsIgnoreCase("pay");
    }

    /**
     * 英文唤醒词（独立模型 assets/kws_en，BPE token，必须存在于它的 tokens.txt）。
     *
     * 为什么中文模型救不了英文：实测把英文 hello 的音频直接灌进中文 KWS 模型，
     * 它解出来是 h ěn d òu / h ěn d uō（很逗 / 很多）—— 中文词表里根本没有英文音，
     * 把「很多」设成唤醒词又会被日常对话频繁误触发，所以只能用英文模型。
     *
     * 为什么要写这么多变体：BPE 对同一个 "hello" 会有多种合法切分，而且同一个词
     * 在「单独说」「句首后接其它词」「中文口音」三种情况下的解码结果完全不同
     * （实测分别是 hono / who know / HE-LL-O）。只写一种切分会漏掉大半说法。
     *
     * 阈值取值是实测出来的：
     *  - 太高（0.25）→ 短词几乎不触发；
     *  - 太低（0.03/0.06）→ "how are you today" 被误判成 hello，
     *    实测在实机上连环境噪声都能把它喊醒（日志里无故冒出 "检测到唤醒词：Hello"）。
     * 0.12 是「4 条正样本全中 + 4 条负样本全不误触」的平衡点，别再往下调。
     *
     * 每行：{ token 序列, 显示名, boost, threshold }
     */
    public static final String[][] WAKE_WORDS_EN = {
            // 标准英式/美式发音，BPE 会把 hello 切成下面几种，全给上（多一条几乎无额外开销）
            {"▁HE LL O", "Hello", "3.0", "0.12"},
            {"▁HE L LO", "Hello", "3.0", "0.12"},
            {"▁HE L L O", "Hello", "3.0", "0.12"},
            {"H E L L O", "Hello", "3.0", "0.12"},
            // 带中国口音的 "hello"（哈喽）实测会被这个英文模型解成 "hono" / "who know"，
            // 中文母语顾客占多数，这组命中率最高。
            {"▁HO N O", "Hello", "3.0", "0.12"},
            {"HO N O", "Hello", "3.0", "0.12"},
            {"▁WHO ▁KNOW", "Hello", "2.5", "0.10"},
            // 短招呼语
            {"▁HI", "Hi", "2.0", "0.15"},
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
