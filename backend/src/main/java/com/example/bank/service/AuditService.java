package com.example.bank.service;

import com.example.bank.domain.AuditEntry;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.repository.AuditRepository;
import com.example.bank.support.DataSources;
import com.example.bank.support.PasswordHasher;
import com.example.bank.support.Transactions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 監査ログ（誰が・いつ・何に対して・結果）。業務トランザクションとは別トランザクションで記録するため、
 * 業務エラーで取り消された操作や権限エラーも残る。各行はハッシュチェーンでつながり、改ざんを検出できる。
 */
public class AuditService {
    /** 結果: 成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 結果: 失敗（業務エラー・入力エラーなど） */
    public static final String FAILURE = "FAILURE";
    /** 結果: 権限がなく拒否 */
    public static final String DENIED = "DENIED";
    private static final Logger LOG = LogManager.getLogger(AuditService.class);
    // 検証時に一度に読む行数（全件を一度にメモリへ載せない）
    private static final int VERIFY_CHUNK = 500;

    private static final AuditService DEFAULT = new AuditService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AuditRepository audit = new AuditRepository();

    /**
     * @param dataSource 接続の取得元
     * @param clock      記録日時の取得元
     */
    public AuditService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static AuditService getDefault() { return DEFAULT; }

    /**
     * 記録する。記録に失敗しても呼び出し元の処理（すでに確定済み）は止めず、アプリケーションログに残す。
     *
     * @param user 未ログインの場合は null（userIdHint に試行された利用者 ID などを渡せる）
     */
    public void record(LoginUser user, String userIdHint, String action, String target, String outcome, String detail,
                       String clientIp) {
        record(user, userIdHint, action, target, outcome, detail, clientIp, null);
    }

    /** @param requestKey 操作の冪等キー（取引明細との対応付けに使う） */
    public void record(LoginUser user, String userIdHint, String action, String target, String outcome, String detail,
                       String clientIp, String requestKey) {
        String userId = user != null ? user.getUserId() : userIdHint;
        String role = user != null ? user.getRole() : null;
        try {
            // 業務とは別の短いトランザクション。チェーン先頭のロックを持つ時間を短くし、業務のロールバックに巻き込まれないようにする。
            // 各項目は列長に合わせて切り詰める（長すぎる値で記録自体が失敗しないように）
            Transactions.inTransaction(dataSource.get(), c -> audit.append(c, LocalDateTime.now(clock),
                    clip(userId, 30), role, clip(action, 40), clip(target, 100), outcome, clip(detail, 400),
                    clip(clientIp, 45), clip(requestKey, 64)));
        // 業務はすでに確定しているので、監査ログの失敗で利用者への応答をエラーにしない（運用者がログで検知する）
        } catch (RuntimeException e) {
            LOG.error("audit log write failed: action={} user={} outcome={}", action, userId, outcome, e);
        }
    }

    /** 検索（新しい順）。 */
    public Map<String, Object> search(LocalDate from, LocalDate to, String userId, String action, String outcome,
                                      int page, int size) {
        // 終了日は「その日を含む」ため、翌日 0 時未満に変換して検索する
        if (page < 1 || size < 1 || size > 200) throw new BankException(ErrorCode.VALIDATION_ERROR);
        AuditRepository.Filter f = new AuditRepository.Filter(
                from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(),
                userId, action, outcome);
        return Transactions.readOnly(dataSource.get(), c -> {
            long total = audit.count(c, f);
            List<AuditEntry> items = audit.search(c, f, (page - 1) * size, size);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items);
            m.put("page", page);
            m.put("size", size);
            m.put("total", total);
            m.put("hasNext", (long) page * size < total);
            return m;
        });
    }

    /**
     * ハッシュチェーンを先頭から検証する。行の改ざん（内容変更）・削除・差し込みと、末尾の削除（チェーン先頭との不一致）を検出する。
     */
    public Map<String, Object> verify() {
        return Transactions.readOnly(dataSource.get(), c -> {
            String expectedPrev = "0000000000000000000000000000000000000000000000000000000000000000";
            long expectedId = 1;
            long checked = 0;
            Long brokenAt = null;
            String reason = null;
            outer:
            while (true) {
                // ID 順にチャンクで読み、ID の連続（欠落なし）・前行とのつながり・内容のハッシュを 1 行ずつ確かめる
                List<AuditEntry> chunk = audit.chunkAfter(c, expectedId - 1, VERIFY_CHUNK);
                if (chunk.isEmpty()) break;
                for (AuditEntry e : chunk) {
                    if (e.getAuditId() != expectedId) {
                        brokenAt = expectedId;
                        reason = "行が欠落しています";
                        break outer;
                    }
                    if (!e.getPrevHash().equals(expectedPrev)) {
                        brokenAt = e.getAuditId();
                        reason = "直前の行とつながっていません";
                        break outer;
                    }
                    String hash = PasswordHasher.sha256Hex(AuditEntry.canonical(e.getAuditId(), e.getOccurredAt(),
                            e.getUserId(), e.getUserRole(), e.getAction(), e.getTarget(), e.getOutcome(),
                            e.getDetail(), e.getClientIp(), e.getPrevHash(), e.getRequestKey()));
                    if (!hash.equals(e.getRowHash())) {
                        brokenAt = e.getAuditId();
                        reason = "内容が改ざんされています";
                        break outer;
                    }
                    expectedPrev = hash;
                    expectedId++;
                    checked++;
                }
            }
            // 最後の行ごと削除されるとチェーン内では検出できないため、別に保持している先頭（最終 ID・最終ハッシュ）と照合する
            String[] head = audit.head(c);
            if (brokenAt == null && (Long.parseLong(head[0]) != expectedId - 1 || !head[1].equals(expectedPrev))) {
                brokenAt = expectedId;
                reason = "末尾の行が削除されています";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", brokenAt == null);
            m.put("checked", checked);
            m.put("brokenAt", brokenAt);
            m.put("reason", reason);
            return m;
        });
    }

    // 列長を超える値を切り詰める
    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
