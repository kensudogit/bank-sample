package com.example.bank.repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 預金商品（定期預金・金利表・為替レート）と、汎用の小さなクエリ。
 * 結果は JSON にそのまま返せるよう Map（列名は camelCase）で返す。
 */
public class ProductRepository {

    // ---------- time deposits ----------

    /** 期間（月）の、基準日時点の定期預金金利（%）。該当なしは null。 */
    public BigDecimal timeDepositRate(Connection c, int termMonths, LocalDate asOf) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT annual_rate FROM time_deposit_rate WHERE term_months = ? "
                + "AND effective_from <= ? ORDER BY effective_from DESC FETCH FIRST 1 ROWS ONLY")) {
            ps.setInt(1, termMonths);
            ps.setDate(2, Date.valueOf(asOf));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    public List<Map<String, Object>> timeDepositRates(Connection c, LocalDate asOf) throws SQLException {
        return query(c, "SELECT r.term_months, r.annual_rate FROM time_deposit_rate r WHERE r.effective_from = "
                + "(SELECT MAX(x.effective_from) FROM time_deposit_rate x WHERE x.term_months = r.term_months "
                + "AND x.effective_from <= ?) ORDER BY r.term_months", Date.valueOf(asOf));
    }

    /** 基準日時点の、普通預金等の金利（%）。中途解約利率にも使う。 */
    public BigDecimal depositRate(Connection c, String rateKey, LocalDate asOf) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT annual_rate FROM interest_rate WHERE account_type = ? "
                + "AND effective_from <= ? ORDER BY effective_from DESC FETCH FIRST 1 ROWS ONLY")) {
            ps.setString(1, rateKey);
            ps.setDate(2, Date.valueOf(asOf));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    public void insertTimeDeposit(Connection c, String accountNo, BigDecimal principal, BigDecimal rate, int termMonths,
                                  LocalDate start, LocalDate maturity, String instruction, String linkedAccountNo)
            throws SQLException {
        execute(c, "INSERT INTO time_deposit (account_no, principal, annual_rate, term_months, start_date, maturity_date, "
                 + "maturity_instruction, linked_account_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                accountNo, principal, rate, termMonths, Date.valueOf(start), Date.valueOf(maturity), instruction,
                linkedAccountNo);
    }

    public Map<String, Object> lockTimeDeposit(Connection c, String accountNo) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM time_deposit WHERE account_no = ? FOR UPDATE", accountNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findTimeDeposit(Connection c, String accountNo) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM time_deposit WHERE account_no = ?", accountNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 満期日が基準日以前の、有効な定期預金の口座番号。 */
    public List<String> dueTimeDeposits(Connection c, LocalDate asOf) throws SQLException {
        List<String> list = new ArrayList<>();
        for (Map<String, Object> m : query(c, "SELECT account_no FROM time_deposit WHERE status = 'ACTIVE' "
                + "AND maturity_date <= ? ORDER BY maturity_date, account_no", Date.valueOf(asOf))) {
            list.add((String) m.get("accountNo"));
        }
        return list;
    }

    public void rolloverTimeDeposit(Connection c, String accountNo, BigDecimal principal, BigDecimal rate,
                                    LocalDate start, LocalDate maturity) throws SQLException {
        execute(c, "UPDATE time_deposit SET principal = ?, annual_rate = ?, start_date = ?, maturity_date = ?, "
                 + "rollover_count = rollover_count + 1 WHERE account_no = ?",
                principal, rate, Date.valueOf(start), Date.valueOf(maturity), accountNo);
    }

    public void setTimeDepositStatus(Connection c, String accountNo, String status) throws SQLException {
        execute(c, "UPDATE time_deposit SET status = ? WHERE account_no = ?", status, accountNo);
    }

    // ---------- FX rates ----------

    public List<Map<String, Object>> fxRates(Connection c) throws SQLException {
        return query(c, "SELECT currency, ttm, spread, ttm + spread AS tts, ttm - spread AS ttb, updated_at, updated_by "
                + "FROM fx_rate ORDER BY currency");
    }

    /** [ttm, spread]。未登録の通貨は null。 */
    public BigDecimal[] fxRate(Connection c, String currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ttm, spread FROM fx_rate WHERE currency = ?")) {
            ps.setString(1, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)} : null;
            }
        }
    }

    public int updateFxRate(Connection c, String currency, BigDecimal ttm, BigDecimal spread, String by,
                            LocalDateTime now) throws SQLException {
        return execute(c, "UPDATE fx_rate SET ttm = ?, spread = ?, updated_at = ?, updated_by = ? WHERE currency = ?",
                ttm, spread, Timestamp.valueOf(now), by, currency);
    }

    // ---------- generic helpers ----------

    /** 結果を camelCase の列名をキーにした Map のリストで返す。 */
    public static List<Map<String, Object>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(200);
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String type = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i).toUpperCase();
                        // Oracle returns TIMESTAMP columns from getObject as oracle.sql.TIMESTAMP: read temporal
                        // columns through getTimestamp, which is portable across drivers
                        Object raw = type.startsWith("TIMESTAMP") || "DATE".equals(type) ? rs.getTimestamp(i) : rs.getObject(i);
                        m.put(camel(md.getColumnLabel(i)), value(raw, type, md.getScale(i)));
                    }
                    list.add(m);
                }
                return list;
            }
        }
    }

    public static int execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** LocalDateTime / LocalDate は JDBC の Timestamp / Date に、null は型付きの NULL にして渡す。 */
    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
            else if (p instanceof LocalDateTime) ps.setTimestamp(i + 1, Timestamp.valueOf((LocalDateTime) p));
            else if (p instanceof LocalDate) ps.setDate(i + 1, Date.valueOf((LocalDate) p));
            else ps.setObject(i + 1, p);
        }
    }

    /** INSERT して生成キー（IDENTITY 列）を返す。 */
    public static long insertReturningId(Connection c, String sql, String idColumn, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, new String[]{idColumn})) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Object value(Object v, String typeName, int scale) throws SQLException {
        // Oracle DATE comes back as a Timestamp, and H2 (Oracle mode) reports DATE as TIMESTAMP(0):
        // a timestamp column without fractional seconds is a date in this schema (TIMESTAMP columns have scale 6)
        boolean dateColumn = "DATE".equalsIgnoreCase(typeName) || (typeName != null
                && typeName.toUpperCase().startsWith("TIMESTAMP") && scale == 0);
        if (v instanceof Timestamp) {
            LocalDateTime t = ((Timestamp) v).toLocalDateTime().withNano(0);
            return dateColumn ? t.toLocalDate().toString() : t.toString();
        }
        if (v instanceof LocalDateTime) {
            LocalDateTime t = ((LocalDateTime) v).withNano(0);
            return dateColumn ? t.toLocalDate().toString() : t.toString();
        }
        if (v instanceof Date) return ((Date) v).toLocalDate().toString();
        if (v instanceof LocalDate) return v.toString();
        if (v instanceof java.sql.Clob) {
            java.sql.Clob clob = (java.sql.Clob) v;
            return clob.getSubString(1, (int) clob.length());
        }
        // Oracle returns some temporal types as its own classes; JDBC 4.2 drivers support getObject(LocalDateTime)
        if (v != null && v.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) return v.toString();
        if (v instanceof java.math.BigInteger) return new BigDecimal((java.math.BigInteger) v);
        if (v instanceof Long || v instanceof Integer || v instanceof Short) return new BigDecimal(v.toString());
        return v;
    }

    private static String camel(String column) {
        String lower = column.toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : lower.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
