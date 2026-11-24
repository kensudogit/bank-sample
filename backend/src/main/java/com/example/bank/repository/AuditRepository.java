package com.example.bank.repository;

import com.example.bank.domain.AuditEntry;
import com.example.bank.support.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * AUDIT_LOG / AUDIT_CHAIN への JDBC アクセス。
 * 各行は「前の行のハッシュ + 自身の内容」の SHA-256 を持ち、途中の行の改ざん・削除を検出できる。
 */
public class AuditRepository {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final String COLS =
            "audit_id, occurred_at, user_id, user_role, action, target, outcome, detail, client_ip, prev_hash, row_hash, request_key";

    /** 検索条件（null は条件なし）。 */
    public static final class Filter {
        final LocalDateTime from;
        final LocalDateTime toExclusive;
        final String userId;
        final String action;
        final String outcome;

        public Filter(LocalDateTime from, LocalDateTime toExclusive, String userId, String action, String outcome) {
            this.from = from;
            this.toExclusive = toExclusive;
            this.userId = userId;
            this.action = action;
            this.outcome = outcome;
        }
    }

    /**
     * 1 行追記する。チェーンの先頭行をロックして ID と直前ハッシュを確定させるため、呼び出し側は
     * 短いトランザクションで実行すること（業務トランザクションとは分ける）。
     */
    public AuditEntry append(Connection c, LocalDateTime at, String userId, String role, String action, String target,
                             String outcome, String detail, String clientIp) throws SQLException {
        return append(c, at, userId, role, action, target, outcome, detail, clientIp, null);
    }

