package com.example.bank.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 返済計算（DB に依存しない）。
 * <ul>
 *   <li>月々の利息 = 残高 × 年利 ÷ 12、1 円未満切捨て</li>
 *   <li>元利均等: 毎回の返済額 = P·r / (1 − (1+r)^−n)（r = 年利/12）、1 円未満切上げ。最終回で端数を調整</li>
 *   <li>元金均等: 元金 = P ÷ n（端数は初回に加える）、利息は残高に応じて逓減</li>
 *   <li>遅延損害金 = 延滞元利金 × 14.6% × 延滞日数 ÷ 365、1 円未満切捨て</li>
 * </ul>
 */
public final class LoanMath {
    /** 遅延損害金の年率（%） */
    public static final BigDecimal LATE_RATE = new BigDecimal("14.6");
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");
    // べき乗・割り算の途中計算は 30 桁で行い、丸めは最後の 1 回だけにする
    private static final MathContext MC = new MathContext(30, RoundingMode.HALF_EVEN);

    // 計算用の static メソッドだけを持つためインスタンス化させない
    private LoanMath() {}

    /** 1 回分の返済（元金・利息）。 */
    public static final class Installment {
        public final int no;
        public final BigDecimal principal;
        public final BigDecimal interest;
        public final BigDecimal balanceAfter;

        // @param no 回数（1 始まり） / principal 元金 / interest 利息 / balanceAfter 返済後残高
        Installment(int no, BigDecimal principal, BigDecimal interest, BigDecimal balanceAfter) {
            this.no = no;
            this.principal = principal;
            this.interest = interest;
            this.balanceAfter = balanceAfter;
        }

        /** @return その回の返済額（元金 + 利息） */
        public BigDecimal total() { return principal.add(interest); }
    }

    /** 月利息（残高 × 年利% ÷ 12、切捨て）。 */
    public static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal annualPercent) {
        return balance.multiply(annualPercent).divide(TWELVE_HUNDRED, 0, RoundingMode.DOWN);
    }

    /** 元利均等の毎回返済額（1 円未満切上げ）。 */
    public static BigDecimal equalPayment(BigDecimal principal, BigDecimal annualPercent, int n) {
        if (n <= 0) throw new IllegalArgumentException("n");
        if (annualPercent.signum() == 0) return principal.divide(BigDecimal.valueOf(n), 0, RoundingMode.CEILING);
        BigDecimal r = annualPercent.divide(TWELVE_HUNDRED, MC);
        BigDecimal pow = BigDecimal.ONE.add(r).pow(n, MC);
        // P·r·(1+r)^n / ((1+r)^n − 1)
        return principal.multiply(r, MC).multiply(pow, MC).divide(pow.subtract(BigDecimal.ONE), MC)
                .setScale(0, RoundingMode.CEILING);
    }

    /** 返済予定（元利均等 EQUAL_PAYMENT / 元金均等 EQUAL_PRINCIPAL）。 */
    public static List<Installment> schedule(BigDecimal principal, BigDecimal annualPercent, int n, String method) {
        List<Installment> list = new ArrayList<>();
        BigDecimal balance = principal;
        if ("EQUAL_PAYMENT".equals(method)) {
            BigDecimal payment = equalPayment(principal, annualPercent, n);
            for (int k = 1; k <= n; k++) {
                BigDecimal interest = monthlyInterest(balance, annualPercent);
                BigDecimal p = payment.subtract(interest);
                // 返済額を切り上げているため、最終回（または残高を超える回）は残高ちょうどにして過払いを防ぐ
                if (k == n || p.compareTo(balance) > 0) p = balance;
                if (p.signum() < 0) p = BigDecimal.ZERO;
                balance = balance.subtract(p);
                list.add(new Installment(k, p, interest, balance));
            }
        } else if ("EQUAL_PRINCIPAL".equals(method)) {
            BigDecimal base = principal.divide(BigDecimal.valueOf(n), 0, RoundingMode.DOWN);
            BigDecimal remainder = principal.subtract(base.multiply(BigDecimal.valueOf(n)));
            for (int k = 1; k <= n; k++) {
                BigDecimal interest = monthlyInterest(balance, annualPercent);
                BigDecimal p = k == 1 ? base.add(remainder) : base;
                // 最終回は端数を含めた残高ちょうどを返済して 0 にする
                if (k == n) p = balance;
                balance = balance.subtract(p);
                list.add(new Installment(k, p, interest, balance));
            }
        } else {
            throw new IllegalArgumentException("method " + method);
        }
        return list;
    }

    /** 遅延損害金。 */
    public static BigDecimal lateFee(BigDecimal overdueAmount, long days) {
        if (days <= 0 || overdueAmount.signum() <= 0) return BigDecimal.ZERO;
        return overdueAmount.multiply(LATE_RATE).multiply(BigDecimal.valueOf(days))
                // 36500 = 100（% を小数に）× 365 日
                .divide(new BigDecimal("36500"), 0, RoundingMode.DOWN);
    }

    /** 日割利息（カードローン・当座貸越の累積用、小数 2 桁で保持）。 */
    public static BigDecimal dailyInterest(BigDecimal balance, BigDecimal annualPercent, long days) {
        if (days <= 0 || balance.signum() <= 0) return BigDecimal.ZERO;
        return balance.multiply(annualPercent).multiply(BigDecimal.valueOf(days))
                // 毎日の累積で端数を失わないよう小数 2 桁まで保持し、請求時に円へ丸める
                .divide(new BigDecimal("36500"), 2, RoundingMode.DOWN);
    }
}
