package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.NewCustomer;
import com.example.bank.service.CustomerService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * POST /api/customer-register — 口座開設の申込（顧客登録・本人確認書類の受付）
 * Body: {"name","nameKana","birthDate":"1990-04-01","postalCode","address","phone","idDocType","idDocNumber","idempotencyKey"}
 */
public class CustomerRegisterAction extends KeyedWriteAction {
    private final CustomerService customers;
    private String name;
    private String nameKana;
    private String birthDate;
    private String postalCode;
    private String address;
    private String phone;
    private String idDocType;
    private String idDocNumber;
    // 検証時に変換した生年月日（execute で再変換しないよう保持する）
    private LocalDate birth;
    private String customerType;

    /** Struts が使う既定コンストラクタ。 */
    public CustomerRegisterAction() { this(CustomerService.getDefault()); }

    /** @param customers 顧客サービス（テスト用） */
    public CustomerRegisterAction(CustomerService customers) { this.customers = customers; }

    /** 申込項目を正規化（前後空白・ハイフン除去）してから形式を検証し、最後に冪等キーを検証する。 */
    @Override
    public void validate() {
        name = InputRules.trim(name);
        nameKana = InputRules.trim(nameKana);
        postalCode = InputRules.trim(postalCode);
        address = InputRules.trim(address);
        phone = InputRules.digitsOnly(phone);
        idDocType = InputRules.trim(idDocType);
        idDocNumber = InputRules.trim(idDocNumber);
        birth = InputRules.toDate(InputRules.trim(birthDate));

        if (!InputRules.isText(name, InputRules.NAME_MAX)) addFieldError("name", getText("validation.name"));
        if (!InputRules.isKana(nameKana)) addFieldError("nameKana", getText("validation.nameKana"));
        if (!InputRules.isReasonableDate(birth)) addFieldError("birthDate", getText("validation.birthDate"));
        if (!InputRules.isPostalCode(postalCode)) addFieldError("postalCode", getText("validation.postalCode"));
        if (!InputRules.isText(address, InputRules.ADDRESS_MAX)) addFieldError("address", getText("validation.address"));
        if (!InputRules.isPhone(phone)) addFieldError("phone", getText("validation.phone"));
        if (!InputRules.isIdDocType(idDocType)) addFieldError("idDocType", getText("validation.idDocType"));
        if (!InputRules.isIdDocNumber(idDocNumber)) addFieldError("idDocNumber", getText("validation.idDocNumber"));
        super.validate();
    }

    /**
     * 顧客を登録する（本人確認は PENDING。行員の確認後に口座開設できる）。
     *
     * @return success、または業務エラーの結果名
     */
    @Override
    public String execute() {
        try {
            NewCustomer input = new NewCustomer(name, nameKana, birth, InputRules.normalizePostalCode(postalCode),
                    address, phone, idDocType, idDocNumber,
                    // 想定外の値はすべて個人として扱い、不正な区分値を DB に入れない
                    "CORPORATE".equals(customerType) ? "CORPORATE" : "INDIVIDUAL");
            // 受付した行員を記録する（本人確認は別の行員が判定する職務分離のため）
            return ok(customers.register(input, getIdempotencyKey(), currentUser().getUserId()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @param name 氏名（法人は商号） */
    @StrutsParameter public void setName(String name) { this.name = name; }
    /** INDIVIDUAL / CORPORATE（法人は商号・設立日・代表者の本人確認書類） */
    @StrutsParameter public void setCustomerType(String customerType) { this.customerType = customerType; }
    /** @param nameKana フリガナ */
    @StrutsParameter public void setNameKana(String nameKana) { this.nameKana = nameKana; }
    /** @param birthDate 生年月日（yyyy-MM-dd。法人は設立日） */
    @StrutsParameter public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    /** @param postalCode 郵便番号 */
    @StrutsParameter public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    /** @param address 住所 */
    @StrutsParameter public void setAddress(String address) { this.address = address; }
    /** @param phone 電話番号 */
    @StrutsParameter public void setPhone(String phone) { this.phone = phone; }
    /** @param idDocType 本人確認書類の種類 */
    @StrutsParameter public void setIdDocType(String idDocType) { this.idDocType = idDocType; }
    /** 本人確認書類の番号は保存せず、下 4 桁のみ記録する（ゲッターも公開しない）。 */
    @StrutsParameter public void setIdDocNumber(String idDocNumber) { this.idDocNumber = idDocNumber; }
}
