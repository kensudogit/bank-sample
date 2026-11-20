package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountDetail;
import com.example.bank.domain.AccountTransaction;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.OperationResult;
import com.example.bank.domain.TransactionPage;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.NoticeRepository;
import com.example.bank.repository.OperationRequestRepository;
import com.example.bank.repository.TransactionRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 口座照会・取引履歴・入金・出金・振込／振替。
 * 更新系は 1 トランザクションで「冪等キー登録 → 口座行ロック → 残高検証 → 残高更新 → 明細登録」を行う。
 */
public class BankService {
    /** 1 回あたりの取引上限（円） */
    public static final BigDecimal MAX_AMOUNT_PER_OPERATION = new BigDecimal("10000000");
    public static final int HISTORY_LIMIT = 50;
    public static final int MAX_PAGE_SIZE = 200;
    /** 1 口座 1 日あたりの出金・振込（出）の合計上限（円） */
    public static final BigDecimal DAILY_OUTFLOW_LIMIT = new BigDecimal("20000000");
    /** 通知預金の据置期間（日） */
    public static final int NOTICE_HOLD_DAYS = 7;

    private static final BankService DEFAULT = new BankService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();
    private final NoticeRepository notices = new NoticeRepository();

    public BankService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** Struts Action 用の既定インスタンス（Liberty の jdbc/bankDS を使用）。 */
    public static BankService getDefault() { return DEFAULT; }

