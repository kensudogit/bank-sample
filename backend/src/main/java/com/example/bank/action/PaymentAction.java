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
    // 被仕向データ・請求データの一括取込用（JSON 配列をそのまま受け取り、1 件ずつ検証する）
    private List<Map<String, Object>> items;

    /** Struts が使う既定コンストラクタ。 */
    public PaymentAction() { this(PaymentService.getDefault()); }

    /** @param payments 為替サービス（テスト用） */
    public PaymentAction(PaymentService payments) {
        super(BankService.getDefault());
        this.payments = payments;
    }

    // 任意の区分値: 未指定は null（絞り込みなし）、指定時は許可された値か確かめる
    private String opt(String v, String... allowed) {
        return v == null || v.trim().isEmpty() ? null : oneOf(v.trim(), allowed);
    }

    // ---------- domestic ----------

    /** @return success（振込先に指定できる金融機関の一覧） */
    public String banks() { return ok(payments.banks()); }

    /**
     * 他行宛て振込を受け付ける（資金は即時に引き落とし、全銀データの作成時に送信する）。
     *
     * @return success、または業務エラーの結果名
     */
    public String send() {
        try {
            String from = accountNo(accountNo);
            requireAccountAccess(from);
            BigDecimal a = yen(amount);
            if (a == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            // 全銀の金融機関コードは 4 桁、支店コードは 3 桁
            if (bankCode == null || !bankCode.matches("^[0-9]{4}$") || branchCode == null || !branchCode.matches("^[0-9]{3}$")) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            return ok(payments.sendDomestic(from, bankCode, branchCode, oneOf(destType, "1", "2", "4"),
                    accountNo(destAccountNo), required(destNameKana, 30), a, key(idempotencyKey), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 国内送金（仕向・被仕向）の一覧。
     *
     * @return success、または業務エラーの結果名
     */
    public String remittances() {
        try {
            return ok(payments.listDomestic(customerScope(), opt(direction, "OUT", "IN"),
                    opt(status, "PENDING", "SENT", "SETTLED", "HELD", "RETURNED", "CREDITED")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 顧客ログインなら、本人の送金一覧に含まれる送金だけを操作できるようにする
    private long ownRemittance() {
        long idValue = id(remitId);
        if (isCustomerUser()) {
            boolean own = payments.listDomestic(customerScope(), null, null).stream()
                    .anyMatch(r -> String.valueOf(r.get("remitId")).equals(String.valueOf(idValue)));
            if (!own) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, remitId);
        }
        return idValue;
    }

    /**
     * 未送信の振込を取り消し、資金を口座へ戻す。
     *
     * @return success、または業務エラーの結果名
     */
    public String cancel() {
        try {
            return ok(payments.cancelDomestic(ownRemittance(), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 送信済みの振込を組戻す（行員）。
     *
     * @return success、または業務エラーの結果名
     */
    public String returnRemit() {
        try {
            return ok(payments.returnDomestic(id(remitId), required(reason, 100), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 未送信の振込をまとめて全銀フォーマットのデータを作成する。
     *
     * @return success、または業務エラーの結果名
     */
    public String zenginCreate() {
        try {
            return ok(payments.createZenginBatch(who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（全銀データの作成履歴） */
    public String zenginBatches() { return ok(payments.zenginBatches()); }

    /**
     * 全銀データ 1 件の内容（レコード）を返す。
     *
     * @return success、または業務エラーの結果名
     */
    public String zenginBatch() {
        try {
            return ok(payments.zenginBatch(id(batchId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 全銀データの決済完了を記録する（送金を SETTLED にする）。
     *
     * @return success、または業務エラーの結果名
     */
    public String zenginSettle() {
        try {
            return ok(payments.settleBatch(id(batchId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 他行からの振込（被仕向）を取り込み、名義が一致すれば入金、不一致なら保留にする。
     *
     * @return success、または業務エラーの結果名
     */
    public String incoming() {
        try {
            // 1 回の取込件数に上限を設け、1 トランザクションが長くなりすぎないようにする
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
                // 参照番号は取込の重複判定に使うため必須（同じ振込を 2 回入金しない）
                in.referenceNo = str(m.get("referenceNo"));
                if (in.referenceNo == null || !in.referenceNo.matches("^[A-Za-z0-9-]{1,40}$")) {
                    throw new BankException(ErrorCode.VALIDATION_ERROR);
                }
                if (in.bankCode == null || !in.bankCode.matches("^[0-9]{4}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
                list.add(in);
            }
            return ok(payments.receiveIncoming(list, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 保留中の被仕向振込を入金（口座を指定）または返却する（行員）。
     *
     * @return success、または業務エラーの結果名
     */
    public String resolve() {
        try {
            boolean credit = "CREDIT".equals(oneOf(decision, "CREDIT", "RETURN"));
            return ok(payments.resolveHeld(id(remitId), credit ? accountNo(accountNo) : null, credit, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // ---------- direct debit ----------

    /** @return success（口座振替の収納企業一覧） */
    public String billers() { return ok(payments.billers()); }

    /** @return success（口座振替契約の一覧。顧客は本人の契約のみ） */
    public String mandates() { return ok(payments.listMandates(customerScope())); }

    /**
     * 口座振替の契約を登録する。
     *
     * @return success、または業務エラーの結果名
     */
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

    /**
     * 口座振替の契約を解約する。
     *
     * @return success、本人以外の契約なら notFound
     */
    public String mandateCancel() {
        try {
            long idValue = id(mandateId);
            requireOwner(payments.mandateOwner(idValue), ErrorCode.MANDATE_NOT_FOUND, mandateId);
            return ok(payments.cancelMandate(idValue));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 収納企業からの請求データを取り込む（引落しは debitProcess で行う）。
     *
     * @return success、または業務エラーの結果名
     */
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

    /** @return success（引落し依頼の一覧） */
    public String debitRequests() { return ok(payments.listDebitRequests(customerScope())); }

    /**
     * 基準日までに期日の来た口座振替を引き落とす。
     *
     * @return success、未来日なら INVALID_BASE_DATE
     */
    public String debitProcess() {
        try {
            java.time.LocalDate d = date(asOf);
            java.time.LocalDate today = java.time.LocalDate.now();
            // 期日前の請求を引き落とさないよう、未来日での実行は拒否する
            if (d != null && d.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
            return ok(payments.processDirectDebits(d == null ? today : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // ---------- foreign ----------

    /**
     * 海外送金を受け付ける（AML の確認で保留になる場合がある）。
     *
     * @return success、または業務エラーの結果名
     */
    public String foreignSend() {
        try {
            PaymentService.ForeignOrder o = new PaymentService.ForeignOrder();
            o.accountNo = accountNo(accountNo);
            o.feeAccountNo = accountNo(feeAccountNo);
            requireAccountAccess(o.accountNo);
            // 送金元と手数料の引落し口座の両方が本人のものであることを確認する
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

    /**
     * 海外送金の一覧。
     *
     * @return success、または業務エラーの結果名
     */
    public String foreignList() {
        try {
            return ok(payments.listForeign(customerScope(), opt(status, "SENT", "HELD", "REJECTED")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 海外送金の詳細。
     *
     * @return success、本人以外の送金なら notFound
     */
    public String foreignGet() {
        try {
            Map<String, Object> r = payments.getForeign(id(remitId));
            requireOwner((String) r.get("customerId"), ErrorCode.REMITTANCE_NOT_FOUND, remitId);
            return ok(r);
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 保留中の海外送金を送金（RELEASE）または謝絶（REJECT）する（役席）。
     *
     * @return success、または業務エラーの結果名
     */
    public String foreignDecide() {
        try {
            boolean release = "RELEASE".equals(oneOf(decision, "RELEASE", "REJECT"));
            return ok(payments.decideForeign(id(remitId), release, required(note, 200), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // JSON の値は数値・文字列のどちらでも来るため、文字列にそろえてから検証する
    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    // 送金番号・全銀バッチ・契約・口座の順に、設定されている識別子で監査ログの対象を作る
    @Override
    public String getAuditTarget() {
        if (remitId != null) return "remit " + remitId + (decision == null ? "" : " " + decision);
        if (batchId != null) return "zengin " + batchId;
        if (mandateId != null) return "mandate " + mandateId;
        if (accountNo != null) return accountNo + (amount == null ? "" : " " + amount)
                + (bankCode == null ? "" : " -> " + bankCode) + (country == null ? "" : " -> " + country);
        return items == null ? null : items.size() + " items";
    }

    // 以下は JSON ボディ・クエリから受け取る入力（各 API で使う項目だけが設定される）
    /** @param v 口座番号 */
    @StrutsParameter public void setAccountNo(String v) { accountNo = v; }
    /** @param v 振込先の金融機関コード */
    @StrutsParameter public void setBankCode(String v) { bankCode = v; }
    /** @param v 振込先の支店コード */
    @StrutsParameter public void setBranchCode(String v) { branchCode = v; }
    /** @param v 預金種目（1: 普通 / 2: 当座 / 4: 貯蓄） */
    @StrutsParameter public void setDestType(String v) { destType = v; }
    /** @param v 振込先口座番号 */
    @StrutsParameter public void setDestAccountNo(String v) { destAccountNo = v; }
    /** @param v 受取人名（カナ） */
    @StrutsParameter public void setDestNameKana(String v) { destNameKana = v; }
    /** @param v 金額 */
    @StrutsParameter public void setAmount(String v) { amount = v; }
    /** @param v 送金番号 */
    @StrutsParameter public void setRemitId(String v) { remitId = v; }
    /** @param v 全銀データの番号 */
    @StrutsParameter public void setBatchId(String v) { batchId = v; }
    /** @param v 組戻しの理由 */
    @StrutsParameter public void setReason(String v) { reason = v; }
    /** @param v 判断（CREDIT / RETURN、RELEASE / REJECT） */
    @StrutsParameter public void setDecision(String v) { decision = v; }
    /** @param v メモ */
    @StrutsParameter public void setNote(String v) { note = v; }
    /** @param v 収納企業コード */
    @StrutsParameter public void setBillerCode(String v) { billerCode = v; }
    /** @param v 収納企業側のお客さま番号 */
    @StrutsParameter public void setPayerNumber(String v) { payerNumber = v; }
    /** @param v 口座振替契約の番号 */
    @StrutsParameter public void setMandateId(String v) { mandateId = v; }
    /** @param v 送金の方向（OUT / IN） */
    @StrutsParameter public void setDirection(String v) { direction = v; }
    /** @param v 状態での絞り込み */
    @StrutsParameter public void setStatus(String v) { status = v; }
    /** @param v 基準日 */
    @StrutsParameter public void setAsOf(String v) { asOf = v; }
    /** @param v 送金通貨（USD / EUR） */
    @StrutsParameter public void setCurrency(String v) { currency = v; }
    /** @param v 手数料の引落し口座番号 */
    @StrutsParameter public void setFeeAccountNo(String v) { feeAccountNo = v; }
    /** @param v 受取人名 */
    @StrutsParameter public void setBeneficiaryName(String v) { beneficiaryName = v; }
    /** @param v 受取人の口座（IBAN など） */
    @StrutsParameter public void setBeneficiaryAccount(String v) { beneficiaryAccount = v; }
    /** @param v 受取銀行の SWIFT/BIC コード */
    @StrutsParameter public void setSwiftBic(String v) { swiftBic = v; }
    /** @param v 受取国（ISO 3166 の 2 文字コード） */
    @StrutsParameter public void setCountry(String v) { country = v; }
    /** @param v 送金目的 */
    @StrutsParameter public void setPurpose(String v) { purpose = v; }
    /** @param v 冪等キー */
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }
    // depth = 2: リスト（1 段目）の中の Map（2 段目）までの値の設定を許可する。それより深い入れ子は受け付けない
    /** @param v 一括取込のデータ */
    @StrutsParameter(depth = 2) public void setItems(List<Map<String, Object>> v) { items = v; }

    // 冪等性インターセプターと監査ログが参照するキー。空のときは「キーなし」として null を返す
    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
