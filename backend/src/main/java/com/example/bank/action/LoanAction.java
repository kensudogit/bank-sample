package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.LoanService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;

/**
 * 融資 API（役割は struts.xml。お客さまは本人の申込・融資のみ）。
 * GET  /api/loan-applications?status=          GET /api/loan-application?applicationId=
 * POST /api/loan-apply {customerId, product, amount, termMonths, repaymentMethod, purpose, annualIncome,
 *                       existingDebtAnnual, collateralValue, syndicateTotal}
 * POST /api/loan-screen {applicationId}                      （行員）
 * POST /api/loan-decide {applicationId, decision, note, rate}（役席）
 * POST /api/loan-collateral {applicationId, collateralType, description, appraisalValue, lienRank, guarantorName}
 * POST /api/loan-participant {applicationId, lenderName, shareAmount, role}
 * POST /api/loan-contract {applicationId, repaymentAccountNo, paymentDay}
 * POST /api/loan-disburse {loanId, idempotencyKey}           （行員）融資実行
 * GET  /api/loans?status=                     GET /api/loan?loanId=
 * POST /api/loan-draw / loan-repay / loan-prepay {loanId, amount, idempotencyKey}
 * POST /api/loan-collection {loanId, actionType, note, promisedDate}
 * POST /api/loan-writeoff {loanId, note} / loan-class {loanId, borrowerClass}   （役席）
 * POST /api/loan-process-due {asOf}                          （役席）約定返済・延滞判定の手動実行
 */
public class LoanAction extends BaseApiAction {
    private final LoanService loans;
    private String applicationId;
    private String loanId;
    private String customerId;
    private String product;
    private String amount;
    private String termMonths;
    private String repaymentMethod;
    private String purpose;
    private String annualIncome;
    private String existingDebtAnnual;
    private String collateralValue;
    private String syndicateTotal;
    private String decision;
    private String note;
    private String rate;
    private String collateralType;
    private String description;
    private String appraisalValue;
    private String lienRank;
    private String guarantorName;
    private String lenderName;
    private String shareAmount;
    private String role;
    private String repaymentAccountNo;
    private String paymentDay;
    private String actionType;
    private String promisedDate;
    private String borrowerClass;
    private String status;
    private String asOf;
    private String idempotencyKey;

    public LoanAction() { this(LoanService.getDefault()); }

    public LoanAction(LoanService loans) {
        super(BankService.getDefault());
        this.loans = loans;
    }

    private String status() {
        return status == null || status.trim().isEmpty() ? null
                : oneOf(status.trim(), "APPLIED", "SCREENED", "APPROVED", "REJECTED", "CONTRACTED", "EXECUTED", "WITHDRAWN",
                        "ACTIVE", "DELINQUENT", "PAID_OFF", "WRITTEN_OFF");
    }