    /** DB 接続確認。 */
    public boolean ping() {
        try {
            return Transactions.readOnly(dataSource.get(), c -> c.isValid(5));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<Account> listAccounts() {
        return Transactions.readOnly(dataSource.get(), accounts::findAll);
    }

    /**
     * 口座一覧の検索（ページ）。
     *
     * @param customerScope 指定時はその顧客の口座のみ（顧客ログイン時は本人の顧客番号）
     * @param q             口座番号の前方一致・顧客番号・氏名／カナの部分一致（null は全件）
     */
    public Map<String, Object> searchAccounts(String customerScope, String q, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.readOnly(dataSource.get(), c -> {
            AccountRepository.Summary summary = accounts.summarize(c, customerScope, q);
            List<Account> items = accounts.search(c, customerScope, q, (page - 1) * size, size);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items);
            m.put("page", page);
            m.put("size", size);
            m.put("total", summary.count);
            m.put("hasNext", (long) page * size < summary.count);
            m.put("totalBalance", summary.totalBalance);
            return m;
        });
    }

    /** 口座の名義人（顧客番号）。アクセス権の確認用。 */
    public String accountOwner(String accountNo) {
        return getAccount(accountNo).getCustomerId();
    }

    public Account getAccount(String accountNo) {
        return Transactions.readOnly(dataSource.get(), c -> findAccount(c, accountNo));
    }

    /** 口座と直近明細を 1 接続で取得する（プールからの接続取得・返却を 1 回に抑える）。 */
    public AccountDetail getAccountDetail(String accountNo) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Account account = findAccount(c, accountNo);
            return new AccountDetail(account, transactions.findRecent(c, accountNo, HISTORY_LIMIT));
        });
    }

    public List<AccountTransaction> getRecentTransactions(String accountNo) {
        return Transactions.readOnly(dataSource.get(), c -> transactions.findRecent(c, accountNo, HISTORY_LIMIT));
    }

    /**
     * 取引履歴の検索（期間・取引種別・ページ）。期首・期末残高と入出金合計も返す。
     *
     * @param from  開始日（その日を含む。null は指定なし）
     * @param to    終了日（その日を含む。null は指定なし）
     * @param types 取引種別（空は全種別）
     * @param page  1 始まり
     */
    public TransactionPage searchTransactions(String accountNo, LocalDate from, LocalDate to, List<TxType> types,
                                              int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE || (from != null && to != null && from.isAfter(to))) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        LocalDateTime fromTs = from == null ? null : from.atStartOfDay();
        LocalDateTime toTs = to == null ? null : to.plusDays(1).atStartOfDay();
        TransactionRepository.Filter filter = new TransactionRepository.Filter(accountNo, fromTs, toTs, types);
        return Transactions.readOnly(dataSource.get(), c -> {
            Account account = findAccount(c, accountNo);
            TransactionRepository.Totals totals = transactions.totals(c, filter);
            List<AccountTransaction> items = transactions.search(c, filter, (page - 1) * size, size);
            BigDecimal opening = fromTs == null ? BigDecimal.ZERO : transactions.balanceBefore(c, accountNo, fromTs);
            BigDecimal closing = toTs == null ? account.getBalance() : transactions.balanceBefore(c, accountNo, toTs);
            return new TransactionPage(items, page, size, totals.count, opening, closing, totals.totalIn, totals.totalOut);
        });
    }

    public OperationResult deposit(String accountNo, BigDecimal amount, String memo, String idempotencyKey) {
        checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            registerRequest(c, idempotencyKey, TxType.DEPOSIT.name(), now, requests);
            Account account = lockActive(c, accounts, accountNo);
            requireYenTransactional(account);
            BigDecimal balance = account.getBalance().add(amount);
            accounts.updateBalance(c, accountNo, balance, now, true);
            transactions.insert(c, accountNo, TxType.DEPOSIT, amount, balance, null, memo, idempotencyKey, now);
            return new OperationResult(TxType.DEPOSIT.name(), accountNo, null, amount, balance, Timestamps.format(now));
        });
    }

    public OperationResult withdraw(String accountNo, BigDecimal amount, String memo, String idempotencyKey) {
        checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            registerRequest(c, idempotencyKey, TxType.WITHDRAWAL.name(), now, requests);
            Account account = lockActive(c, accounts, accountNo);
            requireYenTransactional(account);
            BigDecimal balance = debit(account, amount);
            checkDailyOutflow(c, accountNo, amount, now);
            consumeNoticeIfRequired(c, account, amount, now.toLocalDate(), notices);
            accounts.updateBalance(c, accountNo, balance, now, true);
            transactions.insert(c, accountNo, TxType.WITHDRAWAL, amount, balance, null, memo, idempotencyKey, now);
            return new OperationResult(TxType.WITHDRAWAL.name(), accountNo, null, amount, balance, Timestamps.format(now));
        });
    }

    public OperationResult transfer(String fromAccountNo, String toAccountNo, BigDecimal amount, String memo,
                                    String idempotencyKey) {
        return transfer(fromAccountNo, toAccountNo, amount, memo, idempotencyKey, false);
    }

    /**
     * 振込・振替。
     *
     * @param ownAccountsOnly true: 振替（同じ顧客の口座間に限る）
     */
    public OperationResult transfer(String fromAccountNo, String toAccountNo, BigDecimal amount, String memo,
                                    String idempotencyKey, boolean ownAccountsOnly) {
        checkAmount(amount);
        if (fromAccountNo.equals(toAccountNo)) throw new BankException(ErrorCode.SAME_ACCOUNT);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            registerRequest(c, idempotencyKey, TxType.TRANSFER_OUT.name(), now, requests);

            // Lock both rows in a fixed (account number) order to avoid deadlocks between opposite transfers.
            boolean fromFirst = fromAccountNo.compareTo(toAccountNo) < 0;
            Account first = lockActive(c, accounts, fromFirst ? fromAccountNo : toAccountNo);
            Account second = lockActive(c, accounts, fromFirst ? toAccountNo : fromAccountNo);
            Account from = fromFirst ? first : second;
            Account to = fromFirst ? second : first;
            if (ownAccountsOnly && !from.getCustomerId().equals(to.getCustomerId())) {
                throw new BankException(ErrorCode.NOT_OWN_ACCOUNT, toAccountNo);
            }

            requireYenTransactional(from);
            requireYenTransactional(to);
            BigDecimal fromBalance = debit(from, amount);
            checkDailyOutflow(c, fromAccountNo, amount, now);
            consumeNoticeIfRequired(c, from, amount, now.toLocalDate(), notices);
            BigDecimal toBalance = to.getBalance().add(amount);
            // Batched: 2 updates + 2 inserts in 2 round trips (instead of 4) while both rows are locked.
            accounts.updateBalances(c, fromAccountNo, fromBalance, toAccountNo, toBalance, now);
            transactions.insertTransfer(c, fromAccountNo, fromBalance, toAccountNo, toBalance, amount, memo,
                    idempotencyKey, now);
            return new OperationResult("TRANSFER", fromAccountNo, toAccountNo, amount, fromBalance, Timestamps.format(now));
        });
    }

    /** 当日の出金・振込（出）の合計 + 今回の金額が日次上限を超えないか（口座行ロック中に判定するため同時実行でも正確）。 */
    private void checkDailyOutflow(Connection c, String accountNo, BigDecimal amount, LocalDateTime now)
            throws SQLException {
        BigDecimal today = transactions.outflowSince(c, accountNo, now.toLocalDate().atStartOfDay());
        if (today.add(amount).compareTo(DAILY_OUTFLOW_LIMIT) > 0) {
            throw new BankException(ErrorCode.DAILY_LIMIT_EXCEEDED, accountNo);
        }
    }

    private Account findAccount(Connection c, String accountNo) throws SQLException {
        return accounts.findById(c, accountNo)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo));
    }

    // ---- shared business rules (also used by the other services) ----

    static void registerRequest(Connection c, String key, String operation, LocalDateTime now,
                                OperationRequestRepository requests)
            throws SQLException {
        if (!requests.register(c, key, operation, now)) {
            throw new BankException(ErrorCode.DUPLICATE_REQUEST);
        }
    }

    static Account lockExisting(Connection c, AccountRepository accounts, String accountNo) throws SQLException {
        return accounts.lockById(c, accountNo)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo));
    }

    /** 取引可能（ACTIVE）な口座を行ロック付きで取得する。凍結・休眠・解約済みは取引不可。 */
    static Account lockActive(Connection c, AccountRepository accounts, String accountNo) throws SQLException {
        Account account = lockExisting(c, accounts, accountNo);
        if (!account.isActive()) throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, accountNo);
        return account;
    }

    /** 出金後の残高。当座預金は当座貸越の極度額までマイナスにできる。 */
    static BigDecimal debit(Account account, BigDecimal amount) {
        BigDecimal balance = account.getBalance().subtract(amount);
        if (balance.add(account.getOverdraftLimit()).signum() < 0) {
            throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, account.getAccountNo());
        }
        return balance;
    }

    /** 入金・出金・振込・振替ができる円口座か（定期預金・外貨預金はそれぞれ専用の手続きで行う）。 */
    static void requireYenTransactional(Account account) {
        if ("TIME".equals(account.getAccountType()) || "FOREIGN".equals(account.getAccountType())) {
            throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, account.getAccountNo());
        }
        if (!account.isJpy()) throw new BankException(ErrorCode.CURRENCY_MISMATCH, account.getAccountNo());
    }

    /**
     * 通知預金の払出し: 据置期間（預入から 7 日）経過後、払出可能日を過ぎた通知（金額以上）が必要。使った通知は消し込む。
     */
    static void consumeNoticeIfRequired(Connection c, Account account, BigDecimal amount, LocalDate today,
                                        NoticeRepository notices) throws SQLException {
        if (!"NOTICE".equals(account.getAccountType())) return;
        LocalDate opened = LocalDateTime.parse(account.getOpenedAt()).toLocalDate();
        if (today.isBefore(opened.plusDays(NOTICE_HOLD_DAYS))) {
            throw new BankException(ErrorCode.NOTICE_REQUIRED, account.getAccountNo());
        }
        if (!notices.consume(c, account.getAccountNo(), amount, today)) {
            throw new BankException(ErrorCode.NOTICE_REQUIRED, account.getAccountNo());
        }
    }

    static void checkAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        if (amount.compareTo(MAX_AMOUNT_PER_OPERATION) > 0) throw new BankException(ErrorCode.LIMIT_EXCEEDED);
    }
}
