package com.example.bank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 与信審査のスコアリング（DB に依存しない）。点数の合計（0〜100）から格付（A〜E）と推奨判断・適用金利を出す。
 *
 * <pre>
 *  返済比率（DTI = 年間返済額 ÷ 年収）  20%以下 40 / 30%以下 30 / 35%以下 20 / 40%以下 10 / 超 0
 *  担保掛目（LTV = 融資額 ÷ 担保評価額） 60%以下 25 / 80%以下 18 / 100%以下 10 / 超 0（無担保商品は 10）
 *  年収（法人は年間営業キャッシュフロー） 1,000万以上 15 / 600万 12 / 400万 9 / 250万 5 / 未満 0
 *  取引年数                               5 年以上 10 / 1 年以上 6 / 1 年未満 2
 *  延滞中の融資                           あり −30
 *  格付: 80〜 A / 65〜 B / 50〜 C / 35〜 D / 〜34 E
 *  推奨: A〜C 承認、D 要検討、E または形式要件違反（返済比率 40% 超・総量規制・完済時年齢 80 歳超）謝絶
 * </pre>
 */
public final class CreditScoring {
    /** 商品ごとの基準金利（%） */
    public static final Map<String, BigDecimal> BASE_RATE;
    /** 格付ごとの上乗せ金利（%） */
    public static final Map<String, BigDecimal> SPREAD;
    /** 格付ごとのデフォルト確率（PD、信用リスク管理用） */
    public static final Map<String, BigDecimal> PD;

    // 金利表は変更されない前提なので、読み取り専用の Map にして誤って書き換えられないようにする
    static {
        Map<String, BigDecimal> b = new HashMap<>();
        b.put("HOUSING", new BigDecimal("0.900"));
        b.put("BUSINESS", new BigDecimal("1.800"));
        b.put("CARD", new BigDecimal("12.000"));
        b.put("OVERDRAFT", new BigDecimal("3.000"));
        b.put("SYNDICATED", new BigDecimal("1.200"));
        BASE_RATE = Collections.unmodifiableMap(b);
        Map<String, BigDecimal> s = new HashMap<>();
        s.put("A", new BigDecimal("0.000"));
        s.put("B", new BigDecimal("0.300"));
        s.put("C", new BigDecimal("0.700"));
        s.put("D", new BigDecimal("1.500"));
        s.put("E", new BigDecimal("3.000"));
        SPREAD = Collections.unmodifiableMap(s);
        Map<String, BigDecimal> pd = new HashMap<>();
        pd.put("A", new BigDecimal("0.002"));
        pd.put("B", new BigDecimal("0.005"));
        pd.put("C", new BigDecimal("0.015"));
        pd.put("D", new BigDecimal("0.040"));
        pd.put("E", new BigDecimal("0.100"));
        PD = Collections.unmodifiableMap(pd);
    }

    /** 利息制限法の上限（元本 100 万円以上 15%）にかからないよう、カードローンは 15% を上限とする。 */
    public static final BigDecimal CARD_RATE_CAP = new BigDecimal("15.000");

    private CreditScoring() {}

    /** 審査の入力。 */
    public static final class Input {
        // 呼び出し側（LoanService）が項目を埋めて渡す単純な入れ物
        public String product;
        public BigDecimal amount;
        public int termMonths;
        public String repaymentMethod;
        public BigDecimal annualIncome;
        public BigDecimal existingDebtAnnual = BigDecimal.ZERO;
        public BigDecimal collateralValue = BigDecimal.ZERO;
        public int relationshipYears;
        public int delinquentLoans;
        /** 個人の完済時年齢（不明・法人は 0） */
        public int ageAtMaturity;
        public boolean corporate;
    }

    /** 審査結果。 */
    public static final class Result {
        // 審査結果。reasons は審査記録に残す判断理由
        public int score;
        public String grade;
        public BigDecimal dti;
        public BigDecimal ltv;
        public BigDecimal rate;
        public BigDecimal annualRepayment;
        public String recommendation;
        public final List<String> reasons = new ArrayList<>();
    }

