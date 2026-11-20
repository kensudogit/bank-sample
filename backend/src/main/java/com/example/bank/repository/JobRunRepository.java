package com.example.bank.repository;

import com.example.bank.support.Timestamps;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JOB_RUN（バッチ実行記録）と OPERATION_REQUEST の整理。 */
public class JobRunRepository {

    /**
     * ジョブ・業務日の実行権を取得する（主キーで一意。複数インスタンス・再起動でも 1 日 1 回）。
     *
     * @return false: すでに実行済み（または実行中）
     */
    public boolean claim(Connection c, String jobName, LocalDate runDate, LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                // 「まだ実行していなければ実行する」を SELECT → INSERT で行うと、2 台が同時に「未実行」と判断してしまう。
                // INSERT 一発にして主キー違反で負けた側を判定すれば、同時に動いても実行権を取れるのは 1 台だけ
                "INSERT INTO job_run (job_name, run_date, started_at) VALUES (?, ?, ?)")) {
            ps.setString(1, jobName);
            ps.setDate(2, Date.valueOf(runDate));
            ps.setTimestamp(3, Timestamp.valueOf(now));
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            return false;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1 || (e.getSQLState() != null && e.getSQLState().startsWith("23"))) return false;
            throw e;
        }
    }

    /**
     * ジョブの終了を記録する。
     *
     * @param c       DB 接続
     * @param jobName ジョブ名
     * @param runDate 業務日
     * @param outcome 結果（SUCCESS / FAILURE など）
     * @param detail  詳細（列長に合わせて 400 文字で切り詰める）
     * @param now     終了日時
     * @throws SQLException DB アクセスに失敗した場合
     */
    public void finish(Connection c, String jobName, LocalDate runDate, String outcome, String detail,
                       LocalDateTime now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE job_run SET finished_at = ?, outcome = ?, detail = ? WHERE job_name = ? AND run_date = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(now));
            ps.setString(2, outcome);
            ps.setString(3, detail == null || detail.length() <= 400 ? detail : detail.substring(0, 400));
            ps.setString(4, jobName);
            ps.setDate(5, Date.valueOf(runDate));
            ps.executeUpdate();
        }
    }

    /** 新しい順に最大 limit 件。 */
    public List<Map<String, Object>> recent(Connection c, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT job_name, run_date, started_at, finished_at, outcome, detail "
                + "FROM job_run ORDER BY started_at DESC FETCH FIRST ? ROWS ONLY")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jobName", rs.getString(1));
                    m.put("runDate", rs.getDate(2).toLocalDate().toString());
                    m.put("startedAt", Timestamps.format(rs.getTimestamp(3)));
                    m.put("finishedAt", Timestamps.format(rs.getTimestamp(4)));
                    m.put("outcome", rs.getString(5));
                    m.put("detail", rs.getString(6));
                    list.add(m);
                }
                return list;
            }
        }
    }

    /** 古い冪等キーを削除する。 */
    public int deleteRequestsBefore(Connection c, LocalDateTime cutoff) throws SQLException {
        // 冪等キーの保存期間を過ぎた行を消して表の肥大化を防ぐ（保存期間より後の再送は新しい要求として扱われる）
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM operation_request WHERE created_at < ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(cutoff));
            return ps.executeUpdate();
        }
    }
}
