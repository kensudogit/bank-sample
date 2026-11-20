package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.NoticeRepository;
import com.example.bank.repository.OperationRequestRepository;
import com.example.bank.repository.ProductRepository;
import com.example.bank.repository.TransactionRepository;
import com.example.bank.support.BusinessCalendar;
import com.example.bank.support.DataSources;
import com.example.bank.support.Timestamps;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 預金商品: 定期預金（作成・満期・中途解約）、通知預金（払出し通知）、外貨預金（両替・為替レート）。
 *
 * <ul>
 *   <li>定期預金: 期間 3/6/12/36/60 か月、最低 10,000 円。利率は作成時点の店頭金利で固定（単利）。
 *       満期時は「元利金を指定口座へ」または「元利継続（自動継続）」。中途解約は普通預金金利で再計算</li>
 *   <li>通知預金: 最低 50,000 円。据置 7 日、払出しは 2 日前までの通知が必要</li>
 *   <li>外貨預金: 円口座との両替のみ（TTS で買い、TTB で売る）。同一名義の口座間に限る</li>
 * </ul>
 */
public class DepositProductService {
    public static final List<Integer> TIME_TERMS = Arrays.asList(3, 6, 12, 36, 60);
    public static final BigDecimal TIME_MIN = new BigDecimal("10000");
    public static final BigDecimal NOTICE_MIN = new BigDecimal("50000");
    public static final int NOTICE_DAYS = 2;
    /** 両替 1 回あたりの上限（円換算） */
    public static final BigDecimal FX_MAX_JPY = new BigDecimal("10000000");

    private static final DepositProductService DEFAULT =
            new DepositProductService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();
    private final NoticeRepository notices = new NoticeRepository();
    private final ProductRepository products = new ProductRepository();

    public DepositProductService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static DepositProductService getDefault() { return DEFAULT; }

    // ===================== time deposits =====================

    public List<Map<String, Object>> timeDepositRates() {
        return Transactions.readOnly(dataSource.get(), c -> products.timeDepositRates(c, LocalDate.now(clock)));
    }

    /**
     * 定期預金の作成: 資金口座（同じ名義の普通・貯蓄預金）から振り替えて新しい定期預金口座を作る。
     *
     * @param instruction PAYOUT（満期時に元利金を資金口座へ）/ ROLLOVER（元利継続）
     */
    public Map<String, Object> createTimeDeposit(String sourceAccountNo, BigDecimal amount, int termMonths,
                                                 String instruction, String idempotencyKey) {
        BankService.checkAmount(amount);
        if (amount.compareTo(TIME_MIN) < 0) throw new BankException(ErrorCode.MINIMUM_AMOUNT, "10,000");
        if (!TIME_TERMS.contains(termMonths)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (!"PAYOUT".equals(instruction) && !"ROLLOVER".equals(instruction)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            BankService.registerRequest(c, idempotencyKey, "TIME_OPEN", now, requests);
            Account source = BankService.lockActive(c, accounts, sourceAccountNo);
            if (!"ORDINARY".equals(source.getAccountType()) && !"SAVINGS".equals(source.getAccountType())) {
                throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, sourceAccountNo);
            }
            BigDecimal rate = products.timeDepositRate(c, termMonths, today);
            if (rate == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            BigDecimal sourceBalance = BankService.debit(source, amount);

            String accountNo = accounts.nextAccountNo(c);
            accounts.insert(c, accountNo, source.getCustomerId(), "TIME", "JPY", amount, now);
            LocalDate maturity = today.plusMonths(termMonths);
            products.insertTimeDeposit(c, accountNo, amount, rate, termMonths, today, maturity, instruction,
                    sourceAccountNo);
            accounts.updateBalance(c, sourceAccountNo, sourceBalance, now, true);
            transactions.insert(c, sourceAccountNo, TxType.TRANSFER_OUT, amount, sourceBalance, accountNo,
                    "定期預金へ", idempotencyKey, now);
            transactions.insert(c, accountNo, TxType.TRANSFER_IN, amount, amount, sourceAccountNo,
                    "定期預金 新規 " + termMonths + "か月", idempotencyKey, now);
            return timeDepositView(c, accountNo);
        });
    }

