package com.suntek.checkoutapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品多语言别名表（用于语音点餐时把各语言说法映射到同一商品）。
 *
 * 以商品的英文规范名为 key，值为 { 语言标记 -> 该语言下的别名列表 }。
 * 维护提示：新增/修改商品时，在此同步添加它的中文、英文、德文叫法即可；
 * 语音识别会同时匹配“当前语言别名 + 英文规范名(通用兜底)”。
 */
public final class ProductLocalizer {

    private static final Map<String, Map<String, List<String>>> TABLE = new HashMap<>();

    static {
        put("Sweets", zh("糖果", "糖"), en("sweets", "candy", "sweet"), de("Süßigkeiten"));
        put("Sprite", zh("雪碧"), en("sprite"), de("Sprite", "Sprite-Cola"));
        put("Mineral Water", zh("矿泉水", "矿泉", "水"), en("mineral water", "water"), de("Mineralwasser", "Wasser"));
        put("Hamburger", zh("汉堡", "汉堡包", "面包", "汉堡王"), en("hamburger", "burger", "ham"), de("Hamburger", "Burger"));
        put("Digital Exclusives", zh("数码", "数码产品", "电子产品"), en("digital exclusives", "digital", "electronics"), de("Digitale Exklusivartikel", "Digital", "Elektronik"));
        put("Fried Chicken", zh("炸鸡", "烤鸡"), en("fried chicken", "chicken"), de("Brathähnchen", "Fried Chicken"));
        put("Honey Mustard Royal Crispy Wrap", zh("蜂蜜芥末卷", "芥末卷", "卷"), en("honey mustard wrap", "wrap"), de("Honey-Mustard-Wrap", "Wrap"));
        put("Ultimate Wrap Party", zh("套餐卷", "卷套餐", "卷"), en("wrap party", "wrap"), de("Wrap-Party", "Wrap"));
        put("Chicken Fries", zh("鸡薯条", "鸡块", "薯条"), en("chicken fries", "fries", "pommes"), de("Chicken Fries", "Pommes"));
        put("Cake", zh("蛋糕"), en("cake"), de("Kuchen", "Torte"));
        put("Ciggie", zh("香烟", "烟"), en("ciggie", "cigarette", "cigarettes"), de("Zigaretten", "Zigarette"));
        put("Coffee", zh("咖啡"), en("coffee"), de("Kaffee"));
        put("Tea", zh("茶"), en("tea"), de("Tee"));
    }

    private static List<String> zh(String... a) { return java.util.Arrays.asList(a); }
    private static List<String> en(String... a) { return java.util.Arrays.asList(a); }
    private static List<String> de(String... a) { return java.util.Arrays.asList(a); }

    private static void put(String canonical, List<String> zh, List<String> en, List<String> de) {
        Map<String, List<String>> byLang = new HashMap<>();
        byLang.put("zh", zh);
        byLang.put("en", en);
        byLang.put("de", de);
        TABLE.put(canonical, byLang);
    }

    /**
     * 返回某商品在指定语言下的候选别名（含英文规范名作为通用兜底）。
     * 全部转为小写，便于不区分大小写匹配。
     */
    public static List<String> getAliases(Product product, String langTag) {
        List<String> result = new ArrayList<>();
        String canonical = product.getName();
        if (canonical != null) {
            result.add(canonical.toLowerCase());
        }
        Map<String, List<String>> byLang = TABLE.get(canonical);
        if (byLang != null) {
            List<String> current = byLang.get(langTag);
            if (current != null) {
                for (String s : current) result.add(s.toLowerCase());
            }
            // 英文别名也作为通用兜底（跨语言容错）
            List<String> enList = byLang.get("en");
            if (enList != null) {
                for (String s : enList) {
                    if (!result.contains(s.toLowerCase())) {
                        result.add(s.toLowerCase());
                    }
                }
            }
        }
        return result;
    }

    private ProductLocalizer() {
    }
}
