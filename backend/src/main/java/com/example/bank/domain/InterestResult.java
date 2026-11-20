package com.example.bank.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 利息計算（見込み）または利息決算の結果。 */
public final class InterestResult {
    private final String accountNo;
    private final String accountType;
    private final String periodFrom;
    private final String periodTo;
    private final int days;
    private final BigDecimal balanceDays;
    private final BigDecimal grossInterest;
    private final BigDecimal nationalTax;
    private final BigDecimal localTax;
    private final boolean settled;
    private final String note;

    public InterestResult(String accountNo, String accountType, String periodFrom, String periodTo, int days,
                          BigDecimal balanceDays, BigDecimal grossInterest, BigDecimal nationalTax,
                          BigDecimal localTax, boolean settled, String note) {
        this.accountNo = accountNo;
        this.accountType = accountType;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.days = days;
        this.balanceDays = balanceDays;
        this.grossInterest = grossInterest;
        this.nationalTax = nationalTax;
        this.localTax = localTax;
        this.settled = settled;
        this.note = note;
    }

    /** 同じ計算結果を「決算済み」として返す。 */
    public InterestResult asSettled() {
        return new InterestResult(accountNo, accountType, periodFrom, periodTo, days, balanceDays, grossInterest,
                nationalTax, localTax, true, note);
    }

    public String getAccountNo() { return accountNo; }
    public String getAccountType() { return accountType; }
    /** 計算期間の初日（null は計算対象期間なし） */
    public String getPeriodFrom() { return periodFrom; }
    /** 計算期間の末日（この日の最終残高まで含む） */
    public String getPeriodTo() { return periodTo; }
    public int getDays() { return days; }
    /** 積数（日々の最終残高の合計） */
    public BigDecimal getBalanceDays() { return balanceDays; }
    public BigDecimal getAverageBalance() {
        return days == 0 ? BigDecimal.ZERO : balanceDays.divide(BigDecimal.valueOf(days), 0, RoundingMode.DOWN);
    }
    /** 税引前利息 */
    public BigDecimal getGrossInterest() { return grossInterest; }
    /** 国税（所得税 15% + 復興特別所得税 0.315%） */
    public BigDecimal getNationalTax() { return nationalTax; }
    /** 地方税（住民税利子割 5%） */
    public BigDecimal getLocalTax() { return localTax; }
    public BigDecimal getTax() { return nationalTax.add(localTax); }
    /** 税引後利息（口座への入金額） */
    public BigDecimal getNetInterest() { return grossInterest.subtract(getTax()); }
    /** true: 決算済み（口座に入金済み）、false: 見込み計算のみ／対象外 */
    public boolean isSettled() { return settled; }
    public String getNote() { return note; }
}
