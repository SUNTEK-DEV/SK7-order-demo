package com.suntek.checkoutapplication;

import java.util.Locale;

/**
 * 语音意图识别：把一段识别文本归到「点餐 / 支付 / 确认支付 / 取消」四类之一。
 *
 * 说明：
 *  - 只做"意图"判断，商品/数量仍交给 {@link VoiceOrderParser}，两者解耦；
 *  - 中文用子串匹配，英文/德文按单词匹配，避免 "check" 命中 "chicken" 这类误判；
 *  - PAY 必须先于 CONFIRM 判断，否则 "checkout" 会被当成 "check" 确认支付。
 */
public final class VoiceIntentParser {

    public enum Intent { ORDER, PAY, CONFIRM, CANCEL, UNKNOWN }

    /** 触发"开始支付"：说这些词就弹出 Visa / Mastercard 卡片。 */
    private static final String[] PAY_ZH = {
            "支付", "付款", "付钱", "结账", "结帐", "买单", "埋单", "刷卡", "信用卡", "银行卡", "收银",
            "结算", "算账"
    };
    private static final String[] PAY_EN = {
            "pay", "payment", "checkout", "check out", "card", "credit card", "debit card",
            "visa", "mastercard", "master card", "tap", "pay now"
    };
    private static final String[] PAY_DE = {
            "bezahlen", "zahlen", "karte", "kartenzahlung", "kasse", "rechnung"
    };

    /**
     * 确认支付：卡片弹出后说这些词就出票。
     * 注意不要放「可以 / 好的 / 好了」这类太泛的词——顾客点餐时说
     * 「好的，再来一杯」会被误判成确认付款。
     */
    private static final String[] CONFIRM_ZH = {
            "确认支付", "确认付款", "确认", "确定", "同意", "支付成功", "付款成功",
            "完成支付", "出票", "打印小票", "打印"
    };
    private static final String[] CONFIRM_EN = {
            "check", "confirm", "confirmed", "approve", "approved", "yes", "yeah", "ok",
            "okay", "done", "go ahead", "print", "print it", "payment done"
    };
    private static final String[] CONFIRM_DE = {
            "bestätigen", "bestaetigen", "ja", "ok", "fertig", "bezahlt", "drucken"
    };

    /** 取消支付。 */
    private static final String[] CANCEL_ZH = {
            "取消支付", "取消付款", "不支付", "不要支付", "先不付",
            "取消", "不要了", "算了", "停止", "退出"
    };
    private static final String[] CANCEL_EN = {
            "cancel", "stop", "abort", "no", "nope", "not now", "never mind"
    };
    private static final String[] CANCEL_DE = {
            "abbrechen", "stornieren", "nein", "stop"
    };

    private VoiceIntentParser() {
    }

    /**
     * 判断一段识别文本的意图。
     *
     * 关键是"最长命中优先"：句子里同时出现「确认支付」和「支付」时，
     * 命中更长的那个类别胜出（否则 "确认支付" 会被 "支付" 判成 PAY，永远确认不了）；
     *
     * "checkout" 也会因为比 "check" 长而正确判为 PAY。
     * 打平时：取消 > 支付 > 确认（不动钱最安全）。
     */
    public static Intent classify(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) {
            return Intent.UNKNOWN;
        }
        String text = normalize(transcript);
        String[] words = latinWords(text);

        int pay = score(text, words, PAY_ZH, PAY_EN, PAY_DE);
        int confirm = score(text, words, CONFIRM_ZH, CONFIRM_EN, CONFIRM_DE);
        int cancel = score(text, words, CANCEL_ZH, CANCEL_EN, CANCEL_DE);

        if (pay == 0 && confirm == 0 && cancel == 0) {
            return Intent.ORDER;
        }
        if (cancel > 0 && cancel >= pay && cancel >= confirm) {
            return Intent.CANCEL;
        }
        if (pay > 0 && pay >= confirm) {
            return Intent.PAY;
        }
        if (confirm > 0) {
            return Intent.CONFIRM;
        }
        return Intent.ORDER;
    }

    /** 小写、去标点，中文保留原样（识别结果常带「。」「，」）。 */
    private static String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                sb.append(c);
            } else {
                sb.append(' '); // 标点当分隔符，"pay." / "pay," 都能命中
            }
        }
        return sb.toString();
    }

    /** 英文/德文按单词切分，用于整词匹配。 */
    private static String[] latinWords(String text) {
        String cleaned = text.replaceAll("[^\\p{IsLatin}0-9äöüß ]", " ");
        return cleaned.trim().isEmpty() ? new String[0] : cleaned.trim().split("\\s+");
    }

    /** 返回该类别的最高命中长度（0 = 没命中），用于"最长命中优先"。 */
    private static int score(String text, String[] words,
                             String[] zh, String[] en, String[] de) {
        int best = 0;
        for (String kw : zh) {
            if (text.contains(kw)) {
                best = Math.max(best, kw.length());
            }
        }
        for (String kw : en) {
            if (containsPhrase(words, kw)) {
                best = Math.max(best, kw.length());
            }
        }
        for (String kw : de) {
            if (containsPhrase(words, kw)) {
                best = Math.max(best, kw.length());
            }
        }
        return best;
    }

    /** 整词（或整词组）匹配，words 已按空白切分。 */
    private static boolean containsPhrase(String[] words, String phrase) {
        String[] target = phrase.split(" ");
        if (target.length == 0 || words.length < target.length) {
            return false;
        }
        for (int i = 0; i + target.length <= words.length; i++) {
            boolean ok = true;
            for (int j = 0; j < target.length; j++) {
                if (!words[i + j].equals(target[j])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
