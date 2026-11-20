package com.example.bank.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** 利息一括決算の結果。 */
public final class SettlementSummary {
    private final String asOf;
    private final List<InterestResult> results;

    public SettlementSummary(String asOf, List<InterestResult> results) {
        this.asOf = asOf;
        this.results = results;
    }

    /** 決算日（前日分までの利息を計算する） */
    public String getAsOf() { return asOf; }
    public List<InterestResult> getResults() { return results; }
    public long getSettledCount() { return results.stream().filter(InterestResult::isSettled).count(); }
    public long getSkippedCount() { return results.size() - getSettledCount(); }
    public BigDecimal getTotalGross() { return sum(InterestResult::getGrossInterest); }
    public BigDecimal getTotalTax() { return sum(InterestResult::getTax); }
    public BigDecimal getTotalNet() { return sum(InterestResult::getNetInterest); }

    private BigDecimal sum(Function<InterestResult, BigDecimal> f) {
        return results.stream().filter(InterestResult::isSettled).map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
