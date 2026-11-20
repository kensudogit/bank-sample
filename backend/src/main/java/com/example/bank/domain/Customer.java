package com.example.bank.domain;

import java.util.Collections;
import java.util.List;

/** 顧客と本人確認（KYC）状態。本人確認書類の番号は下 4 桁のみ保持する。 */
public final class Customer {
    private final String customerId;
    private final String name;
    private final String nameKana;
    private final String birthDate;
    private final String postalCode;
    private final String address;
    private final String phone;
    private final String idDocType;
    private final String idDocLast4;
    private final String kycStatus;
    private final String kycDecidedAt;
    private final String kycNote;
    private final String createdAt;
    private final String registeredBy;
    private final String kycDecidedBy;
    private final List<Account> accounts;
    private String customerType = "INDIVIDUAL";

    public Customer(String customerId, String name, String nameKana, String birthDate, String postalCode,
                    String address, String phone, String idDocType, String idDocLast4, String kycStatus,
                    String kycDecidedAt, String kycNote, String createdAt, String registeredBy, String kycDecidedBy,
                    List<Account> accounts) {
        this.customerId = customerId;
        this.name = name;
        this.nameKana = nameKana;
        this.birthDate = birthDate;
        this.postalCode = postalCode;
        this.address = address;
        this.phone = phone;
        this.idDocType = idDocType;
        this.idDocLast4 = idDocLast4;
        this.kycStatus = kycStatus;
        this.kycDecidedAt = kycDecidedAt;
        this.kycNote = kycNote;
        this.createdAt = createdAt;
        this.registeredBy = registeredBy;
        this.kycDecidedBy = kycDecidedBy;
        this.accounts = accounts == null ? Collections.<Account>emptyList() : accounts;
    }

    public Customer withAccounts(List<Account> list) {
        return new Customer(customerId, name, nameKana, birthDate, postalCode, address, phone, idDocType, idDocLast4,
                kycStatus, kycDecidedAt, kycNote, createdAt, registeredBy, kycDecidedBy, list).withCustomerType(customerType);
    }

    public String getCustomerId() { return customerId; }
    public String getName() { return name; }
    public String getNameKana() { return nameKana; }
    public String getBirthDate() { return birthDate; }
    public String getPostalCode() { return postalCode; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getIdDocType() { return idDocType; }
    public String getIdDocLast4() { return idDocLast4; }
    public String getKycStatus() { return kycStatus; }
    public String getKycDecidedAt() { return kycDecidedAt; }
    public String getKycNote() { return kycNote; }
    public String getCreatedAt() { return createdAt; }
    /** 申込を受け付けた行員（移行データは null） */
    public String getRegisteredBy() { return registeredBy; }
    /** 本人確認を判定した行員 */
    public String getKycDecidedBy() { return kycDecidedBy; }
    public List<Account> getAccounts() { return accounts; }

    public boolean isVerified() { return "VERIFIED".equals(kycStatus); }

    /** INDIVIDUAL（個人）/ CORPORATE（法人） */
    public String getCustomerType() { return customerType; }
    public boolean isCorporate() { return "CORPORATE".equals(customerType); }
    public Customer withCustomerType(String type) {
        this.customerType = type == null ? "INDIVIDUAL" : type;
        return this;
    }
}
