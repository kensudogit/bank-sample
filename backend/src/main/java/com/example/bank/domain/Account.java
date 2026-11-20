package com.example.bank.domain;

import java.math.BigDecimal;

/** 口座。残高は円（整数）。JSON 出力用に日時は ISO-8601 文字列で保持する。 */
public final class Account {
    // 不変オブジェクト（全フィールド final）。更新は DB 側で行い、画面には再取得した値を返す。
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

    /**
     * 円口座（通貨 JPY・当座貸越なし）として生成する互換用コンストラクタ。
     *
     * @param accountNo          口座番号
     * @param customerId         顧客番号
     * @param customerName       名義人名
     * @param accountType        口座種別（普通・当座・定期など）
     * @param balance            残高（円）
     * @param status             状態（ACTIVE / DORMANT / CLOSED など）
     * @param updatedAt          最終更新日時
     * @param openedAt           開設日時
     * @param closedAt           解約日時（未解約は null）
     * @param lastActivityAt     最終異動日時
     * @param dormancyNotifiedAt 休眠前通知の日時（未通知は null）
     * @param dormantAt          休眠化した日時（休眠でなければ null）
     */
    public Account(String accountNo, String customerId, String customerName, String accountType,
                   BigDecimal balance, String status, String updatedAt, String openedAt, String closedAt,
                   String lastActivityAt, String dormancyNotifiedAt, String dormantAt) {
        this(accountNo, customerId, customerName, accountType, balance, status, updatedAt, openedAt, closedAt,
                lastActivityAt, dormancyNotifiedAt, dormantAt, "JPY", BigDecimal.ZERO);
    }

    /**
     * 通貨と当座貸越極度額を含めて生成する。
     *
     * @param currency       通貨コード（JPY / USD / EUR）
     * @param overdraftLimit 当座貸越の極度額（当座預金以外は 0）
     * 他の引数は 12 引数版のコンストラクタと同じ。
     */
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

    /** @return 口座番号 */
    public String getAccountNo() { return accountNo; }
    /** @return 名義人の顧客番号 */
    public String getCustomerId() { return customerId; }
    /** @return 名義人名 */
    public String getCustomerName() { return customerName; }
    /** @return 口座種別 */
    public String getAccountType() { return accountType; }
    /** @return 残高（通貨の最小単位。当座貸越中はマイナス） */
    public BigDecimal getBalance() { return balance; }
    /** @return 口座状態（ACTIVE / DORMANT / CLOSED など） */
    public String getStatus() { return status; }
    /** @return 最終更新日時（ISO-8601） */
    public String getUpdatedAt() { return updatedAt; }
    /** @return 開設日時（ISO-8601） */
    public String getOpenedAt() { return openedAt; }
    /** @return 解約日時（未解約は null） */
    public String getClosedAt() { return closedAt; }
    /** 最終異動日時（顧客による入出金・振込。利息の入金は含まない） */
    public String getLastActivityAt() { return lastActivityAt; }
    /** @return 休眠前通知を記録した日時（未通知は null） */
    public String getDormancyNotifiedAt() { return dormancyNotifiedAt; }
    /** @return 休眠化した日時（休眠でなければ null） */
    public String getDormantAt() { return dormantAt; }

    /** 通貨（JPY / USD / EUR）。残高・金額はその通貨の最小単位（円・セント） */
    public String getCurrency() { return currency; }
    /** 当座貸越の極度額（当座預金のみ。残高はこの額までマイナスにできる） */
    public BigDecimal getOverdraftLimit() { return overdraftLimit; }
    /** 引出可能額（当座貸越の未使用枠を含む） */
    public BigDecimal getAvailableBalance() { return balance.add(overdraftLimit); }

    /** @return 取引可能な状態（ACTIVE）なら true */
    public boolean isActive() { return "ACTIVE".equals(status); }
    /** @return 円口座なら true（外貨口座は両替経由でのみ円と資金移動できる） */
    public boolean isJpy() { return "JPY".equals(currency); }
}
