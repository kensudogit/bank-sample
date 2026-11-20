package com.example.bank.domain;

import java.math.BigDecimal;

/** 入金・出金・振込の結果。 */
public final class OperationResult {
    private final String operation;
    private final String accountNo;
    private final String counterpartyAccountNo;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;
    private final String executedAt;

    public OperationResult(String operation, String accountNo, String counterpartyAccountNo,
                           BigDecimal amount, BigDecimal balanceAfter, String executedAt) {
        this.operation = operation;
        this.accountNo = accountNo;
        this.counterpartyAccountNo = counterpartyAccountNo;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.executedAt = executedAt;
    }

    public String getOperation() { return operation; }
    public String getAccountNo() { return accountNo; }
    public String getCounterpartyAccountNo() { return counterpartyAccountNo; }
    public BigDecimal getAmount() { return amount; }
    /** 操作口座（振込の場合は振込元）の取引後残高 */
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getExecutedAt() { return executedAt; }
}
