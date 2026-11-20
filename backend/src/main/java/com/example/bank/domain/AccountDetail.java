package com.example.bank.domain;

import java.util.List;

/** 口座詳細と直近の明細（1 接続・1 往復分の照会結果）。 */
public final class AccountDetail {
    private final Account account;
    private final List<AccountTransaction> transactions;

    /**
     * @param account      口座
     * @param transactions 直近の明細（新しい順）
     */
    public AccountDetail(Account account, List<AccountTransaction> transactions) {
        this.account = account;
        this.transactions = transactions;
    }

    /** @return 口座 */
    public Account getAccount() { return account; }
    /** @return 直近の明細 */
    public List<AccountTransaction> getTransactions() { return transactions; }
}
