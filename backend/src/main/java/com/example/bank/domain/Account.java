package com.example.bank.domain;

import java.math.BigDecimal;

/** 口座。残高は円（整数）。JSON 出力用に日時は ISO-8601 文字列で保持する。 */
public final class Account {
    private final String accountNo;
    private final String customerId;
    private final String customerName;
    private final String accountType;
    private final BigDecimal balance;
    private final String status;
    private final String updatedAt;
    private final String openedAt;
    private final String closedAt;
    private final String lastActivityAt;
    private final String dormancyNotifiedAt;
    private final String dormantAt;
    private final String currency;
    private final BigDecimal overdraftLimit;

    public Account(String accountNo, String customerId, String customerName, String accountType,
                   BigDecimal balance, String status, String updatedAt, String openedAt, String closedAt,
                   String lastActivityAt, String dormancyNotifiedAt, String dormantAt) {
        this(accountNo, customerId, customerName, accountType, balance, status, updatedAt, openedAt, closedAt,
                lastActivityAt, dormancyNotifiedAt, dormantAt, "JPY", BigDecimal.ZERO);
    }

    public Account(String accountNo, String customerId, String customerName, String accountType,
                   BigDecimal balance, String status, String updatedAt, String openedAt, String closedAt,
                   String lastActivityAt, String dormancyNotifiedAt, String dormantAt, String currency,
                   BigDecimal overdraftLimit) {
        this.accountNo = accountNo;
        this.customerId = customerId;
        this.customerName = customerName;
        this.accountType = accountType;
        this.balance = balance;
        this.status = status;
        this.updatedAt = updatedAt;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.lastActivityAt = lastActivityAt;
        this.dormancyNotifiedAt = dormancyNotifiedAt;
        this.dormantAt = dormantAt;
        this.currency = currency;
        this.overdraftLimit = overdraftLimit;
    }

    public String getAccountNo() { return accountNo; }
    public String getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getAccountType() { return accountType; }
    public BigDecimal getBalance() { return balance; }
    public String getStatus() { return status; }
    public String getUpdatedAt() { return updatedAt; }
    public String getOpenedAt() { return openedAt; }
    public String getClosedAt() { return closedAt; }
    /** 最終異動日時（顧客による入出金・振込。利息の入金は含まない） */
    public String getLastActivityAt() { return lastActivityAt; }
    public String getDormancyNotifiedAt() { return dormancyNotifiedAt; }
    public String getDormantAt() { return dormantAt; }

    /** 通貨（JPY / USD / EUR）。残高・金額はその通貨の最小単位（円・セント） */
    public String getCurrency() { return currency; }
    /** 当座貸越の極度額（当座預金のみ。残高はこの額までマイナスにできる） */
    public BigDecimal getOverdraftLimit() { return overdraftLimit; }
    /** 引出可能額（当座貸越の未使用枠を含む） */
    public BigDecimal getAvailableBalance() { return balance.add(overdraftLimit); }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean isJpy() { return "JPY".equals(currency); }
}
