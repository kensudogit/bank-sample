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

    public AuditEntry append(Connection c, LocalDateTime at, String userId, String role, String action, String target,
                             String outcome, String detail, String clientIp, String requestKey) throws SQLException {
        long lastId;
        String lastHash;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_id, last_hash FROM audit_chain WHERE chain_id = 1 FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            lastId = rs.getLong(1);
            lastHash = rs.getString(2);
        }
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
                "UPDATE audit_chain SET last_id = ?, last_hash = ? WHERE chain_id = 1")) {
            ps.setLong(1, id);
            ps.setString(2, hash);
            ps.executeUpdate();
        }
        return new AuditEntry(id, occurredAt, userId, role, action, target, outcome, detail, clientIp, lastHash, hash)
                .withRequestKey(requestKey);
    }

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

    /** チェーン先頭（最後に追記した行の ID とハッシュ）。 */
    public String[] head(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_id, last_hash FROM audit_chain WHERE chain_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("audit_chain head missing");
            return new String[]{String.valueOf(rs.getLong(1)), rs.getString(2)};
        }
    }

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

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

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
