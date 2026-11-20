package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.PaymentService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 為替 API（役割は struts.xml。お客さまは本人の口座からの送金・本人の契約のみ）。
 * 国内: banks, remit-send, remittances, remit-cancel, remit-return, zengin-create, zengin-batches, zengin-batch,
 *       zengin-settle, remit-incoming {items:[...]}, remit-resolve
 * 口座振替: billers, mandates, mandate-create, mandate-cancel, billing-import {items:[...]}, debit-requests, debit-process
 * 海外: foreign-send, foreign-remittances, foreign-remittance, foreign-decide
 */
public class PaymentAction extends BaseApiAction {
    private final PaymentService payments;
    private String accountNo;
    private String bankCode;
    private String branchCode;
    private String destType;
    private String destAccountNo;
    private String destNameKana;
    private String amount;
    private String remitId;
    private String batchId;
    private String reason;
    private String decision;
    private String note;
    private String billerCode;
    private String payerNumber;
    private String mandateId;
    private String direction;
    private String status;
    private String asOf;
    private String currency;
    private String feeAccountNo;
    private String beneficiaryName;
    private String beneficiaryAccount;
    private String swiftBic;
    private String country;
    private String purpose;
    private String idempotencyKey;
    private List<Map<String, Object>> items;

    public PaymentAction() { this(PaymentService.getDefault()); }

    public PaymentAction(PaymentService payments) {
        super(BankService.getDefault());
        this.payments = payments;
    }

    private String opt(String v, String... allowed) {
        return v == null || v.trim().isEmpty() ? null : oneOf(v.trim(), allowed);
    }

    // ---------- domestic ----------

    public String banks() { return ok(payments.banks()); }

