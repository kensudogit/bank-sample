package com.example.bank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 冪等キーの登録。同じキーの二重実行を主キー制約で防ぐ。 */
public class OperationRequestRepository {

    /** @return false if the key was already registered (duplicate request) */
    public boolean register(Connection c, String idempotencyKey, String operationType, LocalDateTime now)
            throws SQLException {
        String sql = "INSERT INTO operation_request (idempotency_key, operation_type, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, operationType);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false;
        } catch (SQLException e) {
            // ORA-00001 (unique constraint) / SQLState class 23 when the driver does not use the subclass
            if (e.getErrorCode() == 1 || (e.getSQLState() != null && e.getSQLState().startsWith("23"))) return false;
            throw e;
        }
    }
}
