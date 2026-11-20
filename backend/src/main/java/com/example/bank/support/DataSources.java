package com.example.bank.support;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * WebSphere Liberty が管理する接続プール（server.xml の jdbc/bankDS）を JNDI で取得する。
 * Struts 2 の Action は DI コンテナなしで生成されるため、ここで一度だけ解決してキャッシュする。
 */
public final class DataSources {
    public static final String JNDI_NAME = "jdbc/bankDS";

    private static volatile DataSource cached;

    private DataSources() {}

    public static DataSource bank() {
        DataSource ds = cached;
        if (ds == null) {
            synchronized (DataSources.class) {
                ds = cached;
                if (ds == null) {
                    try {
                        ds = (DataSource) new InitialContext().lookup(JNDI_NAME);
                    } catch (NamingException e) {
                        throw new IllegalStateException("DataSource not found: " + JNDI_NAME, e);
                    }
                    cached = ds;
                }
            }
        }
        return ds;
    }
}
