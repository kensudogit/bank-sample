package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.InterestResult;
import com.example.bank.domain.SettlementSummary;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.InterestRepository;
import com.example.bank.repository.TransactionRepository;
import com.example.bank.service.InterestCalculator.Calculation;
import com.example.bank.support.DataAccessException;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 利息計算・税金計算と利息決算。
 * <ul>
 *   <li>計算期間: 前回決算期間の翌日（初回は開設日）〜 決算日の前日</li>
 *   <li>利息・税の計算方法は {@link InterestCalculator}</li>
 *   <li>決算: 利息（税引前）と源泉税を明細に記帳し、決算記録を残す。利息の入金は「最終異動」に当たらない</li>
 * </ul>
 */
public class InterestService {
    private static final InterestService DEFAULT = new InterestService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final InterestRepository interest = new InterestRepository();

    public InterestService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static InterestService getDefault() { return DEFAULT; }

    /** 利息の見込み計算（記帳しない）。基準日を未来にすると現在の残高が続く前提の見込みになる。 */
    public InterestResult preview(String accountNo, LocalDate asOf) {
        LocalDate baseDate = asOf == null ? LocalDate.now(clock) : asOf;
        return Transactions.readOnly(dataSource.get(), c -> {
            Account account = accounts.findById(c, accountNo)
                    .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo));
            return compute(c, account, baseDate).toResult(false, "見込み（未決算）");
        });
    }

    /** 1 口座の利息決算。 */
    public InterestResult settle(String accountNo, LocalDate asOf) {
        LocalDate baseDate = checkBaseDate(asOf);
        return Transactions.inTransaction(dataSource.get(), c -> {
            Account account = BankService.lockExisting(c, accounts, accountNo);
            return settleLocked(c, account, baseDate, LocalDateTime.now(clock));
        });
    }

    /** 解約済み以外の全口座の利息決算。口座ごとに別トランザクション（1 口座の失敗で全体を止めない）。 */
    public SettlementSummary settleAll(LocalDate asOf) {
        LocalDate baseDate = checkBaseDate(asOf);
        List<String> accountNos = Transactions.readOnly(dataSource.get(), accounts::findOpenAccountNos);
        List<InterestResult> results = new ArrayList<>();
        for (String accountNo : accountNos) {
            try {
                results.add(settle(accountNo, baseDate));
            } catch (BankException | DataAccessException e) {
                results.add(new InterestResult(accountNo, null, null, null, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, false, "エラー: " + e.getMessage()));
            }
        }
        return new SettlementSummary(baseDate.toString(), results);
    }

    /**
     * 行ロック済みの口座を決算する（解約処理からも呼ばれる）。
     * 決算日の前日までを計算し、利息があれば INTEREST / TAX を記帳する。
     */
    InterestResult settleLocked(Connection c, Account account, LocalDate asOf, LocalDateTime now) throws SQLException {
        if ("CLOSED".equals(account.getStatus())) {
            return Period.empty(account).toResult(false, "解約済み");
        }
        Period p = compute(c, account, asOf);
        if (p.calc.days == 0) return p.toResult(false, "決算済み（対象期間なし）");

        interest.insertSettlement(c, account.getAccountNo(), p.from, p.to, p.calc, now);
        BigDecimal gross = p.calc.grossInterest;
        if (gross.signum() > 0) {
            BigDecimal balance = account.getBalance().add(gross);
            transactions.insert(c, account.getAccountNo(), TxType.INTEREST, gross, balance, null,
                    "利息 " + p.from + "〜" + p.to, null, now);
            BigDecimal tax = p.calc.nationalTax.add(p.calc.localTax);
            if (tax.signum() > 0) {
                balance = balance.subtract(tax);
                transactions.insert(c, account.getAccountNo(), TxType.TAX, tax, balance, null,
                        "国税 " + yen(p.calc.nationalTax) + " 地方税 " + yen(p.calc.localTax), null, now);
            }
            accounts.updateBalance(c, account.getAccountNo(), balance, now, false);
        }
        return p.toResult(true, gross.signum() > 0 ? "決算済み" : "決算済み（利息 0 円）");
    }

    private Period compute(Connection c, Account account, LocalDate asOf) throws SQLException {
        if ("CLOSED".equals(account.getStatus())) return Period.empty(account);
        String accountNo = account.getAccountNo();
        LocalDate lastTo = interest.lastSettledTo(c, accountNo);
        LocalDate from = lastTo != null ? lastTo.plusDays(1) : LocalDateTime.parse(account.getOpenedAt()).toLocalDate();
        LocalDate to = asOf.minusDays(1);
        if (to.isBefore(from)) return new Period(account, from, to, InterestCalculator.calculate(
                BigDecimal.ZERO, new ArrayList<InterestCalculator.BalanceChange>(),
                new ArrayList<InterestCalculator.Rate>(), from, to));

        BigDecimal opening = transactions.balanceBefore(c, accountNo, from.atStartOfDay());
        Calculation calc = InterestCalculator.calculate(opening,
                transactions.balanceChanges(c, accountNo, from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                interest.rates(c, account.getAccountType()), from, to);
        return new Period(account, from, to, calc);
    }

    /** 決算日は本日以前（未来日の決算は不可）。null は本日。 */
    private LocalDate checkBaseDate(LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        if (asOf == null) return today;
        if (asOf.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        return asOf;
    }

    private static String yen(BigDecimal amount) {
        return NumberFormat.getIntegerInstance(Locale.JAPAN).format(amount) + "円";
    }

    /** 計算期間と結果。 */
    private static final class Period {
        final Account account;
        final LocalDate from;
        final LocalDate to;
        final Calculation calc;

        Period(Account account, LocalDate from, LocalDate to, Calculation calc) {
            this.account = account;
            this.from = from;
            this.to = to;
            this.calc = calc;
        }

        static Period empty(Account account) {
            LocalDate d = LocalDate.of(2000, 1, 1);
            return new Period(account, null, null, InterestCalculator.calculate(BigDecimal.ZERO,
                    new ArrayList<InterestCalculator.BalanceChange>(), new ArrayList<InterestCalculator.Rate>(),
                    d, d.minusDays(1)));
        }

        InterestResult toResult(boolean settled, String note) {
            boolean hasPeriod = calc.days > 0;
            return new InterestResult(account.getAccountNo(), account.getAccountType(),
                    hasPeriod ? from.toString() : null, hasPeriod ? to.toString() : null, calc.days,
                    calc.balanceDays, calc.grossInterest, calc.nationalTax, calc.localTax, settled, note);
        }
    }
}
