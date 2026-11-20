package com.example.bank.domain;

/**
 * 明細の取引種別。金額は常に正で記録し、入金か出金かは種別で決める（{@link #isCredit()}）。
 */
public enum TxType {
    // 入金・出金・振込（振込は出金側 TRANSFER_OUT と入金側 TRANSFER_IN の 2 明細で記帳する）
    DEPOSIT, WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN,
    /** 利息（税引前） */
    INTEREST,
    /** 源泉分離課税（国税 15.315% + 地方税 5%） */
    TAX,
    /** 解約時の払戻し */
    CLOSE_PAYOUT,
    /** 外貨両替（払出側・入金側） */
    FX_OUT, FX_IN,
    /** 融資の実行（貸付金の入金） */
    LOAN_DISB,
    /** 融資の返済（口座からの引落し） */
    LOAN_REPAY,
    /** 手数料 */
    FEE,
    /** 口座振替（公共料金などの自動引落し） */
    DIRECT_DEBIT,
    /** 他行宛振込・海外送金（仕向） */
    REMIT_OUT,
    /** 他行からの振込（被仕向） */
    REMIT_IN,
    /** 当座貸越利息 */
    OD_INTEREST;

    /** 残高が増える取引か */
    public boolean isCredit() {
        return this == DEPOSIT || this == TRANSFER_IN || this == INTEREST || this == FX_IN || this == LOAN_DISB
                || this == REMIT_IN;
    }
}
