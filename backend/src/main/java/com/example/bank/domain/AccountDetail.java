package com.example.bank.domain;

import java.util.List;

/** 口座詳細と直近の明細（1 接続・1 往復分の照会結果）。 */
public final class AccountDetail {
    private final Account account;
    private final List<AccountTransaction> transactions;

    public AccountDetail(Account account, List<AccountTransaction> transactions) {
        this.account = account;
        this.transactions = transactions;
    }

    public Account getAccount() { return account; }
    public List<AccountTransaction> getTransactions() { return transactions; }
}