    /**
     * 審査する（点数・格付・推奨判断・適用金利を求める）。
     *
     * @param in 審査の入力
     * @return 審査結果
     */
    public static Result evaluate(Input in) {
        Result r = new Result();
        // 格付は返済比率から決まるので、返済比率の計算には中位（C）の金利を仮に使う（循環を避ける）
        BigDecimal estRate = BASE_RATE.get(in.product).add(SPREAD.get("C"));
        r.annualRepayment = annualRepayment(in, estRate);
        r.dti = in.annualIncome.signum() > 0
                ? in.existingDebtAnnual.add(r.annualRepayment).divide(in.annualIncome, 4, RoundingMode.HALF_UP)
                // 年収 0 は返済比率を計算できないため、必ず基準超過になる大きな値にする
                : new BigDecimal("9.9999");
        boolean secured = "HOUSING".equals(in.product) || in.collateralValue.signum() > 0;
        r.ltv = in.collateralValue.signum() > 0 ? in.amount.divide(in.collateralValue, 4, RoundingMode.HALF_UP) : null;

        int score = 0;
        double dti = r.dti.doubleValue();
        score += dti <= 0.20 ? 40 : dti <= 0.30 ? 30 : dti <= 0.35 ? 20 : dti <= 0.40 ? 10 : 0;
        r.reasons.add(String.format("返済比率 %.1f%%", dti * 100));
        if (r.ltv != null) {
            double ltv = r.ltv.doubleValue();
            score += ltv <= 0.60 ? 25 : ltv <= 0.80 ? 18 : ltv <= 1.00 ? 10 : 0;
            r.reasons.add(String.format("担保掛目 %.1f%%", ltv * 100));
        } else {
            score += secured ? 0 : 10;
            r.reasons.add(secured ? "担保評価額なし" : "無担保");
        }
        double income = in.annualIncome.doubleValue();
        score += income >= 10_000_000 ? 15 : income >= 6_000_000 ? 12 : income >= 4_000_000 ? 9 : income >= 2_500_000 ? 5 : 0;
        score += in.relationshipYears >= 5 ? 10 : in.relationshipYears >= 1 ? 6 : 2;
        r.reasons.add("取引年数 " + in.relationshipYears + " 年");
        if (in.delinquentLoans > 0) {
            score -= 30;
            r.reasons.add("延滞中の融資あり");
        }
        // 延滞の減点でマイナスにならないよう 0〜100 に収める
        r.score = Math.max(0, Math.min(100, score));
        r.grade = r.score >= 80 ? "A" : r.score >= 65 ? "B" : r.score >= 50 ? "C" : r.score >= 35 ? "D" : "E";

        // 形式要件（法令・社内規程）に反する場合は、点数にかかわらず謝絶にする
        boolean hardFail = false;
        if (dti > 0.40 && !"OVERDRAFT".equals(in.product)) {
            hardFail = true;
            r.reasons.add("返済比率が 40% を超過");
        }
        if ("CARD".equals(in.product) && !in.corporate
                && in.amount.multiply(new BigDecimal("3")).compareTo(in.annualIncome) > 0) {
            hardFail = true;
            r.reasons.add("総量規制（年収の 3 分の 1）を超過");
        }
        if ("HOUSING".equals(in.product) && in.ageAtMaturity > 80) {
            hardFail = true;
            r.reasons.add("完済時年齢が 80 歳を超過");
        }
        if ("HOUSING".equals(in.product) && (r.ltv == null || r.ltv.compareTo(BigDecimal.ONE) > 0)) {
            hardFail = true;
            r.reasons.add("住宅ローンは担保評価額の範囲内");
        }
        r.recommendation = hardFail || "E".equals(r.grade) ? "DECLINE" : "D".equals(r.grade) ? "REVIEW" : "APPROVE";
        BigDecimal rate = BASE_RATE.get(in.product).add(SPREAD.get(r.grade));
        // 上限金利を超える約定は法令違反になるため、格付による上乗せ後でも上限で抑える
        if ("CARD".equals(in.product) && rate.compareTo(CARD_RATE_CAP) > 0) rate = CARD_RATE_CAP;
        r.rate = rate;
        return r;
    }

    /** 年間返済額の見込み（審査用）。 */
    static BigDecimal annualRepayment(Input in, BigDecimal rate) {
        switch (in.product) {
            case "CARD":
                // 約定返済 = 利用限度額の 3%/月 を想定
                return in.amount.multiply(new BigDecimal("0.36")).setScale(0, RoundingMode.CEILING);
            // 当座貸越は極度額を使い切った場合の 1 年分の利息を返済額とみなす
            case "OVERDRAFT":
                return in.amount.multiply(rate).divide(new BigDecimal("100"), 0, RoundingMode.CEILING);
            default:
                return LoanMath.equalPayment(in.amount, rate, in.termMonths).multiply(BigDecimal.valueOf(12));
        }
    }
}
