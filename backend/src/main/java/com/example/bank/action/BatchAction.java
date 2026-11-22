package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankBatchService;
import com.example.bank.service.BankService;
import com.example.bank.service.JobRunner;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * 銀行バッチの API（役割は struts.xml）。
 * GET  /api/batch-catalog                         （役席）業務一覧・担当ジョブ・直近の結果
 * POST /api/batch-rerun {jobName, runDate}         （役席）失敗したジョブの再実行
 * GET  /api/batch-daily?date=                     （役席）業務日のバッチ結果（集計・利息・手数料・照合・元帳）
 * GET  /api/month-closes                          （役席）月末締めの一覧
 * GET  /api/report-outputs                        （役席）帳票の一覧
 * GET  /api/report-output?reportId=               （役席・閲覧は監査ログに記録）帳票の本文（CSV）
 * GET  /api/balance-certificate?accountNo=&amp;date=  （お客さまは本人の口座のみ。閲覧は監査ログに記録）残高証明書
 */
public class BatchAction extends BaseApiAction {
    private final JobRunner jobs;
    private final BankBatchService batch;
    private String jobName;
    private String runDate;
    private String date;
    private String reportId;
    private String accountNo;

    /** Struts が使う既定コンストラクタ。 */
    public BatchAction() { this(JobRunner.getDefault(), BankBatchService.getDefault()); }

    /**
     * @param jobs  夜間バッチ
     * @param batch 銀行バッチの集計・照合・帳票
     */
    public BatchAction(JobRunner jobs, BankBatchService batch) {
        super(BankService.getDefault());
        this.jobs = jobs;
        this.batch = batch;
    }

    /** @return success（業務一覧） */
    public String catalog() { return ok(jobs.catalog()); }

    /** @return success、または業務エラーの結果名 */
    public String rerun() {
        try {
            String name = required(jobName, 30);
            LocalDate d = date(runDate);
            if (d == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(jobs.rerun(name, d, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String daily() {
        try {
            LocalDate d = date(date);
            return ok(batch.daily(d == null ? LocalDate.now().minusDays(1) : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（月末締めの一覧） */
    public String monthCloses() { return ok(batch.monthCloses()); }

    /** @return success（帳票の一覧） */
    public String reports() { return ok(batch.reportList()); }

    /** @return success、または業務エラーの結果名 */
    public String report() {
        try {
            return ok(batch.report(id(reportId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String certificate() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            LocalDate d = date(date);
            return ok(batch.balanceCertificate(no, d == null ? LocalDate.now() : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 再実行はジョブ名と日付、帳票は番号、残高証明は口座と日付を監査ログに残す
    @Override
    public String getAuditTarget() {
        if (jobName != null) return jobName + " " + runDate;
        if (reportId != null) return "report " + reportId;
        return accountNo == null ? date : accountNo + " " + date;
    }

    /** @param v ジョブ名 */
    @StrutsParameter public void setJobName(String v) { jobName = v; }
    /** @param v 実行日 */
    @StrutsParameter public void setRunDate(String v) { runDate = v; }
    /** @param v 業務日・証明日 */
    @StrutsParameter public void setDate(String v) { date = v; }
    /** @param v 帳票番号 */
    @StrutsParameter public void setReportId(String v) { reportId = v; }
    /** @param v 口座番号 */
    @StrutsParameter public void setAccountNo(String v) { accountNo = v; }
}
