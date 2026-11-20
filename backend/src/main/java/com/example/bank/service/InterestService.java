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
        if ("TIME".equals(account.getAccountType())) {
            return Period.empty(account).toResult(false, "定期預金は満期時（または中途解約時）に利息を計算");
        }
        Period p = compute(c, account, asOf);
        if (p.calc.days == 0) return p.toResult(false, "決算済み（対象期間なし）");
        String ccy = account.getCurrency();

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
                        "国税 " + money(p.calc.nationalTax, ccy) + " 地方税 " + money(p.calc.localTax, ccy), null, now);
            }
            accounts.updateBalance(c, account.getAccountNo(), balance, now, false);
        }
        if (p.overdraft != null && p.overdraft.grossInterest.signum() > 0) {
            // 当座貸越利息: 貸越残高（マイナス残高）の日々の合計 × 貸越利率 ÷ 365。極度額の範囲で徴収する
            Account current = BankService.lockExisting(c, accounts, account.getAccountNo());
            BigDecimal headroom = current.getBalance().add(current.getOverdraftLimit()).max(BigDecimal.ZERO);
            BigDecimal charge = p.overdraft.grossInterest.min(headroom);
            if (charge.signum() > 0) {
                BigDecimal balance = current.getBalance().subtract(charge);
                transactions.insert(c, account.getAccountNo(), TxType.OD_INTEREST, charge, balance, null,
                        "当座貸越利息 " + p.from + "〜" + p.to, null, now);
                accounts.updateBalance(c, account.getAccountNo(), balance, now, false);
            }
            return p.toResult(true, "決算済み（当座貸越利息 " + money(charge, ccy) + " を徴収"
                    + (charge.compareTo(p.overdraft.grossInterest) < 0 ? "・極度額超過分は未収" : "") + "）");
        }
        if ("CHECKING".equals(account.getAccountType())) return p.toResult(true, "決算済み（当座預金は無利息）");
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
        List<InterestCalculator.BalanceChange> changes =
                transactions.balanceChanges(c, accountNo, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        if ("CHECKING".equals(account.getAccountType())) {
            // No interest on checking accounts; overdraft interest on the negative part of the balance
            Calculation none = InterestCalculator.calculate(opening.max(BigDecimal.ZERO), changes,
                    new ArrayList<InterestCalculator.Rate>(), from, to);
            Period p = new Period(account, from, to, none);
            BigDecimal odRate = overdraftRate(c, accountNo);
            if (odRate.signum() > 0) {
                List<InterestCalculator.BalanceChange> negative = new ArrayList<>();
                for (InterestCalculator.BalanceChange ch : changes) {
                    negative.add(new InterestCalculator.BalanceChange(ch.at, ch.balanceAfter.negate().max(BigDecimal.ZERO)));
                }
                p.overdraft = InterestCalculator.calculate(opening.negate().max(BigDecimal.ZERO), negative,
                        java.util.Collections.singletonList(new InterestCalculator.Rate(from, odRate)), from, to);
            }
            return p;
        }
        Calculation calc = InterestCalculator.calculate(opening, changes, interest.rates(c, rateKey(account)), from, to);
        return new Period(account, from, to, calc);
    }

    /** 決算日は本日以前（未来日の決算は不可）。null は本日。 */
    private LocalDate checkBaseDate(LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        if (asOf == null) return today;
        if (asOf.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        return asOf;
    }

    /** 金利表のキー（外貨預金は FX_<通貨>）。 */
    static String rateKey(Account account) {
        return "FOREIGN".equals(account.getAccountType()) ? "FX_" + account.getCurrency() : account.getAccountType();
    }

    /** 当座貸越契約（実行中）の貸越利率。契約がなければ 0。 */
    private static BigDecimal overdraftRate(Connection c, String accountNo) throws SQLException {
        List<java.util.Map<String, Object>> rows = com.example.bank.repository.ProductRepository.query(c,
                "SELECT annual_rate FROM loan WHERE product = 'OVERDRAFT' AND repayment_account_no = ? "
                + "AND status IN ('ACTIVE', 'DELINQUENT')", accountNo);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.get(0).get("annualRate");
    }

    /** 金額表示（円は「1,234円」、外貨は「12.34 USD」）。金額は最小単位（円・セント）。 */
    static String money(BigDecimal minor, String currency) {
        if (currency == null || "JPY".equals(currency)) {
            return NumberFormat.getIntegerInstance(Locale.JAPAN).format(minor) + "円";
        }
        return DepositMath.fromMinor(minor, currency).toPlainString() + " " + currency;
    }

    /** 計算期間と結果。 */
    private static final class Period {
        final Account account;
        final LocalDate from;
        final LocalDate to;
        final Calculation calc;
        /** 当座預金: 当座貸越利息の計算（貸越なし・契約なしは null） */
        Calculation overdraft;

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
