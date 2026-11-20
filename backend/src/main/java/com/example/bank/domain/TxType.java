package com.example.bank.domain;

public enum TxType {
    DEPOSIT, WITHDRAWAL, TRANSFER_OUT, TRANSFER_IN,
    /** 利息（税引前） */
    INTEREST,
    /** 源泉分離課税（国税 15.315% + 地方税 5%） */
    TAX,
    /** 解約時の払戻し */
    CLOSE_PAYOUT;

    /** 残高が増える取引か */
    public boolean isCredit() {
        return this == DEPOSIT || this == TRANSFER_IN || this == INTEREST;
    }
}
