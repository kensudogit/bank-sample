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

    public long getTxId() { return txId; }
    public String getAccountNo() { return accountNo; }
    public String getTxType() { return txType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getCounterpartyAccountNo() { return counterpartyAccountNo; }
    public String getMemo() { return memo; }
    public String getCreatedAt() { return createdAt; }
}
