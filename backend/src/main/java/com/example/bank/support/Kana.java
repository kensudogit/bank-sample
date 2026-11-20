package com.example.bank.support;

import java.util.HashMap;
import java.util.Map;

/**
 * 全銀フォーマット用の文字変換: 全角カタカナ → 半角カタカナ（濁点・半濁点は 2 文字）、小書き文字は大書き、
 * 長音「ー」は「-」、英字は大文字。全銀で使えない文字は除く。
 */
public final class Kana {
    private static final String FULL = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
            + "ァィゥェォッャュョヮ";
    private static final String HALF = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ"
            + "ｱｲｳｴｵﾂﾔﾕﾖﾜ";
    private static final String DAKUTEN = "ガギグゲゴザジズゼゾダヂヅデドバビブベボ";
    private static final String DAKUTEN_BASE = "ｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾊﾋﾌﾍﾎ";
    private static final String HANDAKUTEN = "パピプペポ";
    private static final String HANDAKUTEN_BASE = "ﾊﾋﾌﾍﾎ";
    private static final Map<Character, String> MAP = new HashMap<>();

    static {
        for (int i = 0; i < FULL.length(); i++) MAP.put(FULL.charAt(i), String.valueOf(HALF.charAt(i)));
        for (int i = 0; i < DAKUTEN.length(); i++) MAP.put(DAKUTEN.charAt(i), DAKUTEN_BASE.charAt(i) + "ﾞ");
        for (int i = 0; i < HANDAKUTEN.length(); i++) MAP.put(HANDAKUTEN.charAt(i), HANDAKUTEN_BASE.charAt(i) + "ﾟ");
        MAP.put('ヴ', "ｳﾞ");
        MAP.put('ー', "-");
        MAP.put('　', " ");
        MAP.put('（', "(");
        MAP.put('）', ")");
        MAP.put('．', ".");
        MAP.put('－', "-");
        MAP.put('／', "/");
    }

    private Kana() {}

    /** 全銀で使える半角文字列に変換する。 */
    public static String toZengin(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            String m = MAP.get(ch);
            if (m != null) {
                sb.append(m);
            } else if (ch >= 'ｦ' && ch <= 'ﾟ') {
                sb.append(ch == 'ｧ' ? 'ｱ' : ch == 'ｨ' ? 'ｲ' : ch == 'ｩ' ? 'ｳ' : ch == 'ｪ' ? 'ｴ' : ch == 'ｫ' ? 'ｵ'
                        : ch == 'ｯ' ? 'ﾂ' : ch == 'ｬ' ? 'ﾔ' : ch == 'ｭ' ? 'ﾕ' : ch == 'ｮ' ? 'ﾖ' : ch);
            } else if (ch >= 'ａ' && ch <= 'ｚ') {
                sb.append((char) (ch - 'ａ' + 'A'));
            } else if (ch >= 'Ａ' && ch <= 'Ｚ') {
                sb.append((char) (ch - 'Ａ' + 'A'));
            } else if (ch >= '０' && ch <= '９') {
                sb.append((char) (ch - '０' + '0'));
            } else if (ch >= 'a' && ch <= 'z') {
                sb.append(Character.toUpperCase(ch));
            } else if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || " ().-/,".indexOf(ch) >= 0) {
                sb.append(ch);
            }
            // other characters are not allowed in Zengin records and are dropped
        }
        return sb.toString();
    }

    /** 名義照合用: 全銀文字に変換し、空白・記号を除く。 */
    public static String normalizeName(String s) {
        return toZengin(s).replaceAll("[ ().\\-/,]", "");
    }

    /** 右側を空白で埋めて固定長にする（長すぎる場合は切り詰める）。 */
    public static String padRight(String s, int width) {
        String v = s == null ? "" : s;
        if (v.length() >= width) return v.substring(0, width);
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /** 左側を 0 で埋めて固定長の数字にする。 */
    public static String padNumber(long value, int width) {
        String v = Long.toString(value);
        if (v.length() > width) throw new IllegalArgumentException("number too long: " + v);
        StringBuilder sb = new StringBuilder();
        while (sb.length() + v.length() < width) sb.append('0');
        return sb.append(v).toString();
    }
}
