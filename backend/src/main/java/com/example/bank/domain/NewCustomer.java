package com.example.bank.domain;

import java.time.LocalDate;

/** 口座開設申込（顧客登録）の入力。形式チェック済みの値を受け取る。 */
public final class NewCustomer {
    // 入力値を運ぶだけの不変オブジェクトのため、getter を設けず public final フィールドで公開する
    public final String name;
    public final String nameKana;
    public final LocalDate birthDate;
    public final String postalCode;
    public final String address;
    public final String phone;
    public final String idDocType;
    public final String idDocNumber;
    /** INDIVIDUAL / CORPORATE（法人は name に商号、birthDate に設立日） */
    public final String customerType;

    /**
     * 個人顧客として生成する。
     *
     * @param name        氏名
     * @param nameKana    フリガナ
     * @param birthDate   生年月日
     * @param postalCode  郵便番号
     * @param address     住所
     * @param phone       電話番号
     * @param idDocType   本人確認書類の種類
     * @param idDocNumber 本人確認書類の番号（保存時は下 4 桁のみ残す）
     */
    public NewCustomer(String name, String nameKana, LocalDate birthDate, String postalCode, String address,
                       String phone, String idDocType, String idDocNumber) {
        this(name, nameKana, birthDate, postalCode, address, phone, idDocType, idDocNumber, "INDIVIDUAL");
    }

    /**
     * 顧客区分を指定して生成する。
     *
     * @param customerType INDIVIDUAL / CORPORATE
     * 他の引数は 8 引数版のコンストラクタと同じ。
     */
    public NewCustomer(String name, String nameKana, LocalDate birthDate, String postalCode, String address,
                       String phone, String idDocType, String idDocNumber, String customerType) {
        this.name = name;
        this.nameKana = nameKana;
        this.birthDate = birthDate;
        this.postalCode = postalCode;
        this.address = address;
        this.phone = phone;
        this.idDocType = idDocType;
        this.idDocNumber = idDocNumber;
        this.customerType = customerType;
    }
}