    public String send() {
        try {
            String from = accountNo(accountNo);
            requireAccountAccess(from);
            BigDecimal a = yen(amount);
            if (a == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            if (bankCode == null || !bankCode.matches("^[0-9]{4}$") || branchCode == null || !branchCode.matches("^[0-9]{3}$")) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            return ok(payments.sendDomestic(from, bankCode, branchCode, oneOf(destType, "1", "2", "4"),
                    accountNo(destAccountNo), required(destNameKana, 30), a, key(idempotencyKey), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String remittances() {
        try {
            return ok(payments.listDomestic(customerScope(), opt(direction, "OUT", "IN"),
                    opt(status, "PENDING", "SENT", "SETTLED", "HELD", "RETURNED", "CREDITED")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    private long ownRemittance() {
        long idValue = id(remitId);
        if (isCustomerUser()) {
            boolean own = payments.listDomestic(customerScope(), null, null).stream()
                    .anyMatch(r -> String.valueOf(r.get("remitId")).equals(String.valueOf(idValue)));
            if (!own) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, remitId);
        }
        return idValue;
    }

    public String cancel() {
        try {
            return ok(payments.cancelDomestic(ownRemittance(), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String returnRemit() {
        try {
            return ok(payments.returnDomestic(id(remitId), required(reason, 100), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String zenginCreate() {
        try {
            return ok(payments.createZenginBatch(who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String zenginBatches() { return ok(payments.zenginBatches()); }

    public String zenginBatch() {
        try {
            return ok(payments.zenginBatch(id(batchId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String zenginSettle() {
        try {
            return ok(payments.settleBatch(id(batchId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String incoming() {
        try {
            if (items == null || items.isEmpty() || items.size() > 100) throw new BankException(ErrorCode.VALIDATION_ERROR);
            List<PaymentService.Incoming> list = new ArrayList<>();
            for (Map<String, Object> m : items) {
                PaymentService.Incoming in = new PaymentService.Incoming();
                in.bankCode = str(m.get("bankCode"));
                in.remitterNameKana = required(str(m.get("remitterNameKana")), 40);
                in.accountNo = accountNo(str(m.get("accountNo")));
                in.destType = m.get("destType") == null ? "1" : oneOf(str(m.get("destType")), "1", "2", "4");
                in.nameKana = required(str(m.get("nameKana")), 30);
                in.amount = yen(str(m.get("amount")));
                if (in.bankCode == null || !in.bankCode.matches("^[0-9]{4}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
                list.add(in);
            }
            return ok(payments.receiveIncoming(list, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String resolve() {
        try {
            boolean credit = "CREDIT".equals(oneOf(decision, "CREDIT", "RETURN"));
            return ok(payments.resolveHeld(id(remitId), credit ? accountNo(accountNo) : null, credit, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // ---------- direct debit ----------

    public String billers() { return ok(payments.billers()); }

    public String mandates() { return ok(payments.listMandates(customerScope())); }

    public String mandateCreate() {
        try {
            String no = accountNo(accountNo);
            requireAccountAccess(no);
            String payer = required(payerNumber, 20);
            if (!payer.matches("^[A-Za-z0-9-]{1,20}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(payments.registerMandate(no, required(billerCode, 8), payer, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String mandateCancel() {
        try {
            long idValue = id(mandateId);
            requireOwner(payments.mandateOwner(idValue), ErrorCode.MANDATE_NOT_FOUND, mandateId);
            return ok(payments.cancelMandate(idValue));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String billingImport() {
        try {
            if (items == null || items.isEmpty() || items.size() > 1000) throw new BankException(ErrorCode.VALIDATION_ERROR);
            List<PaymentService.Billing> list = new ArrayList<>();
            for (Map<String, Object> m : items) {
                PaymentService.Billing b = new PaymentService.Billing();
                b.billerCode = required(str(m.get("billerCode")), 8);
                b.payerNumber = required(str(m.get("payerNumber")), 20);
                b.amount = yen(str(m.get("amount")));
                b.dueDate = date(str(m.get("dueDate")));
                list.add(b);
            }
            return ok(payments.importBilling(list));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String debitRequests() { return ok(payments.listDebitRequests(customerScope())); }

    public String debitProcess() {
        try {
            java.time.LocalDate d = date(asOf);
            java.time.LocalDate today = java.time.LocalDate.now();
            if (d != null && d.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
            return ok(payments.processDirectDebits(d == null ? today : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // ---------- foreign ----------

    public String foreignSend() {
        try {
            PaymentService.ForeignOrder o = new PaymentService.ForeignOrder();
            o.accountNo = accountNo(accountNo);
            o.feeAccountNo = accountNo(feeAccountNo);
            requireAccountAccess(o.accountNo);
            requireAccountAccess(o.feeAccountNo);
            o.currency = oneOf(currency, "USD", "EUR");
            o.amount = amount == null ? "" : amount.trim();
            if (!o.amount.matches("^[0-9]{1,9}(\\.[0-9]{1,2})?$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
            o.beneficiaryName = required(beneficiaryName, 70);
            o.beneficiaryAccount = required(beneficiaryAccount, 34);
            o.swiftBic = required(swiftBic, 11);
            o.country = required(country, 2);
            o.purpose = required(purpose, 70);
            return ok(payments.sendForeign(o, key(idempotencyKey), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String foreignList() {
        try {
            return ok(payments.listForeign(customerScope(), opt(status, "SENT", "HELD", "REJECTED")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String foreignGet() {
        try {
            Map<String, Object> r = payments.getForeign(id(remitId));
            requireOwner((String) r.get("customerId"), ErrorCode.REMITTANCE_NOT_FOUND, remitId);
            return ok(r);
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String foreignDecide() {
        try {
            boolean release = "RELEASE".equals(oneOf(decision, "RELEASE", "REJECT"));
            return ok(payments.decideForeign(id(remitId), release, required(note, 200), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    @Override
    public String getAuditTarget() {
        if (remitId != null) return "remit " + remitId + (decision == null ? "" : " " + decision);
        if (batchId != null) return "zengin " + batchId;
        if (mandateId != null) return "mandate " + mandateId;
        if (accountNo != null) return accountNo + (amount == null ? "" : " " + amount)
                + (bankCode == null ? "" : " -> " + bankCode) + (country == null ? "" : " -> " + country);
        return items == null ? null : items.size() + " items";
    }

    @StrutsParameter public void setAccountNo(String v) { accountNo = v; }
    @StrutsParameter public void setBankCode(String v) { bankCode = v; }
    @StrutsParameter public void setBranchCode(String v) { branchCode = v; }
    @StrutsParameter public void setDestType(String v) { destType = v; }
    @StrutsParameter public void setDestAccountNo(String v) { destAccountNo = v; }
    @StrutsParameter public void setDestNameKana(String v) { destNameKana = v; }
    @StrutsParameter public void setAmount(String v) { amount = v; }
    @StrutsParameter public void setRemitId(String v) { remitId = v; }
    @StrutsParameter public void setBatchId(String v) { batchId = v; }
    @StrutsParameter public void setReason(String v) { reason = v; }
    @StrutsParameter public void setDecision(String v) { decision = v; }
    @StrutsParameter public void setNote(String v) { note = v; }
    @StrutsParameter public void setBillerCode(String v) { billerCode = v; }
    @StrutsParameter public void setPayerNumber(String v) { payerNumber = v; }
    @StrutsParameter public void setMandateId(String v) { mandateId = v; }
    @StrutsParameter public void setDirection(String v) { direction = v; }
    @StrutsParameter public void setStatus(String v) { status = v; }
    @StrutsParameter public void setAsOf(String v) { asOf = v; }
    @StrutsParameter public void setCurrency(String v) { currency = v; }
    @StrutsParameter public void setFeeAccountNo(String v) { feeAccountNo = v; }
    @StrutsParameter public void setBeneficiaryName(String v) { beneficiaryName = v; }
    @StrutsParameter public void setBeneficiaryAccount(String v) { beneficiaryAccount = v; }
    @StrutsParameter public void setSwiftBic(String v) { swiftBic = v; }
    @StrutsParameter public void setCountry(String v) { country = v; }
    @StrutsParameter public void setPurpose(String v) { purpose = v; }
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }
    @StrutsParameter(depth = 2) public void setItems(List<Map<String, Object>> v) { items = v; }

    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
