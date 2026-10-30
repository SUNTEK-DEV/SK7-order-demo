package com.suntek.checkoutapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音点餐解析器：把一段识别文本（中文/English/Deutsch）解析成「数量 + 商品」列表。
 *
 * 思路（与 myAPK 把语音文本解析成灯光命令同源）：
 *  1. 用 ProductLocalizer 取每个商品的多种语言别名；
 *  2. 在文本中做不区分大小写的子串匹配，并按“最长别名优先”消除歧义
 *     （例如 “chicken fries” 不会同时误命中 “chicken”）；
 *  3. 在每个商品前后的小句里找最近的数值（数字或 “two/两/zwei” 等量词），默认 1；
 *  4. 同一商品多次出现时合并数量。
 */
public final class VoiceOrderParser {

    /** 单条点餐指令。 */
    public static class OrderCommand {
        public final Product product;
        public final int quantity;
        /** 命中的商品名称（已是对应语言的说法，便于播报） */
        public final String displayName;

        public OrderCommand(Product product, int quantity, String displayName) {
            this.product = product;
            this.quantity = quantity;
            this.displayName = displayName;
        }
    }

    /** 各语言数字/量词 -> 数值（中文为单字，英文/德文为单词）。 */
    private static final Map<String, Integer> NUMBER_WORDS = new HashMap<>();

    static {
        // 中文（单字）
        NUMBER_WORDS.put("零", 0); NUMBER_WORDS.put("一", 1); NUMBER_WORDS.put("二", 2);
        NUMBER_WORDS.put("两", 2); NUMBER_WORDS.put("三", 3); NUMBER_WORDS.put("四", 4);
        NUMBER_WORDS.put("五", 5); NUMBER_WORDS.put("六", 6); NUMBER_WORDS.put("七", 7);
        NUMBER_WORDS.put("八", 8); NUMBER_WORDS.put("九", 9); NUMBER_WORDS.put("十", 10);
        // 英文
        NUMBER_WORDS.put("a", 1); NUMBER_WORDS.put("an", 1); NUMBER_WORDS.put("one", 1);
        NUMBER_WORDS.put("two", 2); NUMBER_WORDS.put("three", 3); NUMBER_WORDS.put("four", 4);
        NUMBER_WORDS.put("five", 5); NUMBER_WORDS.put("six", 6); NUMBER_WORDS.put("seven", 7);
        NUMBER_WORDS.put("eight", 8); NUMBER_WORDS.put("nine", 9); NUMBER_WORDS.put("ten", 10);
        NUMBER_WORDS.put("dozen", 12);
        // 德文
        NUMBER_WORDS.put("ein", 1); NUMBER_WORDS.put("eine", 1); NUMBER_WORDS.put("eins", 1);
        NUMBER_WORDS.put("zwei", 2); NUMBER_WORDS.put("drei", 3); NUMBER_WORDS.put("vier", 4);
        NUMBER_WORDS.put("fünf", 5); NUMBER_WORDS.put("sechs", 6); NUMBER_WORDS.put("sieben", 7);
        NUMBER_WORDS.put("acht", 8); NUMBER_WORDS.put("neun", 9); NUMBER_WORDS.put("zehn", 10);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private VoiceOrderParser() {
    }

    public static List<OrderCommand> parse(String transcript, String langTag, List<Product> products) {
        List<OrderCommand> result = new ArrayList<>();
        if (transcript == null || transcript.trim().isEmpty() || products == null) {
            return result;
        }
        String text = transcript.toLowerCase();

        // 1) 收集所有（商品, 命中别名, 位置）
        class Match {
            Product product;
            String alias;
            int start;
            int len;
            Match(Product p, String a, int s) { product = p; alias = a; start = s; len = a.length(); }
        }
        List<Match> all = new ArrayList<>();
        for (Product p : products) {
            List<String> aliases = ProductLocalizer.getAliases(p, langTag);
            for (String alias : aliases) {
                if (alias == null || alias.isEmpty()) continue;
                int idx = text.indexOf(alias);
                if (idx >= 0) {
                    all.add(new Match(p, alias, idx));
                }
            }
        }
        if (all.isEmpty()) {
            return result;
        }

        // 2) 最长别名优先，去除重叠，得到无冲突的命中
        all.sort((m1, m2) -> Integer.compare(m2.len, m1.len));
        List<Match> selected = new ArrayList<>();
        for (Match m : all) {
            boolean overlap = false;
            for (Match s : selected) {
                if (m.start < s.start + s.len && s.start < m.start + m.len) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                selected.add(m);
            }
        }
        selected.sort((m1, m2) -> Integer.compare(m1.start, m2.start));

        // 3) 逐个确定数量并合并同一商品
        Map<String, OrderCommand> merged = new HashMap<>();
        for (int i = 0; i < selected.size(); i++) {
            Match m = selected.get(i);
            int prevEnd = (i == 0) ? 0 : selected.get(i - 1).start + selected.get(i - 1).len;
            int nextStart = (i + 1 < selected.size()) ? selected.get(i + 1).start : text.length();
            String clause = text.substring(prevEnd, nextStart);
            int targetPos = m.start - prevEnd;
            int qty = findQuantity(clause, targetPos, langTag);
            if (qty < 1) qty = 1;

            OrderCommand existing = merged.get(m.product.getId());
            if (existing != null) {
                merged.put(m.product.getId(),
                        new OrderCommand(m.product, existing.quantity + qty, existing.displayName));
            } else {
                merged.put(m.product.getId(), new OrderCommand(m.product, qty, m.alias));
            }
        }

        result.addAll(merged.values());
        return result;
    }

    /** 在小句里找离目标位置最近的数字/量词；找不到返回 1。 */
    private static int findQuantity(String clause, int targetPos, String langTag) {
        int bestVal = -1;
        int bestDist = Integer.MAX_VALUE;

        // 阿拉伯数字
        Matcher dm = DIGITS.matcher(clause);
        while (dm.find()) {
            try {
                int val = Integer.parseInt(dm.group());
                int pos = dm.start();
                int dist = Math.abs(pos - targetPos);
                if (dist < bestDist || (dist == bestDist && pos < targetPos)) {
                    bestDist = dist;
                    bestVal = val;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if ("zh".equals(langTag)) {
            // 中文：逐字检查量词
            for (int i = 0; i < clause.length(); i++) {
                String ch = String.valueOf(clause.charAt(i));
                Integer val = NUMBER_WORDS.get(ch);
                if (val != null) {
                    int dist = Math.abs(i - targetPos);
                    if (dist < bestDist || (dist == bestDist && i < targetPos)) {
                        bestDist = dist;
                        bestVal = val;
                    }
                }
            }
        } else {
            // 英文/德文：按单词（非字母切分）检查
            String[] tokens = clause.split("[^a-zäöüß]+");
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                Integer val = NUMBER_WORDS.get(token);
                if (val != null) {
                    int pos = clause.indexOf(token);
                    int dist = Math.abs(pos - targetPos);
                    if (dist < bestDist || (dist == bestDist && pos < targetPos)) {
                        bestDist = dist;
                        bestVal = val;
                    }
                }
            }
        }

        return bestVal < 1 ? 1 : bestVal;
    }
}
