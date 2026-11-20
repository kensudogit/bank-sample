package com.example.bank.action;

import com.example.bank.domain.TxType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 入力形式チェック（Action の validate() から使用）。 */
final class InputRules {
    private static final Pattern ACCOUNT_NO = Pattern.compile("^[0-9]{7}$");
    private static final Pattern CUSTOMER_ID = Pattern.compile("^CU[0-9]{4,8}$");
    private static final Pattern AMOUNT = Pattern.compile("^[1-9][0-9]{0,8}$");
    private static final Pattern AMOUNT_OR_ZERO = Pattern.compile("^(0|[1-9][0-9]{0,8})$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9-]{16,64}$");
    /** 全角カタカナ・長音・全角/半角スペース */
    private static final Pattern KANA = Pattern.compile("^[\\u30A1-\\u30F6\\u30FC\\u3000 ]+$");
    private static final Pattern POSTAL_CODE = Pattern.compile("^[0-9]{3}-?[0-9]{4}$");
    private static final Pattern PHONE = Pattern.compile("^0[0-9]{9,10}$");
    private static final Pattern ID_DOC_NUMBER = Pattern.compile("^[A-Za-z0-9]{8,20}$");
    private static final Pattern PAGE = Pattern.compile("^[1-9][0-9]{0,5}$");
    private static final Set<String> ID_DOC_TYPES =
            new HashSet<>(Arrays.asList("DRIVER_LICENSE", "MY_NUMBER_CARD", "PASSPORT", "RESIDENCE_CARD"));
    private static final Set<String> ACCOUNT_TYPES = new HashSet<>(Arrays.asList("ORDINARY", "SAVINGS"));

    static final int MEMO_MAX = 50;
    static final int NAME_MAX = 50;
    static final int ADDRESS_MAX = 200;
    static final int NOTE_MAX = 200;

    private InputRules() {}

    static boolean isAccountNo(String s) { return s != null && ACCOUNT_NO.matcher(s).matches(); }

    static boolean isCustomerId(String s) { return s != null && CUSTOMER_ID.matcher(s).matches(); }

    /** 円単位の正の整数（最大 9 桁）。 */
    static boolean isAmount(String s) { return s != null && AMOUNT.matcher(s).matches(); }

    /** 円単位の 0 以上の整数（最大 9 桁）。 */
    static boolean isAmountOrZero(String s) { return s != null && AMOUNT_OR_ZERO.matcher(s).matches(); }

    static BigDecimal toAmount(String s) { return new BigDecimal(s); }

    static boolean isIdempotencyKey(String s) { return s != null && IDEMPOTENCY_KEY.matcher(s).matches(); }

    static boolean isMemo(String s) { return s == null || isText(s, MEMO_MAX); }

    /** 1〜max 文字（コードポイント数）で制御文字を含まない。 */
    static boolean isText(String s, int max) {
        if (s == null || s.isEmpty()) return false;
        return s.codePointCount(0, s.length()) <= max && s.chars().noneMatch(Character::isISOControl);
    }

    static boolean isKana(String s) { return isText(s, NAME_MAX) && KANA.matcher(s).matches(); }

    static boolean isPostalCode(String s) { return s != null && POSTAL_CODE.matcher(s).matches(); }

    /** 123-4567 形式にそろえる。 */
    static String normalizePostalCode(String s) {
        String digits = s.replace("-", "");
        return digits.substring(0, 3) + "-" + digits.substring(3);
    }

    /** ハイフンを除いた数字 10〜11 桁（先頭 0）。 */
    static boolean isPhone(String s) { return s != null && PHONE.matcher(s).matches(); }

    static String digitsOnly(String s) { return s == null ? null : s.replace("-", "").trim(); }

    static boolean isIdDocType(String s) { return s != null && ID_DOC_TYPES.contains(s); }

    static boolean isIdDocNumber(String s) { return s != null && ID_DOC_NUMBER.matcher(s).matches(); }

    static boolean isAccountType(String s) { return s != null && ACCOUNT_TYPES.contains(s); }

    /** yyyy-MM-dd（存在する日付のみ）。不正なら null。 */
    static LocalDate toDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 業務で扱う日付の範囲（1900-01-01〜2199-12-31）。 */
    static boolean isReasonableDate(LocalDate d) {
        return d != null && d.getYear() >= 1900 && d.getYear() <= 2199;
    }

    static boolean isPositiveInt(String s) { return s != null && PAGE.matcher(s).matches(); }

    /** カンマ区切りの取引種別。不正な値を含む場合は null、空なら空リスト。 */
    static List<TxType> toTxTypes(String s) {
        List<TxType> list = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return list;
        for (String part : s.split(",")) {
            try {
                TxType t = TxType.valueOf(part.trim());
                if (!list.contains(t)) list.add(t);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return list;
    }

    static String trim(String s) { return s == null ? null : s.trim(); }

    static String emptyToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
