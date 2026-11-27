package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 取引種別：入金（残高が増える）側の種別。 */
class TxTypeTest {
    @Test
    void creditTypes() {
        Set<TxType> credits = EnumSet.of(TxType.DEPOSIT, TxType.TRANSFER_IN, TxType.INTEREST, TxType.FX_IN,
                TxType.LOAN_DISB, TxType.REMIT_IN);
        for (TxType t : TxType.values()) {
            assertEquals(credits.contains(t), t.isCredit(), t.name());
        }
    }
}
