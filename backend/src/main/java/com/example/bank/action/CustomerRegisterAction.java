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
    private LocalDate birth;
    private String customerType;

    public CustomerRegisterAction() { this(CustomerService.getDefault()); }

    public CustomerRegisterAction(CustomerService customers) { this.customers = customers; }

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

    @Override
    public String execute() {
        try {
            NewCustomer input = new NewCustomer(name, nameKana, birth, InputRules.normalizePostalCode(postalCode),
                    address, phone, idDocType, idDocNumber,
                    "CORPORATE".equals(customerType) ? "CORPORATE" : "INDIVIDUAL");
            return ok(customers.register(input, getIdempotencyKey(), currentUser().getUserId()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setName(String name) { this.name = name; }
    /** INDIVIDUAL / CORPORATE（法人は商号・設立日・代表者の本人確認書類） */
    @StrutsParameter public void setCustomerType(String customerType) { this.customerType = customerType; }
    @StrutsParameter public void setNameKana(String nameKana) { this.nameKana = nameKana; }
    @StrutsParameter public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    @StrutsParameter public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    @StrutsParameter public void setAddress(String address) { this.address = address; }
    @StrutsParameter public void setPhone(String phone) { this.phone = phone; }
    @StrutsParameter public void setIdDocType(String idDocType) { this.idDocType = idDocType; }
    /** 本人確認書類の番号は保存せず、下 4 桁のみ記録する（ゲッターも公開しない）。 */
    @StrutsParameter public void setIdDocNumber(String idDocNumber) { this.idDocNumber = idDocNumber; }
}
