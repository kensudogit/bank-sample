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
 *   <li>{@value #DW_LOAD}: 毎日 前日分を情報系（DWH）に取り込む（DWH が空なら直近 30 日分）。ほかのジョブの後に実行する</li>
 * </ul>
 */
public class JobRunner {
    // ジョブ名は JOB_RUN の主キーの一部（名前を変えると、同じ日に「未実行」と判断されて再実行される）
    public static final String DORMANCY = "DORMANCY";
    public static final String INTEREST = "INTEREST_SETTLEMENT";
    public static final String REQUEST_CLEANUP = "REQUEST_CLEANUP";
    public static final String TIME_MATURITY = "TIME_MATURITY";
    public static final String LOAN_DUE = "LOAN_DUE";
    public static final String DIRECT_DEBIT = "DIRECT_DEBIT";
    public static final String LEDGER_CHECK = "LEDGER_CHECK";
    public static final String DW_LOAD = "DW_LOAD";
    /** この時刻より前はジョブを開始しない（前日分の取引が出そろってから処理する） */
    public static final LocalTime START_TIME = LocalTime.of(1, 0);
    /** 冪等キーの保存日数（これより古い再送は新しい要求として扱われる） */
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
    private final LedgerService ledger;
    private final InformationService information;
    private final LoanManagementService loanManagement;
    private final JobRunRepository runs = new JobRunRepository();

    /**
     * 各ジョブのサービスを同じ DataSource・時計で生成する（テストでは時計を固定して業務日を指定できる）。
     *
     * @param dataSource 接続の取得元
     * @param clock      業務日・開始時刻の判定に使う時計
     */
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
        this.ledger = new LedgerService(dataSource, clock);
        this.information = new InformationService(dataSource, clock);
        this.loanManagement = new LoanManagementService(dataSource, clock);
    }

    /** AML の取引モニタリング（定期チェックのたびに、前回の続きから）。 */
    public Map<String, Object> scanAml() {
        return aml.scan();
    }

    /** @return アプリケーション既定のインスタンス */
    public static JobRunner getDefault() { return DEFAULT; }

    /** 期限の来たジョブを実行し、実行したジョブ名を返す。 */
    public List<String> runDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> ran = new ArrayList<>();
        // 定期チェックは 10 分ごとに呼ばれるが、開始時刻前は何もしない
        if (now.toLocalTime().isBefore(START_TIME)) return ran;
        LocalDate today = now.toLocalDate();

        // 各ジョブは独立に実行権を取る。あるジョブが失敗しても残りのジョブは実行される
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
            // 延滞判定で債務者区分が変わった後に、貸倒引当金を見直す
            Map<String, Object> p = loanManagement.provisionAll(today, "SYSTEM");
            return "融資 " + r.get("loans") + " 件・口座引落し " + r.get("autoDebited") + " 件・延滞 " + r.get("delinquent")
                    + " 件・引当見直し " + p.get("changed") + " 件（引当総額 " + p.get("totalProvision") + " 円）";
        })) ran.add(LOAN_DUE);

        if (run(DIRECT_DEBIT, today, () -> {
            Map<String, Object> r = payments.processDirectDebits(today);
            return "口座振替 振替済 " + r.get("paid") + " 件・不能 " + r.get("failed") + " 件";
        })) ran.add(DIRECT_DEBIT);

        boolean interestDay = today.getDayOfMonth() == 1
                && (today.getMonth() == Month.MARCH || today.getMonth() == Month.SEPTEMBER);
        // 決算日以外は実行権も取らない（JOB_RUN に余計な行を作らない）
        if (interestDay && run(INTEREST, today, () -> {
            SettlementSummary s = interest.settleAll(today);
            return "決算 " + s.getSettledCount() + " 口座・利息 " + s.getTotalGross() + " 円・税 " + s.getTotalTax() + " 円";
        })) ran.add(INTEREST);

        if (run(LEDGER_CHECK, today, () -> {
            Map<String, Object> r = ledger.verify(null);
            String summary = "元帳検証 " + r.get("accounts") + " 口座・" + r.get("transactions") + " 明細";
            if (!Boolean.TRUE.equals(r.get("valid"))) {
                // Reported as a failed job so it shows up on the operations screen and in the audit log
                throw new IllegalStateException(summary + "：不整合 " + ((List<?>) r.get("problems")).size() + " 件");
            }
            return summary + "：問題なし";
        })) ran.add(LEDGER_CHECK);

        if (run(REQUEST_CLEANUP, today, () -> {
            // 保存期間を過ぎた冪等キーを削除する
            int deleted = Transactions.inTransaction(dataSource.get(),
                    c -> runs.deleteRequestsBefore(c, now.minusDays(REQUEST_RETENTION_DAYS)));
            return "冪等キー " + deleted + " 件を削除";
        })) ran.add(REQUEST_CLEANUP);

        // 情報系の取込みは、前日分の勘定系の処理（満期・返済・口座振替）が終わった後に行う
        if (run(DW_LOAD, today, () -> {
            Map<String, Object> r = information.loadPreviousDay();
            return "情報系 取込 " + r.get("days") + " 日分（" + r.get("from") + " 〜 " + r.get("to") + "）";
        })) ran.add(DW_LOAD);
        return ran;
    }

    /**
     * 直近のジョブ実行履歴。
     *
     * @param limit 最大件数
     * @return 新しい順の実行履歴
     */
    public List<Map<String, Object>> recentRuns(int limit) {
        return Transactions.readOnly(dataSource.get(), c -> runs.recent(c, limit));
    }

    // ジョブ本体（戻り値は実行結果の要約。実行履歴と監査ログに残す）
    private interface Job {
        String execute();
    }

    /** @return true: このインスタンスで実行した（成功・失敗とも） */
    private boolean run(String jobName, LocalDate runDate, Job job) {
        // 実行権の取得は短い独立トランザクションで先にコミットする。ジョブ本体と同じトランザクションにすると、
        // 実行中は他のインスタンスが主キーの確定待ちでブロックされ、失敗時には実行権ごと消えて再実行されてしまう
        boolean claimed = Transactions.inTransaction(dataSource.get(),
                c -> runs.claim(c, jobName, runDate, LocalDateTime.now(clock)));
        if (!claimed) return false;
        String outcome;
        String detail;
        try {
            detail = job.execute();
            outcome = AuditService.SUCCESS;
        // 失敗も「実行済み」として記録する（同じ日に自動で再実行はしない。必要なら運用者が原因を確認してから対処する）
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
        // バッチの実行も「誰が（SYSTEM）・何を・結果」として監査ログに残す
        audit.record(LoginUser.SYSTEM, null, "job:" + jobName, runDate.toString(), outcome, detail, null);
        LOG.info("job {} {}: {}", jobName, outcome, detail);
        return true;
    }
}
