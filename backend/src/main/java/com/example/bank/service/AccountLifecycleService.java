package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.CloseResult;
import com.example.bank.domain.Customer;
import com.example.bank.domain.DormancyReport;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.InterestResult;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 口座解約と休眠預金管理。
 *
 * <p>休眠預金（休眠預金等活用法を簡略化したルール）:
 * <ul>
 *   <li>最終異動日 = 顧客による入金・出金・振込の最終日時（利息の入金は含まない）</li>
 *   <li>最終異動から 9 年経過・残高 1 万円以上の口座には休眠前の通知を行う（通知日時を記録）</li>
 *   <li>最終異動から 10 年経過し、「残高 1 万円未満」または「通知から 1 か月経過」した口座を休眠（DORMANT）にする</li>
 *   <li>休眠口座は取引不可。本人確認済みの顧客からの申出で休眠解除する</li>
 * </ul>
 */
public class AccountLifecycleService {
    /** 休眠前通知の対象になる、最終異動からの年数 */
    public static final int NOTICE_YEARS = 9;
    /** 休眠化の対象になる、最終異動からの年数 */
    public static final int DORMANT_YEARS = 10;
    /** 休眠前通知が必要になる残高の下限（円） */
    public static final BigDecimal NOTICE_MIN_BALANCE = new BigDecimal("10000");
    /** 通知してから休眠化できるまでの期間（月） */
    public static final int NOTICE_WAIT_MONTHS = 1;

    private static final AccountLifecycleService DEFAULT =
            new AccountLifecycleService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final InterestService interestService;
    private final AccountRepository accounts = new AccountRepository();
    private final CustomerRepository customers = new CustomerRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();