    public Map<String, Object> getTimeDeposit(String accountNo) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object> m = timeDepositView(c, accountNo);
            if (m == null) throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo);
            return m;
        });
    }

    /** 中途解約: 預入日から解約日までを普通預金金利で計算し、元利金を資金口座へ払い戻す。 */
    public Map<String, Object> cancelTimeDeposit(String accountNo, String idempotencyKey) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "TIME_CANCEL", now, requests);
            Map<String, Object> td = lockActiveTimeDeposit(c, accountNo);
            BigDecimal earlyRate = products.depositRate(c, "ORDINARY", now.toLocalDate());
            Map<String, Object> result = payout(c, accountNo, td, earlyRate, now.toLocalDate(), now, "中途解約");
            products.setTimeDepositStatus(c, accountNo, "CANCELLED");
            return result;
        });
    }

    /** 満期を迎えた定期預金を処理する（夜間バッチ）。口座ごとに別トランザクション。 */
    public List<Map<String, Object>> processMaturities(LocalDate asOf) {
        List<String> due = Transactions.readOnly(dataSource.get(), c -> products.dueTimeDeposits(c, asOf));
        List<Map<String, Object>> results = new ArrayList<>();
        for (String accountNo : due) {
            results.add(Transactions.inTransaction(dataSource.get(), c -> {
                LocalDateTime now = LocalDateTime.now(clock);
                Map<String, Object> td = products.lockTimeDeposit(c, accountNo);
                if (td == null || !"ACTIVE".equals(td.get("status"))) return null;
                LocalDate maturity = LocalDate.parse((String) td.get("maturityDate"));
                if ("ROLLOVER".equals(td.get("maturityInstruction"))) return rollover(c, accountNo, td, maturity, now);
                Map<String, Object> r = payout(c, accountNo, td, (BigDecimal) td.get("annualRate"), maturity, now, "満期");
                products.setTimeDepositStatus(c, accountNo, "MATURED");
                return r;
            }));
        }
        results.removeIf(r -> r == null);
        return results;
    }

    private Map<String, Object> lockActiveTimeDeposit(Connection c, String accountNo) throws SQLException {
        Account account = BankService.lockActive(c, accounts, accountNo);
        if (!"TIME".equals(account.getAccountType())) {
            throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, accountNo);
        }
        Map<String, Object> td = products.lockTimeDeposit(c, accountNo);
        if (td == null || !"ACTIVE".equals(td.get("status"))) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, accountNo);
        }
        return td;
    }

    /** 元利金を資金口座へ払い戻し、定期預金口座を解約する。 */
    private Map<String, Object> payout(Connection c, String accountNo, Map<String, Object> td, BigDecimal rate,
                                       LocalDate until, LocalDateTime now, String label) throws SQLException {
        BigDecimal principal = (BigDecimal) td.get("principal");
        LocalDate start = LocalDate.parse((String) td.get("startDate"));
        BigDecimal gross = DepositMath.simpleInterest(principal, rate, start, until);
        BigDecimal national = InterestCalculator.tax(gross, InterestCalculator.NATIONAL_TAX_RATE);
        BigDecimal local = InterestCalculator.tax(gross, InterestCalculator.LOCAL_TAX_RATE);
        BigDecimal net = gross.subtract(national).subtract(local);

        Account account = BankService.lockExisting(c, accounts, accountNo);
        String linked = (String) td.get("linkedAccountNo");
        Account target = BankService.lockExisting(c, accounts, linked);
        if (!target.isActive()) throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, linked);

        BigDecimal balance = account.getBalance();
        if (gross.signum() > 0) {
            balance = balance.add(gross);
            transactions.insert(c, accountNo, TxType.INTEREST, gross, balance, null,
                    label + "利息 " + start + "〜" + until + " " + rate.stripTrailingZeros().toPlainString() + "%", null, now);
            BigDecimal tax = national.add(local);
            if (tax.signum() > 0) {
                balance = balance.subtract(tax);
                transactions.insert(c, accountNo, TxType.TAX, tax, balance, null,
                        "国税 " + national + "円 地方税 " + local + "円", null, now);
            }
        }
        transactions.insert(c, accountNo, TxType.CLOSE_PAYOUT, balance, BigDecimal.ZERO, linked,
                "定期預金 " + label, null, now);
        BigDecimal targetBalance = target.getBalance().add(balance);
        accounts.updateBalance(c, linked, targetBalance, now, true);
        transactions.insert(c, linked, TxType.TRANSFER_IN, balance, targetBalance, accountNo,
                "定期預金 " + label + "（元利金）", null, now);
        accounts.close(c, accountNo, now);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountNo", accountNo);
        m.put("event", label);
        m.put("principal", principal);
        m.put("rate", rate);
        m.put("grossInterest", gross);
        m.put("nationalTax", national);
        m.put("localTax", local);
        m.put("payout", balance);
        m.put("payoutAccountNo", linked);
        return m;
    }

    /** 元利継続: 税引後利息を元本に加え、その日の金利で同じ期間を継続する。 */
    private Map<String, Object> rollover(Connection c, String accountNo, Map<String, Object> td, LocalDate maturity,
                                         LocalDateTime now) throws SQLException {
        BigDecimal principal = (BigDecimal) td.get("principal");
        BigDecimal rate = (BigDecimal) td.get("annualRate");
        int term = ((BigDecimal) td.get("termMonths")).intValue();
        LocalDate start = LocalDate.parse((String) td.get("startDate"));
        BigDecimal gross = DepositMath.simpleInterest(principal, rate, start, maturity);
        BigDecimal national = InterestCalculator.tax(gross, InterestCalculator.NATIONAL_TAX_RATE);
        BigDecimal local = InterestCalculator.tax(gross, InterestCalculator.LOCAL_TAX_RATE);
        Account account = BankService.lockExisting(c, accounts, accountNo);
        BigDecimal balance = account.getBalance();
        if (gross.signum() > 0) {
            balance = balance.add(gross);
            transactions.insert(c, accountNo, TxType.INTEREST, gross, balance, null,
                    "満期利息（元利継続） " + start + "〜" + maturity, null, now);
            BigDecimal tax = national.add(local);
            if (tax.signum() > 0) {
                balance = balance.subtract(tax);
                transactions.insert(c, accountNo, TxType.TAX, tax, balance, null,
                        "国税 " + national + "円 地方税 " + local + "円", null, now);
            }
            accounts.updateBalance(c, accountNo, balance, now, false);
        }
        BigDecimal newRate = products.timeDepositRate(c, term, maturity);
        if (newRate == null) newRate = rate;
        products.rolloverTimeDeposit(c, accountNo, balance, newRate, maturity, maturity.plusMonths(term));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountNo", accountNo);
        m.put("event", "元利継続");
        m.put("principal", balance);
        m.put("rate", newRate);
        m.put("grossInterest", gross);
        m.put("nationalTax", national);
        m.put("localTax", local);
        m.put("nextMaturity", maturity.plusMonths(term).toString());
        return m;
    }

    private Map<String, Object> timeDepositView(Connection c, String accountNo) throws SQLException {
        Map<String, Object> td = products.findTimeDeposit(c, accountNo);
        if (td == null) return null;
        // Expected interest at maturity (before tax) for display
        BigDecimal principal = (BigDecimal) td.get("principal");
        BigDecimal rate = (BigDecimal) td.get("annualRate");
        LocalDate start = LocalDate.parse((String) td.get("startDate"));
        LocalDate maturity = LocalDate.parse((String) td.get("maturityDate"));
        BigDecimal gross = DepositMath.simpleInterest(principal, rate, start, maturity);
        td.put("expectedGrossInterest", gross);
        td.put("expectedNetInterest", gross.subtract(InterestCalculator.tax(gross, InterestCalculator.NATIONAL_TAX_RATE))
                .subtract(InterestCalculator.tax(gross, InterestCalculator.LOCAL_TAX_RATE)));
        return td;
    }

    // ===================== notice deposits =====================

    /** 払出しの通知: 2 日後（休業日なら翌営業日）から払出し可能。 */
    public Map<String, Object> notifyWithdrawal(String accountNo, BigDecimal amount) {
        BankService.checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Account account = BankService.lockActive(c, accounts, accountNo);
            if (!"NOTICE".equals(account.getAccountType())) {
                throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, accountNo);
            }
            if (amount.compareTo(account.getBalance()) > 0) {
                throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, accountNo);
            }
            LocalDate opened = LocalDateTime.parse(account.getOpenedAt()).toLocalDate();
            LocalDate earliest = opened.plusDays(BankService.NOTICE_HOLD_DAYS);
            LocalDate available = now.toLocalDate().plusDays(NOTICE_DAYS);
            if (available.isBefore(earliest)) available = earliest;
            available = BusinessCalendar.onOrAfter(c, available);
            long id = notices.insert(c, accountNo, amount, now, available);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noticeId", id);
            m.put("accountNo", accountNo);
            m.put("amount", amount);
            m.put("availableFrom", available.toString());
            return m;
        });
    }

    public void cancelNotice(String accountNo, long noticeId) {
        Transactions.inTransaction(dataSource.get(), c -> {
            if (notices.cancel(c, accountNo, noticeId) != 1) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return null;
        });
    }

    public List<Map<String, Object>> listNotices(String accountNo) {
        return Transactions.readOnly(dataSource.get(), c -> notices.list(c, accountNo));
    }

    // ===================== foreign currency =====================

    public List<Map<String, Object>> fxRates() {
        return Transactions.readOnly(dataSource.get(), products::fxRates);
    }

    /** 為替レート（仲値・スプレッド）の更新（役席。公示相場の登録）。 */
    public List<Map<String, Object>> updateFxRate(String currency, BigDecimal ttm, BigDecimal spread, String by) {
        if (ttm == null || ttm.signum() <= 0 || spread == null || spread.signum() < 0 || spread.compareTo(ttm) >= 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            if (products.updateFxRate(c, currency, ttm, spread, by, LocalDateTime.now(clock)) != 1) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            return products.fxRates(c);
        });
    }

    /**
     * 両替（同じ名義の円口座 ⇔ 外貨預金口座）。
     *
     * @param amount 外貨額（"100.50" など。円→外貨・外貨→円とも外貨建てで指定）
     */
    public Map<String, Object> exchange(String fromAccountNo, String toAccountNo, String amount, String idempotencyKey) {
        if (fromAccountNo.equals(toAccountNo)) throw new BankException(ErrorCode.SAME_ACCOUNT);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "FX", now, requests);
            boolean fromFirst = fromAccountNo.compareTo(toAccountNo) < 0;
            Account first = BankService.lockActive(c, accounts, fromFirst ? fromAccountNo : toAccountNo);
            Account second = BankService.lockActive(c, accounts, fromFirst ? toAccountNo : fromAccountNo);
            Account from = fromFirst ? first : second;
            Account to = fromFirst ? second : first;
            if (!from.getCustomerId().equals(to.getCustomerId())) throw new BankException(ErrorCode.NOT_OWN_ACCOUNT, toAccountNo);

            boolean buying = from.isJpy() && "FOREIGN".equals(to.getAccountType());
            boolean selling = "FOREIGN".equals(from.getAccountType()) && to.isJpy();
            if (!buying && !selling) throw new BankException(ErrorCode.CURRENCY_MISMATCH, toAccountNo);
            Account yenSide = buying ? from : to;
            BankService.requireYenTransactional(yenSide);
            String currency = buying ? to.getCurrency() : from.getCurrency();
            BigDecimal[] rate = products.fxRate(c, currency);
            if (rate == null) throw new BankException(ErrorCode.CURRENCY_MISMATCH, currency);

            BigDecimal minor;
            try {
                minor = DepositMath.toMinor(amount, currency);
            } catch (IllegalArgumentException e) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            if (minor.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
            BigDecimal applied = buying ? rate[0].add(rate[1]) : rate[0].subtract(rate[1]);
            BigDecimal yen = buying ? DepositMath.yenToBuy(minor, currency, applied)
                                    : DepositMath.yenFromSell(minor, currency, applied);
            if (yen.compareTo(FX_MAX_JPY) > 0) throw new BankException(ErrorCode.LIMIT_EXCEEDED);
            if (yen.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);

            BigDecimal outAmount = buying ? yen : minor;
            BigDecimal inAmount = buying ? minor : yen;
            BigDecimal fromBalance = BankService.debit(from, outAmount);
            BigDecimal toBalance = to.getBalance().add(inAmount);
            String memo = currency + " " + DepositMath.fromMinor(minor, currency).toPlainString() + " @"
                    + applied.stripTrailingZeros().toPlainString() + (buying ? " (TTS)" : " (TTB)");
            accounts.updateBalance(c, fromAccountNo, fromBalance, now, true);
            accounts.updateBalance(c, toAccountNo, toBalance, now, true);
            transactions.insert(c, fromAccountNo, TxType.FX_OUT, outAmount, fromBalance, toAccountNo, memo, idempotencyKey, now);
            transactions.insert(c, toAccountNo, TxType.FX_IN, inAmount, toBalance, fromAccountNo, memo, idempotencyKey, now);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("direction", buying ? "BUY" : "SELL");
            m.put("currency", currency);
            m.put("foreignAmount", DepositMath.fromMinor(minor, currency));
            m.put("yenAmount", yen);
            m.put("rate", applied);
            m.put("executedAt", Timestamps.format(now));
            return m;
        });
    }
}