    public String applications() {
        try {
            return ok(loans.listApplications(customerScope(), status()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String application() {
        try {
            long appId = id(applicationId);
            java.util.Map<String, Object> app = loans.getApplication(appId);
            requireOwner((String) app.get("customerId"), ErrorCode.LOAN_NOT_FOUND, applicationId);
            return ok(app);
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String apply() {
        try {
            LoanService.Application a = new LoanService.Application();
            a.customerId = ownCustomerOr(required(customerId, 10));
            a.product = oneOf(product, "HOUSING", "BUSINESS", "CARD", "OVERDRAFT", "SYNDICATED");
            a.amount = yen(amount);
            boolean revolving = "CARD".equals(a.product) || "OVERDRAFT".equals(a.product);
            a.termMonths = revolving ? 12 : integer(termMonths, 1, 420);
            a.repaymentMethod = revolving ? "REVOLVING" : oneOf(repaymentMethod, "EQUAL_PAYMENT", "EQUAL_PRINCIPAL");
            a.purpose = text(purpose, 200);
            a.annualIncome = yen(annualIncome);
            BigDecimal debt = yen(existingDebtAnnual);
            a.existingDebtAnnual = debt == null ? BigDecimal.ZERO : debt;
            BigDecimal coll = yen(collateralValue);
            a.collateralValue = coll == null ? BigDecimal.ZERO : coll;
            a.syndicateTotal = yen(syndicateTotal);
            if (a.amount == null || a.annualIncome == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(loans.apply(a, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String screen() {
        try {
            return ok(loans.screen(id(applicationId), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String decide() {
        try {
            boolean approve = "APPROVE".equals(oneOf(decision, "APPROVE", "REJECT"));
            return ok(loans.decide(id(applicationId), approve, text(note, 400), decimal(rate), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String collateral() {
        try {
            BigDecimal appraisal = yen(appraisalValue);
            Integer rank = lienRank == null || lienRank.trim().isEmpty() ? null : integer(lienRank, 1, 9);
            return ok(loans.addCollateral(id(applicationId),
                    oneOf(collateralType, "REAL_ESTATE", "DEPOSIT", "SECURITIES", "GUARANTOR", "GUARANTEE_CO"),
                    required(description, 200), appraisal, rank, text(guarantorName, 100), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String participant() {
        try {
            BigDecimal share = yen(shareAmount);
            if (share == null || share.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(loans.addParticipant(id(applicationId), required(lenderName, 100), share,
                    oneOf(role, "ARRANGER", "PARTICIPANT")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String contract() {
        try {
            return ok(loans.contract(id(applicationId), accountNo(repaymentAccountNo), integer(paymentDay, 1, 28), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String disburse() {
        try {
            return ok(loans.disburse(id(loanId), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String loans() {
        try {
            return ok(loans.listLoans(customerScope(), status()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String loan() {
        try {
            java.util.Map<String, Object> l = loans.getLoan(id(loanId));
            requireOwner((String) l.get("customerId"), ErrorCode.LOAN_NOT_FOUND, loanId);
            return ok(l);
        } catch (BankException e) {
            return fail(e);
        }
    }

    private long ownLoan() {
        long idValue = id(loanId);
        if (isCustomerUser()) requireOwner(loans.loanOwner(idValue), ErrorCode.LOAN_NOT_FOUND, loanId);
        return idValue;
    }

    private BigDecimal positiveAmount() {
        BigDecimal a = yen(amount);
        if (a == null || a.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return a;
    }

    public String draw() {
        try {
            return ok(loans.draw(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String repay() {
        try {
            return ok(loans.repay(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String prepay() {
        try {
            return ok(loans.prepay(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String collection() {
        try {
            return ok(loans.addCollectionAction(id(loanId), oneOf(actionType, "CALL", "LETTER", "VISIT", "PROMISE", "LEGAL"),
                    required(note, 400), date(promisedDate), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String writeOff() {
        try {
            return ok(loans.writeOff(id(loanId), required(note, 90), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String setClass() {
        try {
            return ok(loans.setBorrowerClass(id(loanId),
                    oneOf(borrowerClass, "NORMAL", "WATCH", "SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String processDue() {
        try {
            java.time.LocalDate d = date(asOf);
            java.time.LocalDate today = java.time.LocalDate.now();
            if (d != null && d.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
            return ok(loans.processDue(d == null ? today : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() {
        if (loanId != null) return "loan " + loanId + (amount == null ? "" : " " + amount);
        if (applicationId != null) return "application " + applicationId + (decision == null ? "" : " " + decision);
        return product == null ? null : product + " " + customerId + " " + amount;
    }

    @StrutsParameter public void setApplicationId(String v) { applicationId = v; }
    @StrutsParameter public void setLoanId(String v) { loanId = v; }
    @StrutsParameter public void setCustomerId(String v) { customerId = v; }
    @StrutsParameter public void setProduct(String v) { product = v; }
    @StrutsParameter public void setAmount(String v) { amount = v; }
    @StrutsParameter public void setTermMonths(String v) { termMonths = v; }
    @StrutsParameter public void setRepaymentMethod(String v) { repaymentMethod = v; }
    @StrutsParameter public void setPurpose(String v) { purpose = v; }
    @StrutsParameter public void setAnnualIncome(String v) { annualIncome = v; }
    @StrutsParameter public void setExistingDebtAnnual(String v) { existingDebtAnnual = v; }
    @StrutsParameter public void setCollateralValue(String v) { collateralValue = v; }
    @StrutsParameter public void setSyndicateTotal(String v) { syndicateTotal = v; }
    @StrutsParameter public void setDecision(String v) { decision = v; }
    @StrutsParameter public void setNote(String v) { note = v; }
    @StrutsParameter public void setRate(String v) { rate = v; }
    @StrutsParameter public void setCollateralType(String v) { collateralType = v; }
    @StrutsParameter public void setDescription(String v) { description = v; }
    @StrutsParameter public void setAppraisalValue(String v) { appraisalValue = v; }
    @StrutsParameter public void setLienRank(String v) { lienRank = v; }
    @StrutsParameter public void setGuarantorName(String v) { guarantorName = v; }
    @StrutsParameter public void setLenderName(String v) { lenderName = v; }
    @StrutsParameter public void setShareAmount(String v) { shareAmount = v; }
    @StrutsParameter public void setRole(String v) { role = v; }
    @StrutsParameter public void setRepaymentAccountNo(String v) { repaymentAccountNo = v; }
    @StrutsParameter public void setPaymentDay(String v) { paymentDay = v; }
    @StrutsParameter public void setActionType(String v) { actionType = v; }
    @StrutsParameter public void setPromisedDate(String v) { promisedDate = v; }
    @StrutsParameter public void setBorrowerClass(String v) { borrowerClass = v; }
    @StrutsParameter public void setStatus(String v) { status = v; }
    @StrutsParameter public void setAsOf(String v) { asOf = v; }
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }

    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
