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

    /**
     * @param operation             操作名（DEPOSIT / WITHDRAWAL / TRANSFER など）
     * @param accountNo             操作口座
     * @param counterpartyAccountNo 相手口座（振込以外は null）
     * @param amount                金額
     * @param balanceAfter          操作口座の取引後残高
     * @param executedAt            実行日時
     */
    public OperationResult(String operation, String accountNo, String counterpartyAccountNo,
                           BigDecimal amount, BigDecimal balanceAfter, String executedAt) {
        this.operation = operation;
        this.accountNo = accountNo;
        this.counterpartyAccountNo = counterpartyAccountNo;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.executedAt = executedAt;
    }

    /** @return 操作名 */
    public String getOperation() { return operation; }
    /** @return 操作口座 */
    public String getAccountNo() { return accountNo; }
    /** @return 相手口座 */
    public String getCounterpartyAccountNo() { return counterpartyAccountNo; }
    /** @return 金額 */
    public BigDecimal getAmount() { return amount; }
    /** 操作口座（振込の場合は振込元）の取引後残高 */
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    /** @return 実行日時 */
    public String getExecutedAt() { return executedAt; }
}
