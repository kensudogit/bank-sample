package com.example.bank.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 接続プールの取得：JNDI がない環境（Liberty の外）では、設定漏れとして分かる例外にする。 */
class DataSourcesTest {
    @Test
    void jndiName() {
        assertEquals("jdbc/bankDS", DataSources.JNDI_NAME);
    }

    @Test
    void failsClearlyWithoutJndi() {
        IllegalStateException e = assertThrows(IllegalStateException.class, DataSources::bank);
        assertTrue(e.getMessage().contains(DataSources.JNDI_NAME));
        assertNotNull(e.getCause());
    }
}
