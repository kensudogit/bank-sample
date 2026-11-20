package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AuditService;
import com.example.bank.service.AuthService;
import com.example.bank.service.BankService;
import com.example.bank.service.JobRunner;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理系 API（役割は struts.xml で制限）。
 * GET  /api/audit-log?from=&to=&userId=&action=&outcome=&page=&size=  (method="auditLog")     監査ログ検索
 * GET  /api/audit-verify                                              (method="auditVerify")  ハッシュチェーン検証
 * GET  /api/jobs                                                      (method="jobs")         バッチ実行履歴
 * POST /api/jobs-run {}                                               (method="runJobs")      期限の来たバッチを今すぐ実行
 * POST /api/customer-login-issue {"customerId"}                       (method="issueLogin")   顧客ログインの発行
 */
public class AdminAction extends BaseApiAction {
    private final AuditService audit;
    private final JobRunner jobs;
    private final AuthService auth;
    private String from;
    private String to;
    private String userId;
    private String auditAction;
    private String outcome;
    private String page;
    private String size;
    private String customerId;
    private String accountNo;

    /** Struts が使う既定コンストラクタ。 */
    public AdminAction() { this(AuditService.getDefault(), JobRunner.getDefault(), AuthService.getDefault()); }

    /**
     * @param audit 監査サービス
     * @param jobs  バッチ実行
     * @param auth  認証サービス
     */
    public AdminAction(AuditService audit, JobRunner jobs, AuthService auth) {
        super(BankService.getDefault());
        this.audit = audit;
        this.jobs = jobs;
        this.auth = auth;
    }

    /**
     * 監査ログを検索する。
     *
     * @return success、入力不正なら input
     */
    public String auditLog() {
        LocalDate fromDate = InputRules.toDate(InputRules.trim(from));
        LocalDate toDate = InputRules.toDate(InputRules.trim(to));
        if (InputRules.emptyToNull(from) != null && !InputRules.isReasonableDate(fromDate)) {
            addFieldError("from", getText("validation.date"));
        }
        if (InputRules.emptyToNull(to) != null && !InputRules.isReasonableDate(toDate)) {
            addFieldError("to", getText("validation.date"));
        }
        String p = InputRules.emptyToNull(page) == null ? "1" : page.trim();
        String s = InputRules.emptyToNull(size) == null ? "50" : size.trim();
        // 監査ログは件数が多いため、1 ページの上限を 200 件にして一度に大量の行を返さない
        if (!InputRules.isPositiveInt(p) || !InputRules.isPositiveInt(s) || Integer.parseInt(s) > 200) {
            addFieldError("page", getText("validation.page"));
        }
        String o = InputRules.emptyToNull(outcome);
        if (o != null && !o.matches("SUCCESS|FAILURE|DENIED")) addFieldError("outcome", getText("validation.outcome"));
        // このメソッドは validate() を使わず自分で検証しているため、エラーがあればここで input を返す
        if (hasFieldErrors()) return INPUT;
        try {
            return ok(audit.search(fromDate, toDate, InputRules.emptyToNull(userId), InputRules.emptyToNull(auditAction),
                    o, Integer.parseInt(p), Integer.parseInt(s)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 監査ログのハッシュチェーンを先頭から検証する（改ざん・削除の検出）。
     *
     * @return success（検証結果を data に含める）
     */
    public String auditVerify() {
        return ok(audit.verify());
    }

    /**
     * バッチの設定と直近の実行履歴を返す。
     *
     * @return success
     */
    public String jobs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", "true".equalsIgnoreCase(System.getenv("BANK_JOBS_ENABLED")));
        m.put("startTime", JobRunner.START_TIME.toString());
        m.put("runs", jobs.recentRuns(30));
        return ok(m);
    }

    /**
     * 期限の来たバッチを今すぐ実行する（同じ日の二重実行は JobRunner が DB で防ぐ）。
     *
     * @return success（実行結果を data に含める）
     */
    public String runJobs() {
        return ok(jobs.runDue());
    }

    /** 元帳の整合性検証（残高の連続性・残高照合・振込の対応・ハッシュチェーン）。accountNo 省略で全口座。 */
    public String ledgerVerify() {
        try {
            String no = InputRules.emptyToNull(accountNo);
            // 口座番号は任意だが、指定された場合は形式を確認する
            if (no != null && !InputRules.isAccountNo(no)) throw new com.example.bank.domain.BankException(
                    com.example.bank.domain.ErrorCode.VALIDATION_ERROR);
            return ok(com.example.bank.service.LedgerService.getDefault().verify(no));
        } catch (com.example.bank.domain.BankException e) {
            return fail(e);
        }
    }

    /** 取引の追跡: 明細と、それを実行した操作（監査ログ）の対応。 */
    public String ledgerTrace() {
        try {
            String no = InputRules.trim(accountNo);
            // 追跡は 1 口座単位なので口座番号は必須
            if (!InputRules.isAccountNo(no)) throw new com.example.bank.domain.BankException(
                    com.example.bank.domain.ErrorCode.VALIDATION_ERROR);
            return ok(com.example.bank.service.LedgerService.getDefault().trace(no, 50));
        } catch (com.example.bank.domain.BankException e) {
            return fail(e);
        }
    }

    /**
     * 顧客のログイン ID と初期パスワードを発行する（初回ログイン時にパスワード変更を求める）。
     *
     * @return success、入力不正なら input、発行済みなら conflict
     */
    public String issueLogin() {
        customerId = InputRules.trim(customerId);
        if (!InputRules.isCustomerId(customerId)) {
            addFieldError("customerId", getText("validation.customerId"));
            return INPUT;
        }
        try {
            return ok(auth.issueCustomerLogin(customerId));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（ログイン発行時の顧客番号） */
    @Override
    public String getAuditTarget() { return customerId; }

    // 以下は検索条件などの入力。@StrutsParameter を付けたセッターだけがリクエストから値を受け取れる
    /** @param from 検索期間の開始日 */
    @StrutsParameter public void setFrom(String from) { this.from = from; }
    /** @param to 検索期間の終了日 */
    @StrutsParameter public void setTo(String to) { this.to = to; }
    /** @param userId 操作者で絞り込む */
    @StrutsParameter public void setUserId(String userId) { this.userId = userId; }
    /** 監査ログの操作名で絞り込み（Struts の action パラメーター名と衝突しないよう auditAction） */
    @StrutsParameter public void setAuditAction(String auditAction) { this.auditAction = auditAction; }
    /** @param outcome 結果で絞り込む（SUCCESS / FAILURE / DENIED） */
    @StrutsParameter public void setOutcome(String outcome) { this.outcome = outcome; }
    /** @param page ページ番号 */
    @StrutsParameter public void setPage(String page) { this.page = page; }
    /** @param size 1 ページの件数 */
    @StrutsParameter public void setSize(String size) { this.size = size; }
    /** @param customerId ログインを発行する顧客番号 */
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
    /** @param accountNo 元帳検証・追跡の対象口座 */
    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
