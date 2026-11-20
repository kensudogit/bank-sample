package com.example.bank.domain;

import java.math.BigDecimal;
import java.util.List;

/** 取引履歴の検索結果（ページ）と期間の集計。 */
public final class TransactionPage {
    private final List<AccountTransaction> items;
    private final int page;
    private final int size;
    private final long total;
    private final BigDecimal openingBalance;
    private final BigDecimal closingBalance;
    private final BigDecimal totalIn;
    private final BigDecimal totalOut;

    public TransactionPage(List<AccountTransaction> items, int page, int size, long total, BigDecimal openingBalance,
                           BigDecimal closingBalance, BigDecimal totalIn, BigDecimal totalOut) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.total = total;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.totalIn = totalIn;
        this.totalOut = totalOut;
    }

    public List<AccountTransaction> getItems() { return items; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotal() { return total; }
    public boolean isHasNext() { return (long) page * size < total; }
    /** 期間開始時点の残高（開始日指定なしは 0） */
    public BigDecimal getOpeningBalance() { return openingBalance; }
    /** 期間終了時点の残高 */
    public BigDecimal getClosingBalance() { return closingBalance; }
    /** 期間内（取引種別の絞り込み後）の入金合計 */
    public BigDecimal getTotalIn() { return totalIn; }
    /** 期間内（取引種別の絞り込み後）の出金合計 */
    public BigDecimal getTotalOut() { return totalOut; }
}
