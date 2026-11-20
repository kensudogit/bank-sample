package com.example.bank.support;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * WebSphere Liberty が管理する接続プール（server.xml の jdbc/bankDS）を JNDI で取得する。
 * Struts 2 の Action は DI コンテナなしで生成されるため、ここで一度だけ解決してキャッシュする。
 */
public final class DataSources {
    /** server.xml で定義した接続プールの JNDI 名 */
    public static final String JNDI_NAME = "jdbc/bankDS";

    // ダブルチェックロッキングで使うため volatile にして、初期化途中の参照が他スレッドから見えないようにする
    private static volatile DataSource cached;

    private DataSources() {}

    /**
     * 銀行 DB の DataSource を返す（初回のみ JNDI で検索し、以後はキャッシュを返す）。
     *
     * @return 接続プールの DataSource
     * @throws IllegalStateException JNDI に登録されていない場合（server.xml の設定漏れ）
     */
    public static DataSource bank() {
        DataSource ds = cached;
        if (ds == null) {
            // JNDI 検索は比較的重いので、初回だけロックして 1 回に限定する（2 回目以降はロックなしで返す）
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
