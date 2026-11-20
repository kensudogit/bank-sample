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
    /** 口座詳細に表示する直近明細の件数 */
    public static final int HISTORY_LIMIT = 50;
    /** 一覧 API の 1 ページあたりの最大件数 */
    public static final int MAX_PAGE_SIZE = 200;
    /** 1 口座 1 日あたりの出金・振込（出）の合計上限（円） */
    public static final BigDecimal DAILY_OUTFLOW_LIMIT = new BigDecimal("20000000");
    /** 通知預金の据置期間（日） */
    public static final int NOTICE_HOLD_DAYS = 7;

    // Struts の Action は DI なしで生成されるため、既定のインスタンスを static に持つ（テストでは別の DataSource・時計で生成する）
    private static final BankService DEFAULT = new BankService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    // リポジトリは状態を持たない（接続は引数で受け取る）ので、1 つのインスタンスを全スレッドで共有してよい
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();
    private final NoticeRepository notices = new NoticeRepository();

    /**
     * @param dataSource 接続の取得元（JNDI の解決を初回利用時まで遅らせるため Supplier で受け取る）
     * @param clock      現在日時の取得元（テストで日時を固定できるように）
     */
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
        // ヘルスチェックは例外を投げず、DB に届かなければ false（503）として扱う
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 全口座の一覧。
     *
     * @return 口座一覧
     */
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
        // Action で検証済みでも、サービスを直接呼ぶ場合に備えてここでも範囲を確かめる
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

    /**
     * 口座を取得する。
     *
     * @param accountNo 口座番号
     * @return 口座
     * @throws BankException 口座がない場合（ACCOUNT_NOT_FOUND）
     */
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

    /**
     * 直近の明細（最大 {@value #HISTORY_LIMIT} 件、新しい順）。
     *
     * @param accountNo 口座番号
     * @return 明細一覧
     */
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
        // 終了日は「その日を含む」ため、翌日 0 時未満（toExclusive）に変換して時刻付きの明細も漏れなく含める
        LocalDateTime toTs = to == null ? null : to.plusDays(1).atStartOfDay();
        TransactionRepository.Filter filter = new TransactionRepository.Filter(accountNo, fromTs, toTs, types);
        return Transactions.readOnly(dataSource.get(), c -> {
            Account account = findAccount(c, accountNo);
            TransactionRepository.Totals totals = transactions.totals(c, filter);
            List<AccountTransaction> items = transactions.search(c, filter, (page - 1) * size, size);
            // 期首残高は「期間開始より前の最後の明細の取引後残高」。明細に取引後残高を持たせているので過去分を合計し直す必要がない
            BigDecimal opening = fromTs == null ? BigDecimal.ZERO : transactions.balanceBefore(c, accountNo, fromTs);
            BigDecimal closing = toTs == null ? account.getBalance() : transactions.balanceBefore(c, accountNo, toTs);
            return new TransactionPage(items, page, size, totals.count, opening, closing, totals.totalIn, totals.totalOut);
        });
    }

    /**
     * 入金する。
     *
     * @param accountNo      口座番号
     * @param amount         金額（1 円以上・上限以内）
     * @param memo           摘要
     * @param idempotencyKey 冪等キー（同じキーの 2 回目は DUPLICATE_REQUEST）
     * @return 取引結果
     * @throws BankException 口座がない・取引できない状態・円口座でない場合など
     */
    public OperationResult deposit(String accountNo, BigDecimal amount, String memo, String idempotencyKey) {
        checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            // 冪等キーの登録を最初に行う。重複ならこの時点で例外になり、ロックも残高更新もせずにロールバックされる
            registerRequest(c, idempotencyKey, TxType.DEPOSIT.name(), now, requests);
            // 行ロックを取ってから残高を読む（ロック前に読んだ残高で計算すると、同時入金の一方が消える）
            Account account = lockActive(c, accounts, accountNo);
            requireYenTransactional(account);
            BigDecimal balance = account.getBalance().add(amount);
            // 残高更新と明細登録は同じトランザクション。どちらかが失敗すれば両方とも取り消され、残高と明細が食い違わない
            accounts.updateBalance(c, accountNo, balance, now, true);
            transactions.insert(c, accountNo, TxType.DEPOSIT, amount, balance, null, memo, idempotencyKey, now);
            return new OperationResult(TxType.DEPOSIT.name(), accountNo, null, amount, balance, Timestamps.format(now));
        });
    }

    /**
     * 出金する。
     *
     * @param accountNo      口座番号
     * @param amount         金額（1 円以上・上限以内）
     * @param memo           摘要
     * @param idempotencyKey 冪等キー
     * @return 取引結果
     * @throws BankException 残高不足・日次上限超過・通知預金の通知なしなど
     */
    public OperationResult withdraw(String accountNo, BigDecimal amount, String memo, String idempotencyKey) {
        checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            registerRequest(c, idempotencyKey, TxType.WITHDRAWAL.name(), now, requests);
            Account account = lockActive(c, accounts, accountNo);
            requireYenTransactional(account);
            BigDecimal balance = debit(account, amount);
            // 日次上限は口座行ロック中に判定する。ロック外で集計すると、同時に 2 件出金されたときに両方とも上限内と判定されてしまう
            checkDailyOutflow(c, accountNo, amount, now);
            consumeNoticeIfRequired(c, account, amount, now.toLocalDate(), notices);
            accounts.updateBalance(c, accountNo, balance, now, true);
            transactions.insert(c, accountNo, TxType.WITHDRAWAL, amount, balance, null, memo, idempotencyKey, now);
            return new OperationResult(TxType.WITHDRAWAL.name(), accountNo, null, amount, balance, Timestamps.format(now));
        });
    }

    /**
     * 振込（同じ顧客の口座間に限らない）。
     *
     * @param fromAccountNo  振込元口座番号
     * @param toAccountNo    振込先口座番号
     * @param amount         金額
     * @param memo           摘要
     * @param idempotencyKey 冪等キー
     * @return 取引結果（残高は振込元の取引後残高）
     */
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
        // 同じ口座同士だと 2 行目のロックが同じ行になり、残高計算も意味を持たないため先に拒否する
        if (fromAccountNo.equals(toAccountNo)) throw new BankException(ErrorCode.SAME_ACCOUNT);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            registerRequest(c, idempotencyKey, TxType.TRANSFER_OUT.name(), now, requests);

            // A→B と B→A の振込が同時に来たとき、互いに相手の行を待ち合うデッドロックを避けるため、常に口座番号の小さい順にロックする
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
            // 行ロックを持っている時間を短くするため、2 口座分の更新と明細登録をそれぞれ 1 回の DB 往復にまとめる
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

    // 照会用（ロックなし）。存在しなければ業務エラーにする
    private Account findAccount(Connection c, String accountNo) throws SQLException {
        return accounts.findById(c, accountNo)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo));
    }

    // ---- shared business rules (also used by the other services) ----

    /**
     * 冪等キーを登録する（業務トランザクションの最初に呼ぶ）。
     *
     * @param c         業務と同じトランザクションの接続
     * @param key       冪等キー
     * @param operation 操作種別（記録用）
     * @param now       現在日時
     * @param requests  冪等キーのリポジトリ
     * @throws BankException 同じキーがすでに登録されている場合（DUPLICATE_REQUEST。インターセプターが再送の扱いを決める）
     */
    static void registerRequest(Connection c, String key, String operation, LocalDateTime now,
                                OperationRequestRepository requests)
            throws SQLException {
        if (!requests.register(c, key, operation, now)) {
            throw new BankException(ErrorCode.DUPLICATE_REQUEST);
        }
    }

    /**
     * 口座を行ロック付きで取得する（状態は問わない。解約・休眠解除など状態を変える処理用）。
     *
     * @param c         DB 接続
     * @param accounts  口座リポジトリ
     * @param accountNo 口座番号
     * @return 口座
     * @throws BankException 口座がない場合（ACCOUNT_NOT_FOUND）
     */
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
        // 引出可能額 = 残高 + 当座貸越の極度額（当座以外は極度額 0 なので残高そのもの）
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
        // 通知の消し込みは口座行ロック中に行うので、同じ通知を 2 つの出金で使うことはない
        if (!notices.consume(c, account.getAccountNo(), amount, today)) {
            throw new BankException(ErrorCode.NOTICE_REQUIRED, account.getAccountNo());
        }
    }

    /**
     * 金額の共通チェック（1 円以上の整数・1 回の上限以内）。
     *
     * @param amount 金額
     * @throws BankException 0 以下・小数を含む場合（VALIDATION_ERROR）、上限超過（LIMIT_EXCEEDED）
     */
    static void checkAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        if (amount.compareTo(MAX_AMOUNT_PER_OPERATION) > 0) throw new BankException(ErrorCode.LIMIT_EXCEEDED);
    }
}
