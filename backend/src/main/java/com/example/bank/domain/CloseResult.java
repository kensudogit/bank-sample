package com.example.bank.domain;

import java.math.BigDecimal;

/** 口座解約の結果。 */
public final class CloseResult {
    private final String accountNo;
    private final InterestResult interest;
    private final BigDecimal payoutAmount;
    private final String payoutAccountNo;
    private final String closedAt;

    /**
     * @param accountNo       解約した口座
     * @param interest        解約時の利息決算
     * @param payoutAmount    払戻し額
     * @param payoutAccountNo 払戻し先口座（現金払戻しは null）
     * @param closedAt        解約日時
     */
    public CloseResult(String accountNo, InterestResult interest, BigDecimal payoutAmount, String payoutAccountNo,
                       String closedAt) {
        this.accountNo = accountNo;
        this.interest = interest;
        this.payoutAmount = payoutAmount;
        this.payoutAccountNo = payoutAccountNo;
        this.closedAt = closedAt;
    }

    /** @return 解約した口座番号 */
    public String getAccountNo() { return accountNo; }
    /** 解約日前日までの利息決算 */
    public InterestResult getInterest() { return interest; }
    /** 払戻し額（利息入金後の全残高） */
    public BigDecimal getPayoutAmount() { return payoutAmount; }
    /** 払戻し先口座（null は現金払戻し） */
    public String getPayoutAccountNo() { return payoutAccountNo; }
    /** @return 解約日時 */
    public String getClosedAt() { return closedAt; }
}
