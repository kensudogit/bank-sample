package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AccountLifecycleService;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * GET  /api/dormancy?asOf=2026-11-20   (method="report") — 休眠管理の対象一覧（未来日も指定可）
 * POST /api/dormancy-run {"asOf":...}  (method="run")    — 休眠前通知の記録・休眠化（本日以前）
 */
public class DormancyAction extends BaseApiAction {
    private final AccountLifecycleService lifecycle;
    private String asOf;
    private LocalDate asOfDate;

    /** Struts が使う既定コンストラクタ。 */
    public DormancyAction() { this(AccountLifecycleService.getDefault()); }

    /** @param lifecycle 口座ライフサイクルサービス（テスト用） */
    public DormancyAction(AccountLifecycleService lifecycle) {
        super(BankService.getDefault());
        this.lifecycle = lifecycle;
    }

    /** 基準日の形式を検証する（未指定なら本日）。 */
    @Override
    public void validate() {
        asOfDate = InputRules.toDate(InputRules.trim(asOf));
        if (InputRules.emptyToNull(asOf) != null && !InputRules.isReasonableDate(asOfDate)) {
            addFieldError("asOf", getText("validation.date"));
        }
    }

    /**
     * 基準日時点の休眠管理対象を照会する（更新はしない）。
     *
     * @return success
     */
    public String report() {
        return ok(lifecycle.dormancyReport(asOfDate));
    }

    /**
     * 休眠前通知の記録と休眠化を実行する（未来日は INVALID_BASE_DATE）。
     *
     * @return success、または業務エラーの結果名
     */
    public String run() {
        try {
            return ok(lifecycle.runDormancy(asOfDate));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（基準日） */
    @Override
    public String getAuditTarget() { return asOf == null ? "today" : asOf; }

    /** @param asOf 基準日（yyyy-MM-dd） */
    @StrutsParameter public void setAsOf(String asOf) { this.asOf = asOf; }
}
