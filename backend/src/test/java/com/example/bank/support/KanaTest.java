package com.example.bank.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 全銀フォーマット用の文字変換と、固定長への整形。 */
class KanaTest {
    @Test
    void fullWidthKatakanaBecomesHalfWidthWithSeparateDakuten() {
        assertEquals("ﾔﾏﾀﾞ ﾀﾛｳ", Kana.toZengin("ヤマダ　タロウ"));
        assertEquals("ﾊﾟﾝ", Kana.toZengin("パン"));
        assertEquals("ｳﾞｲ", Kana.toZengin("ヴィ"));
    }

    @Test
    void smallKanaBecomesLarge() {
        assertEquals("ｷﾂﾌﾟ", Kana.toZengin("キップ"));
        assertEquals("ｼﾔｲﾝ", Kana.toZengin("シャイン"));
        // すでに半角の小書きも大書きにする
        assertEquals("ｷﾂﾌﾟ", Kana.toZengin("ｷｯﾌﾟ"));
    }

    @Test
    void lettersDigitsAndSymbols() {
        assertEquals("ABC123", Kana.toZengin("ａｂｃ１２３"));
        assertEquals("ABC", Kana.toZengin("ＡＢＣ"));
        assertEquals("ABC", Kana.toZengin("abc"));
        assertEquals("(ｶ)-./", Kana.toZengin("（カ）－．／"));
        assertEquals("ｺ-ﾋ-", Kana.toZengin("コーヒー"));
    }

    @Test
    void unsupportedCharactersAreDroppedAndNullIsEmpty() {
        assertEquals("ﾀﾛｳ", Kana.toZengin("漢字タロウ！"));
        assertEquals("", Kana.toZengin(null));
    }

    @Test
    void normalizeNameRemovesSpacesAndSymbols() {
        assertEquals("ｶﾔﾏﾀﾞ", Kana.normalizeName("（カ）ヤマダ"));
        assertEquals(Kana.normalizeName("ヤマダ タロウ"), Kana.normalizeName("ﾔﾏﾀﾞﾀﾛｳ"));
    }

    @Test
    void padRightTruncatesOrFills() {
        assertEquals("AB   ", Kana.padRight("AB", 5));
        assertEquals("ABCDE", Kana.padRight("ABCDEFG", 5));
        assertEquals("   ", Kana.padRight(null, 3));
    }

    @Test
    void padNumberFillsZerosAndRejectsOverflow() {
        assertEquals("0000012345", Kana.padNumber(12345, 10));
        assertEquals("12345", Kana.padNumber(12345, 5));
        // 金額を切り捨てると別の金額になるので、桁あふれは例外
        assertThrows(IllegalArgumentException.class, () -> Kana.padNumber(123456, 5));
    }
}
