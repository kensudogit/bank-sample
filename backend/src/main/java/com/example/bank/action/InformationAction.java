package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.InformationService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * 情報系の API（役割は struts.xml）。
 * GET  /api/dw-status                                   （役席）DWH の取込状況
 * POST /api/dw-load {from, to}                          （役席）指定期間の取込み（バックフィル・再取込）
 * GET  /api/mis-dashboard?from=&amp;to=                  （役席）経営ダッシュボード
 * GET  /api/customer-analytics?date=                    （行員・閲覧は監査ログに記録）顧客分析
 * GET  /api/info-report?type=&amp;date=&amp;from=&amp;to=     （役席・閲覧は監査ログに記録）帳票
 */
public class InformationAction extends BaseApiAction {
    private final InformationService information;
    private String from;
    private String to;
    private String date;
    private String type;

    /** Struts が使う既定コンストラクタ。 */
    public InformationAction() { this(InformationService.getDefault()); }

    /** @param information 情報系サービス */
    public InformationAction(InformationService information) {
        super(BankService.getDefault());
        this.information = information;
    }

    /** @return success（取込済みの期間と最終取込） */
    public String status() { return ok(information.status()); }

    /**
     * 指定期間を取り込む。開始日・終了日とも必須。
     *
     * @return success、または業務エラーの結果名
     */
    public String load() {
        try {
            LocalDate f = date(from);
            LocalDate t = date(to);
            if (f == null || t == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(information.load(f, t, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String dashboard() {
        try {
            return ok(information.dashboard(date(from), date(to)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String customers() {
        try {
            return ok(information.customerAnalytics(date(date)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String report() {
        try {
            String t = oneOf(type, InformationService.REPORT_TYPES.toArray(new String[0]));
            return ok(information.report(t, date(date), date(from), date(to)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 取込みは期間、帳票は種類と条件を監査ログに残す
    @Override
    public String getAuditTarget() {
        if (type != null) return type + (date != null ? " " + date : "") + (from != null ? " " + from + ".." + to : "");
        return from != null ? from + ".." + to : date;
    }

    /** @param v 開始日 */
    @StrutsParameter public void setFrom(String v) { from = v; }
    /** @param v 終了日 */
    @StrutsParameter public void setTo(String v) { to = v; }
    /** @param v 基準日 */
    @StrutsParameter public void setDate(String v) { date = v; }
    /** @param v 帳票の種類 */
    @StrutsParameter public void setType(String v) { type = v; }
}
