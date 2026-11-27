package com.example.bank.testsupport;

import com.example.bank.support.DataSources;

import javax.sql.DataSource;
import java.lang.reflect.Field;

/**
 * 既定のサービス（XxxService.getDefault()）が使う DB を、テスト用の DB に差し替える。
 * 本番では DataSources が JNDI（Liberty の jdbc/bankDS）で取得してキャッシュする。テストでは JNDI がないため、
 * そのキャッシュに直接テスト用の DB を入れる。Action が getDefault() のサービスで本人の口座かを確かめる場合などに使う。
 * テストの後は必ず {@link #clear()} で元に戻す（テスト同士で DB を共有しないように）。
 */
public final class DefaultDataSource {
    private DefaultDataSource() {}

    /** @param dataSource 既定のサービスに使わせる DB */
    public static void use(DataSource dataSource) { set(dataSource); }

    /** 差し替えを戻す（次の取得は JNDI に問い合わせる）。 */
    public static void clear() { set(null); }

    private static void set(DataSource dataSource) {
        try {
            Field f = DataSources.class.getDeclaredField("cached");
            f.setAccessible(true);
            f.set(null, dataSource);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot replace the default DataSource", e);
        }
    }
}
