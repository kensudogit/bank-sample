package com.example.bank.action;

import com.example.bank.service.BankService;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ヘルスチェック（ログイン不要）。
 * <ul>
 *   <li>GET /api/health — readiness: DB に接続できれば 200、できなければ 503（ロードバランサー・コンテナの判定用）</li>
 *   <li>GET /api/live — liveness: プロセスが応答していれば 200（DB 障害でプロセスを再起動させないため DB は見ない）</li>
 * </ul>
 */
public class HealthAction extends BaseApiAction {
    /** どのインスタンスが応答したか（ロードバランサー越しの確認用） */
    private static final String INSTANCE = hostName();

    /** Struts が使う既定コンストラクタ。 */
    public HealthAction() { this(BankService.getDefault()); }

    /** @param service 業務サービス（テスト用） */
    public HealthAction(BankService service) { super(service); }

    /**
     * readiness チェック。
     *
     * @return DB に接続できれば success（200）、できなければ unavailable（503）
     */
    @Override
    public String execute() {
        boolean db = service.ping();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", db ? "UP" : "DOWN");
        data.put("database", db ? "UP" : "DOWN");
        data.put("instance", INSTANCE);
        // 503 のときも状態を JSON で返すため、レスポンス本体は常に設定する
        ok(data);
        return db ? SUCCESS : "unavailable";
    }

    /**
     * liveness チェック。
     *
     * @return success（常に 200）
     */
    public String live() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("instance", INSTANCE);
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return ok(data);
    }

    // コンテナではホスト名がコンテナ ID になるため、どのインスタンスが応答したかを区別できる
    private static String hostName() {
        String h = System.getenv("HOSTNAME");
        return h == null || h.isEmpty() ? "local" : h;
    }
}
