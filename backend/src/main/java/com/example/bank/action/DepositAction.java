package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.DepositProductService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;

/**
 * 預金商品 API（役割は struts.xml）。
 * GET  /api/time-rates                         定期預金金利
 * POST /api/time-open   {sourceAccountNo, amount, termMonths, instruction, idempotencyKey}
 * GET  /api/time-deposit?accountNo=
 * POST /api/time-cancel {accountNo, idempotencyKey}        中途解約
 * POST /api/notice-create {accountNo, amount}               通知預金の払出し通知
 * POST /api/notice-cancel {accountNo, noticeId}
 * GET  /api/notices?accountNo=
 * GET  /api/fx-rates
 * POST /api/fx-rate-update {currency, ttm, spread}         （役席）
 * POST /api/fx-exchange {fromAccountNo, toAccountNo, amount, idempotencyKey}
 */
public class DepositAction extends BaseApiAction {
    private final DepositProductService deposits;
    private String accountNo;
    private String sourceAccountNo;
    private String fromAccountNo;
    private String toAccountNo;
    private String amount;
    private String termMonths;
    private String instruction;
    private String noticeId;
    private String currency;
    private String ttm;
    private String spread;
    // 1 つの Action で複数の API を扱うため、各メソッドが必要な項目だけを BaseApiAction の解析メソッドで検証する
    private String idempotencyKey;

    /** Struts が使う既定コンストラクタ。 */
    public DepositAction() { this(DepositProductService.getDefault()); }

    /** @param deposits 預金商品サービス（テスト用） */
    public DepositAction(DepositProductService deposits) {
        super(BankService.getDefault());
        this.deposits = deposits;
    }

    /** @return success（定期預金の期間別金利） */
    public String timeRates() { return ok(deposits.timeDepositRates()); }

    /**
     * 普通預金から資金を移して定期預金を作成する。
     *
     * @return success、または業務エラーの結果名
     */
    public String timeOpen() {
        try {
            String src = accountNo(sourceAccountNo);
            // 資金の出し手の口座が本人のものであることを確認する
            requireAccountAccess(src);
            BigDecimal a = yen(amount);
            if (a == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(deposits.createTimeDeposit(src, a, integer(termMonths, 1, 120), oneOf(instruction, "PAYOUT", "ROLLOVER"),
                    key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 定期預金の内容（元本・満期日・満期時の取扱い）を返す。
     *
     * @return success、または業務エラーの結果名
     */
    public String timeGet() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.getTimeDeposit(no));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 定期預金を中途解約する（中途解約利率で利息を計算する）。
     *
     * @return success、または業務エラーの結果名
     */
    public String timeCancel() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.cancelTimeDeposit(no, key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 通知預金の払出し通知を登録する（通知から一定日数後に払い出せる）。
     *
     * @return success、または業務エラーの結果名
     */
    public String noticeCreate() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            BigDecimal a = yen(amount);
            if (a == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(deposits.notifyWithdrawal(no, a));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 払出し通知を取り消し、残りの通知一覧を返す。
     *
     * @return success、または業務エラーの結果名
     */
    public String noticeCancel() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            deposits.cancelNotice(no, id(noticeId));
            return ok(deposits.listNotices(no));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 口座の払出し通知一覧を返す。
     *
     * @return success、または業務エラーの結果名
     */
    public String notices() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.listNotices(no));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（外貨の為替レート） */
    public String fxRates() { return ok(deposits.fxRates()); }

    /**
     * 為替レート（仲値と片側スプレッド）を更新する（役席のみ。権限は struts.xml で制限）。
     *
     * @return success、または業務エラーの結果名
     */
    public String fxRateUpdate() {
        try {
            return ok(deposits.updateFxRate(oneOf(currency, "USD", "EUR"), decimal(ttm), decimal(spread), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 円口座と外貨口座の間で両替する。
     *
     * @return success、または業務エラーの結果名
     */
    public String fxExchange() {
        try {
            String from = accountNo(fromAccountNo);
            String to = accountNo(toAccountNo);
            requireAccountAccess(from);
            // 両替は本人名義の口座間に限るため、入金先も確認する
            requireAccountAccess(to);
            String a = amount == null ? "" : amount.trim();
            // 外貨はセント単位まで入力できるため、小数 2 桁まで許す（円口座側の端数処理はサービスで行う）
            if (!a.matches("^[0-9]{1,9}(\\.[0-9]{1,2})?$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(deposits.exchange(from, to, a, key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // どの API でも監査ログに対象が残るよう、設定されている口座番号（なければ通貨）を選ぶ
    @Override
    public String getAuditTarget() {
        String target = accountNo != null ? accountNo : sourceAccountNo != null ? sourceAccountNo
                : fromAccountNo != null ? fromAccountNo + "->" + toAccountNo : currency;
        return target == null ? null : target + (amount == null ? "" : " " + amount);
    }

    // 以下は JSON ボディ・クエリから受け取る入力。各 API で使う項目だけが設定される
    /** @param v 口座番号 */
    @StrutsParameter public void setAccountNo(String v) { accountNo = v; }
    /** @param v 定期預金の資金を出す口座番号 */
    @StrutsParameter public void setSourceAccountNo(String v) { sourceAccountNo = v; }
    /** @param v 両替の払出し口座番号 */
    @StrutsParameter public void setFromAccountNo(String v) { fromAccountNo = v; }
    /** @param v 両替の入金口座番号 */
    @StrutsParameter public void setToAccountNo(String v) { toAccountNo = v; }
    /** @param v 金額 */
    @StrutsParameter public void setAmount(String v) { amount = v; }
    /** @param v 定期預金の期間（月数） */
    @StrutsParameter public void setTermMonths(String v) { termMonths = v; }
    /** @param v 満期時の取扱い（PAYOUT: 払出し / ROLLOVER: 自動継続） */
    @StrutsParameter public void setInstruction(String v) { instruction = v; }
    /** @param v 払出し通知の ID */
    @StrutsParameter public void setNoticeId(String v) { noticeId = v; }
    /** @param v 通貨（USD / EUR） */
    @StrutsParameter public void setCurrency(String v) { currency = v; }
    /** @param v 仲値（TTM） */
    @StrutsParameter public void setTtm(String v) { ttm = v; }
    /** @param v 仲値からの片側スプレッド */
    @StrutsParameter public void setSpread(String v) { spread = v; }
    /** @param v 冪等キー */
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }

    // 冪等性インターセプターと監査ログが参照するキー。空のときは「キーなし」として null を返す
    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