    /**
     * 冪等キー付きで 1 行追記する。
     *
     * @param requestKey 操作の冪等キー（明細との対応付けに使う。なければ null）
     * @return 追記した行
     * 他の引数は 9 引数版の append と同じ。
     */
    public AuditEntry append(Connection c, LocalDateTime at, String userId, String role, String action, String target,
                             String outcome, String detail, String clientIp, String requestKey) throws SQLException {
        long lastId;
        String lastHash;
        try (PreparedStatement ps = c.prepareStatement(
                // チェーンの先頭（最終 ID と最終ハッシュ）を 1 行で管理し、FOR UPDATE で直列化する。
                // 同時に 2 件追記されても、同じ prevHash を持つ行が 2 つできて鎖が分岐することはない
                "SELECT last_id, last_hash FROM audit_chain WHERE chain_id = 1 FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            lastId = rs.getLong(1);
            lastHash = rs.getString(2);
        }
        // ID もシーケンスではなく先頭行から決める（欠番があれば削除を疑えるよう、連番を保証する）
        long id = lastId + 1;
        // Millisecond precision: what is hashed must survive the round trip through the DB unchanged
        LocalDateTime ts = at.truncatedTo(ChronoUnit.MILLIS);
        String occurredAt = TS.format(ts);
        String hash = PasswordHasher.sha256Hex(
                AuditEntry.canonical(id, occurredAt, userId, role, action, target, outcome, detail, clientIp, lastHash,
                        requestKey));

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log (" + COLS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setTimestamp(2, Timestamp.valueOf(ts));
            ps.setString(3, userId);
            ps.setString(4, role);
            ps.setString(5, action);
            ps.setString(6, target);
            ps.setString(7, outcome);
            ps.setString(8, detail);
            ps.setString(9, clientIp);
            ps.setString(10, lastHash);
            ps.setString(11, hash);
            ps.setString(12, requestKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                // 追記した行を新しい先頭として記録する（同じトランザクション内なので、行の追加と先頭の更新は必ず一緒に確定する）
                "UPDATE audit_chain SET last_id = ?, last_hash = ? WHERE chain_id = 1")) {
            ps.setLong(1, id);
            ps.setString(2, hash);
            ps.executeUpdate();
        }
        return new AuditEntry(id, occurredAt, userId, role, action, target, outcome, detail, clientIp, lastHash, hash)
                .withRequestKey(requestKey);
    }

    /**
     * 条件に合う監査ログの件数を返す。
     *
     * @param c DB 接続
     * @param f 検索条件
     * @return 件数
     * @throws SQLException DB アクセスに失敗した場合
     */
    public long count(Connection c, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log" + where(f, params))) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 新しい順に offset から limit 件。 */
    public List<AuditEntry> search(Connection c, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM audit_log" + where(f, params)
                   + " ORDER BY audit_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        params.add(offset);
        params.add(limit);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(limit);
            bind(ps, params);
            return read(ps);
        }
    }

    /** ID 順に afterId より後を最大 limit 件（チェーン検証用）。 */
    public List<AuditEntry> chunkAfter(Connection c, long afterId, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM audit_log WHERE audit_id > ? "
                + "ORDER BY audit_id FETCH FIRST ? ROWS ONLY")) {
            ps.setFetchSize(limit);
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            return read(ps);
        }
    }

    /** チェーンの起点（保存期間を過ぎて削除した範囲の最後の行の ID とハッシュ。削除前は 0 と 0 埋め）。 */
    public String[] base(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT base_id, base_hash FROM audit_chain WHERE chain_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            return new String[]{String.valueOf(rs.getLong(1)), rs.getString(2)};
        }
    }

    /**
     * 保存期間を過ぎた行を古い順に削除し、削除した最後の行を新しい起点にする（チェーンの先頭をロックして直列化）。
     * 期限内の行より後ろは削除しない（途中に穴を開けない）。
     *
     * @param cutoff この日時より前に記録された行が削除の対象
     * @param max    1 回に削除する最大行数
     * @return 削除した行数
     */
    public int purgeBefore(Connection c, LocalDateTime cutoff, int max) throws SQLException {
        long baseId;
        long lastId;
        try (PreparedStatement ps = c.prepareStatement("SELECT base_id, last_id FROM audit_chain WHERE chain_id = 1 FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            baseId = rs.getLong(1);
            lastId = rs.getLong(2);
        }
        // 期限内の最初の行の直前までが削除できる範囲（行は記録順に ID が増える）
        long firstKept = lastId + 1;
        try (PreparedStatement ps = c.prepareStatement("SELECT MIN(audit_id) FROM audit_log WHERE occurred_at >= ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) firstKept = rs.getLong(1);
            }
        }
        long upTo = Math.min(firstKept - 1, baseId + max);
        if (upTo <= baseId) return 0;
        String hash;
        try (PreparedStatement ps = c.prepareStatement("SELECT row_hash FROM audit_log WHERE audit_id = ?")) {
            ps.setLong(1, upTo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("audit_log row " + upTo + " missing");
                hash = rs.getString(1);
            }
        }
        int deleted;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM audit_log WHERE audit_id > ? AND audit_id <= ?")) {
            ps.setLong(1, baseId);
            ps.setLong(2, upTo);
            deleted = ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE audit_chain SET base_id = ?, base_hash = ? WHERE chain_id = 1")) {
            ps.setLong(1, upTo);
            ps.setString(2, hash);
            ps.executeUpdate();
        }
        return deleted;
    }

    /** 日次のアンカー（その時点のチェーン先頭）を記録する。同じ日に 2 回目は記録しない。 @return 記録した [last_id, last_hash]。記録済みなら null */
    public String[] anchor(Connection c, java.time.LocalDate date, LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM audit_anchor WHERE anchor_date = ?")) {
            ps.setObject(1, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return null;
            }
        }
        String[] head = head(c);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_anchor (anchor_date, last_id, last_hash, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, date);
            ps.setLong(2, Long.parseLong(head[0]));
            ps.setString(3, head[1]);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            ps.executeUpdate();
        }
        return head;
    }

    /** 起点より後のアンカー（[anchor_date, last_id, last_hash]）。 */
    public List<String[]> anchorsAfter(Connection c, long baseId) throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT anchor_date, last_id, last_hash FROM audit_anchor WHERE last_id > ? ORDER BY anchor_date")) {
            ps.setLong(1, baseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new String[]{String.valueOf(rs.getObject(1)), String.valueOf(rs.getLong(2)), rs.getString(3)});
            }
        }
        return list;
    }

    /** チェーン先頭（最後に追記した行の ID とハッシュ）。 */
    public String[] head(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_id, last_hash FROM audit_chain WHERE chain_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            return new String[]{String.valueOf(rs.getLong(1)), rs.getString(2)};
        }
    }

    // 指定された条件だけを AND でつなぎ、値はすべてバインド変数として params に追加する
    private static String where(Filter f, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (f.from != null) {
            sb.append(" AND occurred_at >= ?");
            params.add(Timestamp.valueOf(f.from));
        }
        if (f.toExclusive != null) {
            sb.append(" AND occurred_at < ?");
            params.add(Timestamp.valueOf(f.toExclusive));
        }
        if (f.userId != null) {
            sb.append(" AND user_id = ?");
            params.add(f.userId);
        }
        if (f.action != null) {
            sb.append(" AND action = ?");
            params.add(f.action);
        }
        if (f.outcome != null) {
            sb.append(" AND outcome = ?");
            params.add(f.outcome);
        }
        return sb.toString();
    }

    // where() で積んだ順にバインド変数を設定する
    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    // SELECT 結果を AuditEntry のリストに変換する
    private static List<AuditEntry> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<AuditEntry> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new AuditEntry(rs.getLong("audit_id"),
                        TS.format(rs.getTimestamp("occurred_at").toLocalDateTime()),
                        rs.getString("user_id"), rs.getString("user_role"), rs.getString("action"),
                        rs.getString("target"), rs.getString("outcome"), rs.getString("detail"),
                        rs.getString("client_ip"), rs.getString("prev_hash"), rs.getString("row_hash"))
                        .withRequestKey(rs.getString("request_key")));
            }
            return list;
        }
    }
}
