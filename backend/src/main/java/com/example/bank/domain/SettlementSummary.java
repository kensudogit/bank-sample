package com.example.bank.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** 利息一括決算の結果。 */
public final class SettlementSummary {
    private final String asOf;
    private final List<InterestResult> results;

    /**
     * @param asOf    決算日
     * @param results 口座ごとの決算結果（対象外も含む）
     */
    public SettlementSummary(String asOf, List<InterestResult> results) {
        this.asOf = asOf;
        this.results = results;
    }

    /** 決算日（前日分までの利息を計算する） */
    public String getAsOf() { return asOf; }
    /** @return 口座ごとの結果 */
    public List<InterestResult> getResults() { return results; }
    /** @return 決算（入金）した件数 */
    public long getSettledCount() { return results.stream().filter(InterestResult::isSettled).count(); }
    /** @return 対象外として入金しなかった件数 */
    public long getSkippedCount() { return results.size() - getSettledCount(); }
    /** @return 決算済み分の税引前利息合計 */
    public BigDecimal getTotalGross() { return sum(InterestResult::getGrossInterest); }
    /** @return 決算済み分の源泉税合計 */
    public BigDecimal getTotalTax() { return sum(InterestResult::getTax); }
    /** @return 決算済み分の税引後利息合計 */
    public BigDecimal getTotalNet() { return sum(InterestResult::getNetInterest); }

    // 見込みだけの行を合計に含めないよう、決算済みの結果だけを集計する
    private BigDecimal sum(Function<InterestResult, BigDecimal> f) {
        return results.stream().filter(InterestResult::isSettled).map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
