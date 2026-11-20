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
    // 後から追加した属性のため、コンストラクタを増やさず with メソッドで設定する（既定は個人）
    private String customerType = "INDIVIDUAL";

    /**
     * @param customerId   顧客番号
     * @param name         氏名（法人は商号）
     * @param nameKana     フリガナ
     * @param birthDate    生年月日（法人は設立日）
     * @param postalCode   郵便番号
     * @param address      住所
     * @param phone        電話番号
     * @param idDocType    本人確認書類の種類
     * @param idDocLast4   本人確認書類番号の下 4 桁
     * @param kycStatus    本人確認状態（PENDING / VERIFIED / REJECTED）
     * @param kycDecidedAt 本人確認の判定日時
     * @param kycNote      判定メモ
     * @param createdAt    登録日時
     * @param registeredBy 受付した行員
     * @param kycDecidedBy 判定した行員
     * @param accounts     保有口座（null は空リスト）
     */
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

    /**
     * 保有口座を差し替えた新しいインスタンスを返す。
     *
     * @param list 保有口座
     * @return 口座を設定した顧客（顧客区分も引き継ぐ）
     */
    public Customer withAccounts(List<Account> list) {
        return new Customer(customerId, name, nameKana, birthDate, postalCode, address, phone, idDocType, idDocLast4,
                kycStatus, kycDecidedAt, kycNote, createdAt, registeredBy, kycDecidedBy, list).withCustomerType(customerType);
    }

    /** @return 顧客番号 */
    public String getCustomerId() { return customerId; }
    /** @return 氏名（法人は商号） */
    public String getName() { return name; }
    /** @return フリガナ */
    public String getNameKana() { return nameKana; }
    /** @return 生年月日（法人は設立日） */
    public String getBirthDate() { return birthDate; }
    /** @return 郵便番号 */
    public String getPostalCode() { return postalCode; }
    /** @return 住所 */
    public String getAddress() { return address; }
    /** @return 電話番号 */
    public String getPhone() { return phone; }
    /** @return 本人確認書類の種類 */
    public String getIdDocType() { return idDocType; }
    /** @return 本人確認書類番号の下 4 桁（全桁は保持しない） */
    public String getIdDocLast4() { return idDocLast4; }
    /** @return 本人確認状態 */
    public String getKycStatus() { return kycStatus; }
    /** @return 本人確認の判定日時 */
    public String getKycDecidedAt() { return kycDecidedAt; }
    /** @return 判定メモ */
    public String getKycNote() { return kycNote; }
    /** @return 登録日時 */
    public String getCreatedAt() { return createdAt; }
    /** 申込を受け付けた行員（移行データは null） */
    public String getRegisteredBy() { return registeredBy; }
    /** 本人確認を判定した行員 */
    public String getKycDecidedBy() { return kycDecidedBy; }
    /** @return 保有口座 */
    public List<Account> getAccounts() { return accounts; }

    /** @return 本人確認済み（VERIFIED）なら true。口座開設などの前提条件 */
    public boolean isVerified() { return "VERIFIED".equals(kycStatus); }

    /** INDIVIDUAL（個人）/ CORPORATE（法人） */
    public String getCustomerType() { return customerType; }
    /** @return 法人なら true */
    public boolean isCorporate() { return "CORPORATE".equals(customerType); }
    /**
     * 顧客区分を設定する。
     *
     * @param type INDIVIDUAL / CORPORATE（null は INDIVIDUAL）
     * @return このインスタンス
     */
    public Customer withCustomerType(String type) {
        this.customerType = type == null ? "INDIVIDUAL" : type;
        return this;
    }
}
