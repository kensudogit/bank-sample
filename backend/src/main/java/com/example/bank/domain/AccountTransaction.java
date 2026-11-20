package com.example.bank.domain;

import java.math.BigDecimal;

/** 入出金・振込明細。 */
public final class AccountTransaction {
    private final long txId;
    private final String accountNo;
    private final String txType;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;
    private final String counterpartyAccountNo;
    private final String memo;
    private final String createdAt;

    /**
     * @param txId                  明細 ID（採番順 = 記帳順）
     * @param accountNo             口座番号
     * @param txType                取引種別（{@link TxType} の名前）
     * @param amount                取引金額（常に正。入出金の向きは txType で表す）
     * @param balanceAfter          取引後残高
     * @param counterpartyAccountNo 相手口座（振込のときのみ）
     * @param memo                  摘要
     * @param createdAt             記帳日時（ISO-8601）
     */
    public AccountTransaction(long txId, String accountNo, String txType, BigDecimal amount, BigDecimal balanceAfter,
                              String counterpartyAccountNo, String memo, String createdAt) {
        this.txId = txId;
        this.accountNo = accountNo;
        this.txType = txType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.counterpartyAccountNo = counterpartyAccountNo;
        this.memo = memo;
        this.createdAt = createdAt;
    }

    /** @return 明細 ID */
    public long getTxId() { return txId; }
    /** @return 口座番号 */
    public String getAccountNo() { return accountNo; }
    /** @return 取引種別 */
    public String getTxType() { return txType; }
    /** @return 取引金額（正の値） */
    public BigDecimal getAmount() { return amount; }
    /** @return 取引後残高 */
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    /** @return 相手口座番号（振込以外は null） */
    public String getCounterpartyAccountNo() { return counterpartyAccountNo; }
    /** @return 摘要 */
    public String getMemo() { return memo; }
    /** @return 記帳日時 */
    public String getCreatedAt() { return createdAt; }
}
