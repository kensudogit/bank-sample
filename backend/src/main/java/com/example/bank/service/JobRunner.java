package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.DormancyReport;
import com.example.bank.domain.ErrorCode;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.query;

/**
 * 夜間バッチ。{@link #runDue()} を定期的に呼ぶと、開始時刻を過ぎた未実行のジョブを登録順に実行する。
 * 実行権は JOB_RUN の主キー（ジョブ名 + 業務日）で取得するため、再起動や複数インスタンスでも 1 日 1 回だけ動く。
 * あるジョブが失敗しても残りのジョブは実行し、失敗したジョブは役席が原因を確かめてから {@link #rerun} で再実行する。
 *
 * <p>日次の集計・照合・帳票は「前日（締まった業務日）」を処理する。順序は
 * 休眠 → 定期満期 → 融資の約定返済・延滞判定・引当 → 口座振替 → 預金利息の日次計上 → 利息決算（3/1・9/1）
 * → 残高照合 → 日次取引集計 → 手数料計算 → 為替照合 → 会計仕訳集計 → 月末締め（毎月 1 日）→ 帳票出力
 * → 冪等キーの整理 → 情報系の取込み。</p>
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
    public static final String INTEREST_ACCRUAL = "INTEREST_ACCRUAL";
    public static final String DAILY_SUMMARY = "DAILY_SUMMARY";
    public static final String FEE_CHECK = "FEE_CHECK";
    public static final String REMIT_RECON = "REMIT_RECON";
    public static final String GL_POSTING = "GL_POSTING";
    public static final String MONTH_END = "MONTH_END";
    public static final String REPORTS = "REPORTS";
    public static final String AUDIT_ANCHOR = "AUDIT_ANCHOR";
    public static final String AUDIT_RETENTION = "AUDIT_RETENTION";
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
    private final BankBatchService batch;
    private final JobRunRepository runs = new JobRunRepository();
    private final List<JobDef> jobs = new ArrayList<>();

    // ジョブ本体（実行日を受け取り、結果の要約を返す。要約は実行履歴と監査ログに残す）
    private interface Body {
        String execute(LocalDate runDate, String by);
    }

    // 登録したジョブ：名前・表示名・実行日の条件・本体
    private static final class JobDef {
        final String name;
        final String label;
        final String timing;
        final Predicate<LocalDate> due;
        final Body body;

        JobDef(String name, String label, String timing, Predicate<LocalDate> due, Body body) {
            this.name = name;
            this.label = label;
            this.timing = timing;
            this.due = due;
            this.body = body;
        }
    }

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
        this.batch = new BankBatchService(dataSource, clock);
        register();
    }

    /** AML の取引モニタリング（定期チェックのたびに、前回の続きから）。 */
    public Map<String, Object> scanAml() {
        return aml.scan();
    }

    /** @return アプリケーション既定のインスタンス */
    public static JobRunner getDefault() { return DEFAULT; }

    private static boolean interestDay(LocalDate d) {
        return d.getDayOfMonth() == 1 && (d.getMonth() == Month.MARCH || d.getMonth() == Month.SEPTEMBER);
    }

    // 実行順に登録する（前のジョブの結果を後のジョブが使う：返済 → 照合 → 集計 → 仕訳 → 締め → 帳票）
    private void register() {
        Predicate<LocalDate> daily = d -> true;
        jobs.add(new JobDef(DORMANCY, "休眠管理", "毎日", daily, (d, by) -> {
            DormancyReport r = lifecycle.runDormancy(d);
            return "休眠前通知 " + r.getNoticed() + " 件・休眠化 " + r.getMadeDormant() + " 件";
        }));
        jobs.add(new JobDef(TIME_MATURITY, "定期預金の満期処理", "毎日", daily, (d, by) -> {
            List<Map<String, Object>> r = deposits.processMaturities(d);
            return "定期預金の満期処理 " + r.size() + " 件";
        }));
        jobs.add(new JobDef(LOAN_DUE, "融資返済・延滞判定・貸倒引当金", "毎日（約定返済日に引落し）", daily, (d, by) -> {
            Map<String, Object> r = loans.processDue(d);
            // 延滞判定で債務者区分が変わった後に、貸倒引当金を見直す
            Map<String, Object> p = loanManagement.provisionAll(d, "SYSTEM");
            return "融資 " + r.get("loans") + " 件・口座引落し " + r.get("autoDebited") + " 件・延滞 " + r.get("delinquent")
                    + " 件・引当見直し " + p.get("changed") + " 件（引当総額 " + p.get("totalProvision") + " 円）";
        }));
        jobs.add(new JobDef(DIRECT_DEBIT, "口座振替", "毎日", daily, (d, by) -> {
            Map<String, Object> r = payments.processDirectDebits(d);
            return "口座振替 振替済 " + r.get("paid") + " 件・不能 " + r.get("failed") + " 件";
        }));
        jobs.add(new JobDef(INTEREST_ACCRUAL, "預金利息の日次計上", "毎日（前日分）", daily, (d, by) -> {
            Map<String, Object> r = batch.accrueInterest(d.minusDays(1));
            return r.get("date") + " " + r.get("accounts") + " 口座・当日 " + r.get("dailyInterest") + " 円・未払利息 "
                    + r.get("accruedInterest") + " 円";
        }));
        // 決算日以外は実行権も取らない（JOB_RUN に余計な行を作らない）
        jobs.add(new JobDef(INTEREST, "預金利息の決算（利払い）", "3/1・9/1", JobRunner::interestDay, (d, by) -> {
            SettlementSummary s = interest.settleAll(d);
            return "決算 " + s.getSettledCount() + " 口座・利息 " + s.getTotalGross() + " 円・税 " + s.getTotalTax() + " 円";
        }));
        jobs.add(new JobDef(LEDGER_CHECK, "残高照合（元帳検証）", "毎日", daily, (d, by) -> {
            Map<String, Object> r = ledger.verify(null);
            String summary = "元帳検証 " + r.get("accounts") + " 口座・" + r.get("transactions") + " 明細";
            if (!Boolean.TRUE.equals(r.get("valid"))) {
                // Reported as a failed job so it shows up on the operations screen and in the audit log
                throw new IllegalStateException(summary + "：不整合 " + ((List<?>) r.get("problems")).size() + " 件");
            }
            return summary + "：問題なし";
        }));
        jobs.add(new JobDef(DAILY_SUMMARY, "日次取引集計", "毎日（前日分）", daily, (d, by) -> {
            Map<String, Object> r = batch.dailySummary(d.minusDays(1));
            return r.get("date") + " 取引 " + r.get("txCount") + " 件・入金 " + r.get("inflowJpy") + " 円・出金 " + r.get("outflowJpy") + " 円";
        }));
        jobs.add(new JobDef(FEE_CHECK, "手数料計算・照合", "毎日（前日分）・月次は月末締め", daily, (d, by) -> {
            Map<String, Object> r = batch.feeCheck(d.minusDays(1));
            return r.get("date") + " 他行振込 " + r.get("domestic") + " 件・海外送金 " + r.get("foreign") + " 件・手数料 "
                    + r.get("feeIncome") + " 円：照合 OK";
        }));
        jobs.add(new JobDef(REMIT_RECON, "為替照合", "毎日（前日分）", daily, (d, by) -> {
            Map<String, Object> r = batch.remittanceReconciliation(d.minusDays(1));
            return r.get("date") + " 全銀データ " + r.get("zenginBatches") + " 件・送金 " + r.get("remitOut") + " 円：照合 OK（警告 "
                    + r.get("warnings") + " 件）";
        }));
        jobs.add(new JobDef(GL_POSTING, "会計仕訳集計（元帳照合）", "毎日（前日分）", daily, (d, by) -> {
            Map<String, Object> r = batch.generalLedger(d.minusDays(1));
            return r.get("date") + " " + r.get("accounts") + " 科目・借方 " + r.get("debit") + " 円 = 貸方 " + r.get("credit") + " 円：照合 OK";
        }));
        jobs.add(new JobDef(MONTH_END, "月末締め", "毎月 1 日（前月分）", d -> d.getDayOfMonth() == 1, (d, by) -> {
            Map<String, Object> r = batch.monthEnd(YearMonth.from(d.minusDays(1)), by);
            return r.get("closeMonth") + " 締め：預金 " + r.get("totalDeposits") + " 円・貸出金 " + r.get("totalLoans") + " 円・取引 "
                    + r.get("txCount") + " 件（" + r.get("status") + "）";
        }));
        jobs.add(new JobDef(REPORTS, "帳票出力", "毎日（前日分）・月初は月次帳票", daily, (d, by) -> {
            List<Map<String, Object>> r = batch.reports(d.minusDays(1), by);
            StringBuilder sb = new StringBuilder(d.minusDays(1) + " 帳票 " + r.size() + " 件：");
            for (Map<String, Object> x : r) sb.append(' ').append(x.get("fileName")).append('（').append(x.get("rows")).append(" 行）");
            return sb.toString();
        }));
        jobs.add(new JobDef(REQUEST_CLEANUP, "冪等キーの整理", "毎日", daily, (d, by) -> {
            // 保存期間を過ぎた冪等キーを削除する
            int deleted = Transactions.inTransaction(dataSource.get(),
                    c -> runs.deleteRequestsBefore(c, LocalDateTime.now(clock).minusDays(REQUEST_RETENTION_DAYS)));
            return "冪等キー " + deleted + " 件を削除";
        }));
        jobs.add(new JobDef(AUDIT_ANCHOR, "監査ログのアンカー記録", "毎日", daily, (d, by) -> {
            // その日のチェーン先頭を DB とアプリケーションログの両方に残す（後からの書き換えを検出できるように）
            Map<String, Object> a = audit.anchor(d);
            return a == null ? "記録済み" : "監査ログ 先頭 " + a.get("lastId") + "（" + String.valueOf(a.get("lastHash")).substring(0, 12) + "…）";
        }));
        jobs.add(new JobDef(AUDIT_RETENTION, "監査ログの保存期間管理", "毎日", daily, (d, by) -> {
            int deleted = audit.purgeExpired();
            return "保存期間を過ぎた監査ログ " + deleted + " 件を削除";
        }));
        // 情報系の取込みは、前日分の勘定系の処理（満期・返済・口座振替）が終わった後に行う
        jobs.add(new JobDef(DW_LOAD, "情報系の取込み", "毎日（前日分）", daily, (d, by) -> {
            Map<String, Object> r = information.loadPreviousDay();
            return "情報系 取込 " + r.get("days") + " 日分（" + r.get("from") + " 〜 " + r.get("to") + "）";
        }));
    }

    /** 期限の来たジョブを実行し、実行したジョブ名を返す。 */
    public List<String> runDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> ran = new ArrayList<>();
        // 定期チェックは 10 分ごとに呼ばれるが、開始時刻前は何もしない
        if (now.toLocalTime().isBefore(START_TIME)) return ran;
        LocalDate today = now.toLocalDate();
        // 各ジョブは独立に実行権を取る。あるジョブが失敗しても残りのジョブは実行される
        for (JobDef j : jobs) {
            if (j.due.test(today) && run(j.name, today, () -> j.body.execute(today, "SYSTEM"))) ran.add(j.name);
        }
        return ran;
    }

    /**
     * 失敗したジョブを再実行する（役席が原因を確かめてから）。成功・実行中のジョブは再実行できない。
     *
     * @param jobName ジョブ名
     * @param runDate 実行日（失敗した実行の日付。日次の集計・照合はその前日を処理する）
     * @param by      実行者
     * @return 実行結果（outcome・detail）
     * @throws BankException ジョブ名が不明（VALIDATION_ERROR）、失敗した実行がない（INVALID_BASE_DATE）場合
     */
    public Map<String, Object> rerun(String jobName, LocalDate runDate, String by) {
        JobDef job = null;
        for (JobDef j : jobs) if (j.name.equals(jobName)) job = j;
        if (job == null || runDate == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
        int deleted = Transactions.inTransaction(dataSource.get(), c -> runs.deleteFailed(c, jobName, runDate));
        if (deleted == 0) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        final JobDef target = job;
        run(jobName, runDate, () -> target.body.execute(runDate, by));
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT * FROM job_run WHERE job_name = ? AND run_date = ?",
                jobName, runDate).get(0));
    }

    /**
     * 銀行バッチの業務一覧：業務ごとの処理内容・実行タイミング・担当ジョブと直近の実行結果。
     *
     * @return 業務一覧と、そのほかのジョブ
     */
    public Map<String, Object> catalog() {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Map<String, Object>> last = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT r.* FROM job_run r WHERE r.run_date = (SELECT MAX(r2.run_date) FROM job_run r2 "
                    + "WHERE r2.job_name = r.job_name)")) {
                last.put((String) r.get("jobName"), r);
            }
            Map<String, JobDef> byName = new LinkedHashMap<>();
            for (JobDef j : jobs) byName.put(j.name, j);
            String[][] business = {
                    {"日次取引集計", "取引種別・通貨ごとの件数・金額を集計", DAILY_SUMMARY},
                    {"残高照合", "口座残高と取引明細の整合性（残高の連続性・ハッシュチェーン・振込の対応）", LEDGER_CHECK},
                    {"預金利息計算", "日々の最終残高で利息を計上（未払利息）、3/1・9/1 に決算して利払い", INTEREST_ACCRUAL, INTEREST},
                    {"融資返済", "約定返済日に返済口座から元金・利息を引落し", LOAN_DUE},
                    {"延滞判定", "未返済の回を延滞にし、延滞日数から債務者区分を更新、貸倒引当金を見直し", LOAN_DUE},
                    {"手数料計算", "他行振込・海外送金の手数料が料金表どおりか、明細の計上と一致するか（月次は月末締めで集計）", FEE_CHECK},
                    {"為替照合", "他行振込の送信・決済・全銀データ・保留を突合", REMIT_RECON},
                    {"会計仕訳集計", "補助元帳から総勘定元帳の日計を作り、貸借・預金・貸出金を照合", GL_POSTING},
                    {"月末締め", "月末残高・月次の取引と収益・未払利息を確定（照合 NG があれば例外）", MONTH_END},
                    {"帳票出力", "取引明細表・日計表（日次）、月末残高一覧・月次集計表（月初）を CSV で作成。残高証明書は随時", REPORTS},
            };
            List<Map<String, Object>> items = new ArrayList<>();
            List<String> covered = new ArrayList<>();
            for (int i = 0; i < business.length; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("no", i + 1);
                m.put("business", business[i][0]);
                m.put("description", business[i][1]);
                List<Map<String, Object>> js = new ArrayList<>();
                for (String name : Arrays.copyOfRange(business[i], 2, business[i].length)) {
                    js.add(jobInfo(byName.get(name), last.get(name)));
                    covered.add(name);
                }
                m.put("jobs", js);
                items.add(m);
            }
            List<Map<String, Object>> others = new ArrayList<>();
            for (JobDef j : jobs) if (!covered.contains(j.name)) others.add(jobInfo(j, last.get(j.name)));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startTime", START_TIME.toString());
            m.put("items", items);
            m.put("otherJobs", others);
            return m;
        });
    }

    private static Map<String, Object> jobInfo(JobDef j, Map<String, Object> last) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", j.name);
        m.put("label", j.label);
        m.put("timing", j.timing);
        m.put("last", last);
        return m;
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

    // 実行権を取ってからジョブを動かす本体
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
        final String finalDetail = detail.length() > 400 ? detail.substring(0, 400) : detail;
        Transactions.inTransaction(dataSource.get(), c -> {
            runs.finish(c, jobName, runDate, finalOutcome, finalDetail, LocalDateTime.now(clock));
            return null;
        });
        // バッチの実行も「誰が（SYSTEM）・何を・結果」として監査ログに残す
        audit.record(LoginUser.SYSTEM, null, "job:" + jobName, runDate.toString(), outcome, finalDetail, null);
        LOG.info("job {} {}: {}", jobName, outcome, finalDetail);
        return true;
    }
}
