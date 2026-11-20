package com.example.bank.domain;

import java.time.LocalDate;

/** 口座開設申込（顧客登録）の入力。形式チェック済みの値を受け取る。 */
public final class NewCustomer {
    public final String name;
    public final String nameKana;
    public final LocalDate birthDate;
    public final String postalCode;
    public final String address;
    public final String phone;
    public final String idDocType;
    public final String idDocNumber;

    public NewCustomer(String name, String nameKana, LocalDate birthDate, String postalCode, String address,
                       String phone, String idDocType, String idDocNumber) {
        this.name = name;
        this.nameKana = nameKana;
        this.birthDate = birthDate;
        this.postalCode = postalCode;
        this.address = address;
        this.phone = phone;
        this.idDocType = idDocType;
        this.idDocNumber = idDocNumber;
    }
}
