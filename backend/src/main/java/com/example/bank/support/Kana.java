package com.example.bank.support;

import java.util.HashMap;
import java.util.Map;

/**
 * 全銀フォーマット用の文字変換: 全角カタカナ → 半角カタカナ（濁点・半濁点は 2 文字）、小書き文字は大書き、
 * 長音「ー」は「-」、英字は大文字。全銀で使えない文字は除く。
 */
public final class Kana {
    // FULL と HALF は同じ位置の文字が対応する（小書き文字「ァ」などは大書きの半角に寄せる）
    private static final String FULL = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
            + "ァィゥェォッャュョヮ";
    private static final String HALF = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ"
            + "ｱｲｳｴｵﾂﾔﾕﾖﾜ";
    private static final String DAKUTEN = "ガギグゲゴザジズゼゾダヂヅデドバビブベボ";
    private static final String DAKUTEN_BASE = "ｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾊﾋﾌﾍﾎ";
    private static final String HANDAKUTEN = "パピプペポ";
    private static final String HANDAKUTEN_BASE = "ﾊﾋﾌﾍﾎ";
    private static final Map<Character, String> MAP = new HashMap<>();

    // 変換表はクラス読み込み時に 1 度だけ作る（以後は読み取り専用なのでスレッド間で共有してよい）
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
            // すでに半角カナの文字は、小書きだけ大書きに直してそのまま使う
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
        // 受取人名の照合で、空白や記号の有無による表記ゆれを吸収する
        return toZengin(s).replaceAll("[ ().\\-/,]", "");
    }

    /** 右側を空白で埋めて固定長にする（長すぎる場合は切り詰める）。 */
    public static String padRight(String s, int width) {
        String v = s == null ? "" : s;
        // 全銀の固定長レコードの文字項目用。桁を超える部分は切り捨てて後続項目の桁位置を守る
        if (v.length() >= width) return v.substring(0, width);
        StringBuilder sb = new StringBuilder(v);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /** 左側を 0 で埋めて固定長の数字にする。 */
    public static String padNumber(long value, int width) {
        String v = Long.toString(value);
        // 数値項目は切り捨てると金額が変わってしまうため、桁あふれは例外にする
        if (v.length() > width) throw new IllegalArgumentException("number too long: " + v);
        StringBuilder sb = new StringBuilder();
        while (sb.length() + v.length() < width) sb.append('0');
        return sb.append(v).toString();
    }
}
