package com.example.bank.service;

import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 冪等キーの結果の保存と再送時の再現（idempotent replay）。
 *
 * <p>更新系 API は業務トランザクションの先頭で冪等キーを登録する（同じキーは主キー制約で 1 回だけ成功）。
 * 成功した応答はキーに保存し、同じキーで再送された場合はエラーではなく「前回と同じ結果」を返す。
 * 通信断・タイムアウトで結果が分からなくなったクライアントは、同じキーで安全に再送できる。
 * <ul>
 *   <li>同じキーで別の内容（操作・対象・金額が違う）を送った場合は再現せず拒否する</li>
 *   <li>先の要求がまだ処理中（結果未保存）の場合は「処理中」を返す</li>
 * </ul>
 */
public class IdempotencyService {
    private static final Logger LOG = LogManager.getLogger(IdempotencyService.class);
    // Struts の Action は DI なしで生成されるため、既定のインスタンスを static に持つ（テストでは別の DataSource・時計で生成する）
    private static final IdempotencyService DEFAULT = new IdempotencyService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    /**
     * @param dataSource 接続の取得元（JNDI の解決を初回利用時まで遅らせるため Supplier で受け取る）
     * @param clock      完了日時の記録に使う時計（テストで固定できるように）
     */
    public IdempotencyService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static IdempotencyService getDefault() { return DEFAULT; }

    /** 結果を保存する（成功時のみ。業務トランザクションのコミット後に別トランザクションで）。 */
    public void saveResult(String key, String fingerprint, String json) {
        try {
            // completed_at IS NULL の条件で、最初に保存した結果を後から上書きしない
            Transactions.inTransaction(dataSource.get(), c -> execute(c, "UPDATE operation_request SET fingerprint = ?, "
                    + "result_json = ?, completed_at = ? WHERE idempotency_key = ? AND completed_at IS NULL",
                    clip(fingerprint), json, LocalDateTime.now(clock), key));
        } catch (RuntimeException e) {
            // 業務処理はコミット済みなので、ここで例外にすると成功した操作が失敗に見えてしまう。ログだけ残して応答は成功のまま返す
            // The operation itself is committed; without a stored result a retry gets "processing" instead of a replay
            LOG.error("failed to store idempotent result for key {}", key, e);
        }
    }

    /** 保存済みの結果: [fingerprint, json]。キーなし・処理中は null。 */
    public String[] storedResult(String key) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT fingerprint, result_json, completed_at FROM operation_request "
                    + "WHERE idempotency_key = ?", key);
            // 行がない、または結果未保存（completed_at が null = 先の要求がまだ処理中）なら null
            if (rows.isEmpty() || rows.get(0).get("completedAt") == null) return null;
            return new String[]{(String) rows.get(0).get("fingerprint"), (String) rows.get(0).get("resultJson")};
        });
    }

    // 指紋は列長（200 文字）に収まるよう切り詰める
    private static String clip(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }
}
