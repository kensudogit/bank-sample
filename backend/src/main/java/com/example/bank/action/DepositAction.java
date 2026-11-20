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
    private String idempotencyKey;

    public DepositAction() { this(DepositProductService.getDefault()); }

    public DepositAction(DepositProductService deposits) {
        super(BankService.getDefault());
        this.deposits = deposits;
    }

    public String timeRates() { return ok(deposits.timeDepositRates()); }

    public String timeOpen() {
        try {
            String src = accountNo(sourceAccountNo);
            requireAccountAccess(src);
            BigDecimal a = yen(amount);
            if (a == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(deposits.createTimeDeposit(src, a, integer(termMonths, 1, 120), oneOf(instruction, "PAYOUT", "ROLLOVER"),
                    key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String timeGet() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.getTimeDeposit(no));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String timeCancel() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.cancelTimeDeposit(no, key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

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

    public String notices() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            return ok(deposits.listNotices(no));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String fxRates() { return ok(deposits.fxRates()); }

    public String fxRateUpdate() {
        try {
            return ok(deposits.updateFxRate(oneOf(currency, "USD", "EUR"), decimal(ttm), decimal(spread), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String fxExchange() {
        try {
            String from = accountNo(fromAccountNo);
            String to = accountNo(toAccountNo);
            requireAccountAccess(from);
            requireAccountAccess(to);
            String a = amount == null ? "" : amount.trim();
            if (!a.matches("^[0-9]{1,9}(\\.[0-9]{1,2})?$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(deposits.exchange(from, to, a, key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() {
        String target = accountNo != null ? accountNo : sourceAccountNo != null ? sourceAccountNo
                : fromAccountNo != null ? fromAccountNo + "->" + toAccountNo : currency;
        return target == null ? null : target + (amount == null ? "" : " " + amount);
    }

    @StrutsParameter public void setAccountNo(String v) { accountNo = v; }
    @StrutsParameter public void setSourceAccountNo(String v) { sourceAccountNo = v; }
    @StrutsParameter public void setFromAccountNo(String v) { fromAccountNo = v; }
    @StrutsParameter public void setToAccountNo(String v) { toAccountNo = v; }
    @StrutsParameter public void setAmount(String v) { amount = v; }
    @StrutsParameter public void setTermMonths(String v) { termMonths = v; }
    @StrutsParameter public void setInstruction(String v) { instruction = v; }
    @StrutsParameter public void setNoticeId(String v) { noticeId = v; }
    @StrutsParameter public void setCurrency(String v) { currency = v; }
    @StrutsParameter public void setTtm(String v) { ttm = v; }
    @StrutsParameter public void setSpread(String v) { spread = v; }
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }

    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
