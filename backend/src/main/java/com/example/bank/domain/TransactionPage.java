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

    /**
     * @param items          このページの明細
     * @param page           ページ番号（1 始まり）
     * @param size           1 ページの件数
     * @param total          条件に合う全件数
     * @param openingBalance 期間開始時点の残高
     * @param closingBalance 期間終了時点の残高
     * @param totalIn        入金合計
     * @param totalOut       出金合計
     */
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

    /** @return このページの明細 */
    public List<AccountTransaction> getItems() { return items; }
    /** @return ページ番号（1 始まり） */
    public int getPage() { return page; }
    /** @return 1 ページの件数 */
    public int getSize() { return size; }
    /** @return 全件数 */
    public long getTotal() { return total; }
    /** @return 次のページがあれば true（JSON で hasNext として出力するため is + HasNext の名前にしている） */
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