    /**
     * @param dataSource 接続の取得元
     * @param clock      基準日・処理日時の取得元
     */
    public AccountLifecycleService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.interestService = new InterestService(dataSource, clock);
    }

    /** @return アプリケーション既定のインスタンス */
    public static AccountLifecycleService getDefault() { return DEFAULT; }

    /**
     * 口座解約: 前日までの利息を決算し、全残高を払い戻して CLOSED にする（1 トランザクション）。
     *
     * @param payoutAccountNo 払戻し先（同じ顧客の利用可能な口座）。null は現金払戻し
     */
    public CloseResult close(String accountNo, String payoutAccountNo, String idempotencyKey) {
        if (accountNo.equals(payoutAccountNo)) throw new BankException(ErrorCode.SAME_ACCOUNT);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "CLOSE", now, requests);

            Account account;
            Account payout = null;
            // 現金払戻しなら解約口座だけ、口座への払戻しなら 2 口座をロックする
            if (payoutAccountNo == null) {
                account = BankService.lockExisting(c, accounts, accountNo);
            } else {
                // Lock in account number order, same as transfers, to avoid deadlocks
                boolean accountFirst = accountNo.compareTo(payoutAccountNo) < 0;
                Account first = BankService.lockExisting(c, accounts, accountFirst ? accountNo : payoutAccountNo);
                Account second = BankService.lockExisting(c, accounts, accountFirst ? payoutAccountNo : accountNo);
                account = accountFirst ? first : second;
                payout = accountFirst ? second : first;
            }
            if (!account.isActive()) throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, accountNo);
            // 定期預金は中途解約の手続き、外貨預金は円への両替で残高を 0 にしてから
            if ("TIME".equals(account.getAccountType())) {
                throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, accountNo);
            }
            if (!account.isJpy() && account.getBalance().signum() != 0) {
                throw new BankException(ErrorCode.ACCOUNT_NOT_EMPTY, accountNo);
            }
            // 当座貸越でマイナス残高のまま解約すると貸越金が回収できなくなるため、先に返済してもらう
            if (account.getBalance().signum() < 0) throw new BankException(ErrorCode.ACCOUNT_NOT_EMPTY, accountNo);
            // 融資の返済口座を解約すると約定返済の引落し先がなくなるため、返済中の融資があれば解約させない
            if (!com.example.bank.repository.ProductRepository.query(c, "SELECT loan_id FROM loan "
                    + "WHERE repayment_account_no = ? AND status IN ('CONTRACTED', 'ACTIVE', 'DELINQUENT')", accountNo).isEmpty()) {
                throw new BankException(ErrorCode.LOAN_OUTSTANDING, accountNo);
            }
            if (payout != null) {
                if (!payout.isJpy() || "TIME".equals(payout.getAccountType())) {
                    throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, payoutAccountNo);
                }
                if (!payout.isActive()) throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, payoutAccountNo);
                if (!payout.getCustomerId().equals(account.getCustomerId())) {
                    throw new BankException(ErrorCode.NOT_OWN_ACCOUNT, payoutAccountNo);
                }
            }

            // 口座行ロックを持ったまま同じトランザクションで利息を決算する（決算と解約の間に入出金が割り込まない）
            InterestResult interest = interestService.settleLocked(c, account, now.toLocalDate(), now);
            // 利息入金後の残高を読み直す（ロック済みの行なので再取得しても待たされない）
            BigDecimal balance = BankService.lockExisting(c, accounts, accountNo).getBalance();  // after interest
            if (balance.signum() > 0) {
                // 出金側（CLOSE_PAYOUT）と入金側（TRANSFER_IN）を同じ冪等キーで記録し、元帳検証で金額の対応を確かめられるようにする
                transactions.insert(c, accountNo, TxType.CLOSE_PAYOUT, balance, BigDecimal.ZERO, payoutAccountNo,
                        "解約払戻し", idempotencyKey, now);
                if (payout != null) {
                    BigDecimal payoutBalance = payout.getBalance().add(balance);
                    accounts.updateBalance(c, payoutAccountNo, payoutBalance, now, true);
                    transactions.insert(c, payoutAccountNo, TxType.TRANSFER_IN, balance, payoutBalance, accountNo,
                            "解約払戻し", idempotencyKey, now);
                }
            }
            // 残高 0・CLOSED にする。ここまでがすべて 1 トランザクションなので、途中で失敗すれば利息決算も払戻しも取り消される
            accounts.close(c, accountNo, now);
            return new CloseResult(accountNo, interest, balance, payoutAccountNo, Timestamps.format(now));
        });
    }

    /** 休眠解除（本人確認済みの顧客のみ）。 */
    public Account reactivate(String accountNo, String idempotencyKey) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "REACTIVATE", now, requests);
            Account account = BankService.lockExisting(c, accounts, accountNo);
            if (!"DORMANT".equals(account.getStatus())) throw new BankException(ErrorCode.ACCOUNT_NOT_DORMANT, accountNo);
            Customer customer = customers.findById(c, account.getCustomerId())
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, account.getCustomerId()));
            if (!customer.isVerified()) throw new BankException(ErrorCode.KYC_NOT_VERIFIED, customer.getCustomerId());
            // 休眠解除の申出は「異動」に当たるため、最終異動日を現在にする（すぐに再び休眠対象にならない）
            accounts.reactivate(c, accountNo, now);
            return accounts.findById(c, accountNo).orElseThrow(() -> new SQLException("account vanished"));
        });
    }

    /** 休眠管理の対象一覧（照会のみ）。基準日は任意（未来日で将来の対象を確認できる）。 */
    public DormancyReport dormancyReport(LocalDate asOf) {
        LocalDate baseDate = asOf == null ? LocalDate.now(clock) : asOf;
        return Transactions.readOnly(dataSource.get(), c -> report(c, baseDate, 0, 0));
    }

    /** 休眠前通知の記録と休眠化を実行する。基準日は本日以前。 */
    public DormancyReport runDormancy(LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        LocalDate baseDate = asOf == null ? today : asOf;
        // 未来日で実行すると、まだ条件を満たしていない口座を休眠にしてしまうため拒否する
        if (baseDate.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            DormancyReport before = report(c, baseDate, 0, 0);
            LocalDateTime noticeCutoff = baseDate.minusYears(NOTICE_YEARS).atStartOfDay();
            LocalDateTime dormantCutoff = baseDate.minusYears(DORMANT_YEARS).atStartOfDay();
            int noticed = 0;
            int dormant = 0;
            // The UPDATEs re-check status / last activity, so an account used a moment ago is skipped
            for (Account a : before.getNoticeTargets()) {
                noticed += accounts.markNotified(c, a.getAccountNo(), now, noticeCutoff, now);
            }
            for (Account a : before.getDormancyTargets()) {
                dormant += accounts.markDormant(c, a.getAccountNo(), now, dormantCutoff, now);
            }
            return report(c, baseDate, noticed, dormant);
        });
    }

    // 候補（9 年以上異動なし）を 1 回の検索で取り、通知対象と休眠対象に振り分ける
    private DormancyReport report(Connection c, LocalDate asOf, int noticed, int madeDormant) throws SQLException {
        LocalDateTime noticeCutoff = asOf.minusYears(NOTICE_YEARS).atStartOfDay();
        LocalDateTime dormantCutoff = asOf.minusYears(DORMANT_YEARS).atStartOfDay();
        // 通知日時がこれより前なら「通知から 1 か月経過」とみなす
        LocalDateTime noticeDeadline = asOf.minusMonths(NOTICE_WAIT_MONTHS).plusDays(1).atStartOfDay();

        List<Account> noticeTargets = new ArrayList<>();
        List<Account> dormancyTargets = new ArrayList<>();
        for (Account a : accounts.findActiveInactiveSince(c, noticeCutoff)) {
            boolean needsNotice = a.getBalance().compareTo(NOTICE_MIN_BALANCE) >= 0;
            LocalDateTime notifiedAt = parse(a.getDormancyNotifiedAt());
            if (needsNotice && notifiedAt == null) noticeTargets.add(a);
            boolean tenYears = parse(a.getLastActivityAt()).isBefore(dormantCutoff);
            boolean noticeDone = !needsNotice || (notifiedAt != null && notifiedAt.isBefore(noticeDeadline));
            if (tenYears && noticeDone) dormancyTargets.add(a);
        }
        return new DormancyReport(asOf.toString(), noticeTargets, dormancyTargets,
                accounts.findByStatus(c, "DORMANT"), noticed, madeDormant);
    }

    // Account は日時を ISO 文字列で持つため、比較のために LocalDateTime に戻す
    private static LocalDateTime parse(String iso) {
        return iso == null ? null : LocalDateTime.parse(iso);
    }
}
