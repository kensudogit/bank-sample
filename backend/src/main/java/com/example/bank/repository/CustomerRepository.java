package com.example.bank.repository;

import com.example.bank.domain.Customer;
import com.example.bank.domain.NewCustomer;
import com.example.bank.support.Timestamps;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** CUSTOMER（顧客・本人確認）への JDBC アクセス。 */
public class CustomerRepository {

    private static final String SELECT =
            "SELECT customer_id, name, name_kana, birth_date, postal_code, address, phone, id_doc_type, id_doc_last4, "
          + "kyc_status, kyc_decided_at, kyc_note, created_at, registered_by, kyc_decided_by, customer_type FROM customer ";

    public String nextCustomerId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT customer_seq.NEXTVAL FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return String.format("CU%04d", rs.getLong(1));
        }
    }

    /** 本人確認書類の番号は保存しない（下 4 桁のみ）。 */
    public void insert(Connection c, String customerId, NewCustomer n, String registeredBy, LocalDateTime now)
            throws SQLException {
        String sql = "INSERT INTO customer (customer_id, name, name_kana, birth_date, postal_code, address, phone, "
                   + "id_doc_type, id_doc_last4, kyc_status, created_at, registered_by, customer_type) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, customerId);
            ps.setString(2, n.name);
            ps.setString(3, n.nameKana);
            ps.setDate(4, Date.valueOf(n.birthDate));
            ps.setString(5, n.postalCode);
            ps.setString(6, n.address);
            ps.setString(7, n.phone);
            ps.setString(8, n.idDocType);
            ps.setString(9, n.idDocNumber.substring(n.idDocNumber.length() - 4));
            ps.setTimestamp(10, Timestamp.valueOf(now));
            ps.setString(11, registeredBy);
            ps.setString(12, n.customerType == null ? "INDIVIDUAL" : n.customerType);
            ps.executeUpdate();
        }
    }

    /** 名寄せ: 同じカナ氏名・生年月日の顧客が存在するか。 */
    public boolean existsByKanaAndBirthDate(Connection c, String nameKana, LocalDate birthDate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM customer WHERE name_kana = ? AND birth_date = ? AND kyc_status <> 'REJECTED'")) {
            ps.setString(1, nameKana);
            ps.setDate(2, Date.valueOf(birthDate));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Customer> findAll(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + "ORDER BY customer_id")) {
            ps.setFetchSize(200);
            try (ResultSet rs = ps.executeQuery()) {
                List<Customer> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            }
        }
    }

    public Optional<Customer> findById(Connection c, String customerId) throws SQLException {
        return one(c, SELECT + "WHERE customer_id = ?", customerId);
    }

    /** 行ロック付きで取得（口座開設・本人確認の判定を顧客単位で直列化する）。 */
    public Optional<Customer> lockById(Connection c, String customerId) throws SQLException {
        return one(c, SELECT + "WHERE customer_id = ? FOR UPDATE", customerId);
    }

    public void decideKyc(Connection c, String customerId, String status, String note, String decidedBy,
                          LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE customer SET kyc_status = ?, kyc_note = ?, "
                + "kyc_decided_at = ?, kyc_decided_by = ? WHERE customer_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, note);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            ps.setString(4, decidedBy);
            ps.setString(5, customerId);
            if (ps.executeUpdate() != 1) throw new SQLException("customer row not updated: " + customerId);
        }
    }

    private static Optional<Customer> one(Connection c, String sql, String customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.<Customer>empty();
            }
        }
    }

    private static Customer map(ResultSet rs) throws SQLException {
        Date birth = rs.getDate("birth_date");
        return new Customer(
                rs.getString("customer_id"),
                rs.getString("name"),
                rs.getString("name_kana"),
                birth == null ? null : birth.toLocalDate().toString(),
                rs.getString("postal_code"),
                rs.getString("address"),
                rs.getString("phone"),
                rs.getString("id_doc_type"),
                rs.getString("id_doc_last4"),
                rs.getString("kyc_status"),
                Timestamps.format(rs.getTimestamp("kyc_decided_at")),
                rs.getString("kyc_note"),
                Timestamps.format(rs.getTimestamp("created_at")),
                rs.getString("registered_by"),
                rs.getString("kyc_decided_by"),
                null).withCustomerType(rs.getString("customer_type"));
    }
}
