package com.example.bank.service;

import com.example.bank.domain.DormancyReport;
import com.example.bank.domain.LoginUser;
import com.example.bank.domain.SettlementSummary;
import com.example.bank.repository.JobRunRepository;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 夜間バッチ。{@link #runDue()} を定期的に呼ぶと、開始時刻を過ぎた未実行のジョブを実行する。
 * 実行権は JOB_RUN の主キー（ジョブ名 + 業務日）で取得するため、再起動や複数インスタンスでも 1 日 1 回だけ動く。
 * <ul>
 *   <li>{@value #DORMANCY}: 毎日 休眠前通知の記録・休眠化</li>
 *   <li>{@value #INTEREST}: 3/1・9/1 に全口座の利息決算（2 月末・8 月末までの利息。普通預金の年 2 回決算）</li>
 *   <li>{@value #REQUEST_CLEANUP}: 毎日 {@value #REQUEST_RETENTION_DAYS} 日より古い冪等キーを削除</li>
 * </ul>
 */
public class JobRunner {
    public static final String DORMANCY = "DORMANCY";
    public static final String INTEREST = "INTEREST_SETTLEMENT";
    public static final String REQUEST_CLEANUP = "REQUEST_CLEANUP";
    public static final String TIME_MATURITY = "TIME_MATURITY";
    public static final String LOAN_DUE = "LOAN_DUE";
    public static final String DIRECT_DEBIT = "DIRECT_DEBIT";
    public static final LocalTime START_TIME = LocalTime.of(1, 0);
    public static final int REQUEST_RETENTION_DAYS = 90;
    private static final Logger LOG = LogManager.getLogger(JobRunner.class);

    private static final JobRunner DEFAULT = new JobRunner(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final InterestService interest;
    private final AccountLifecycleService lifecycle;
    private final AuditService audit;
    private final DepositProductService deposits;
    private final LoanService loans;
    private final PaymentService payments;
    private final AmlService aml;
    private final JobRunRepository runs = new JobRunRepository();

    public JobRunner(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.interest = new InterestService(dataSource, clock);
        this.lifecycle = new AccountLifecycleService(dataSource, clock);
        this.audit = new AuditService(dataSource, clock);
        this.deposits = new DepositProductService(dataSource, clock);
        this.loans = new LoanService(dataSource, clock);
        this.payments = new PaymentService(dataSource, clock);
        this.aml = new AmlService(dataSource, clock);
    }

    /** AML の取引モニタリング（定期チェックのたびに、前回の続きから）。 */
    public Map<String, Object> scanAml() {
        return aml.scan();
    }

    public static JobRunner getDefault() { return DEFAULT; }

    /** 期限の来たジョブを実行し、実行したジョブ名を返す。 */
    public List<String> runDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> ran = new ArrayList<>();
        if (now.toLocalTime().isBefore(START_TIME)) return ran;
        LocalDate today = now.toLocalDate();

        if (run(DORMANCY, today, () -> {
            DormancyReport r = lifecycle.runDormancy(today);
            return "休眠前通知 " + r.getNoticed() + " 件・休眠化 " + r.getMadeDormant() + " 件";
        })) ran.add(DORMANCY);

        if (run(TIME_MATURITY, today, () -> {
            List<Map<String, Object>> r = deposits.processMaturities(today);
            return "定期預金の満期処理 " + r.size() + " 件";
        })) ran.add(TIME_MATURITY);

        if (run(LOAN_DUE, today, () -> {
            Map<String, Object> r = loans.processDue(today);
            return "融資 " + r.get("loans") + " 件・口座引落し " + r.get("autoDebited") + " 件・延滞 " + r.get("delinquent") + " 件";
        })) ran.add(LOAN_DUE);

        if (run(DIRECT_DEBIT, today, () -> {
            Map<String, Object> r = payments.processDirectDebits(today);
            return "口座振替 振替済 " + r.get("paid") + " 件・不能 " + r.get("failed") + " 件";
        })) ran.add(DIRECT_DEBIT);

        boolean interestDay = today.getDayOfMonth() == 1
                && (today.getMonth() == Month.MARCH || today.getMonth() == Month.SEPTEMBER);
        if (interestDay && run(INTEREST, today, () -> {
            SettlementSummary s = interest.settleAll(today);
            return "決算 " + s.getSettledCount() + " 口座・利息 " + s.getTotalGross() + " 円・税 " + s.getTotalTax() + " 円";
        })) ran.add(INTEREST);

        if (run(REQUEST_CLEANUP, today, () -> {
            int deleted = Transactions.inTransaction(dataSource.get(),
                    c -> runs.deleteRequestsBefore(c, now.minusDays(REQUEST_RETENTION_DAYS)));
            return "冪等キー " + deleted + " 件を削除";
        })) ran.add(REQUEST_CLEANUP);
        return ran;
    }

    public List<Map<String, Object>> recentRuns(int limit) {
        return Transactions.readOnly(dataSource.get(), c -> runs.recent(c, limit));
    }

    private interface Job {
        String execute();
    }

    /** @return true: このインスタンスで実行した（成功・失敗とも） */
    private boolean run(String jobName, LocalDate runDate, Job job) {
        boolean claimed = Transactions.inTransaction(dataSource.get(),
                c -> runs.claim(c, jobName, runDate, LocalDateTime.now(clock)));
        if (!claimed) return false;
        String outcome;
        String detail;
        try {
            detail = job.execute();
            outcome = AuditService.SUCCESS;
        } catch (RuntimeException e) {
            LOG.error("job {} failed", jobName, e);
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            outcome = AuditService.FAILURE;
        }
        final String finalOutcome = outcome;
        final String finalDetail = detail;
        Transactions.inTransaction(dataSource.get(), c -> {
            runs.finish(c, jobName, runDate, finalOutcome, finalDetail, LocalDateTime.now(clock));
            return null;
        });
        audit.record(LoginUser.SYSTEM, null, "job:" + jobName, runDate.toString(), outcome, detail, null);
        LOG.info("job {} {}: {}", jobName, outcome, detail);
        return true;
    }
}
