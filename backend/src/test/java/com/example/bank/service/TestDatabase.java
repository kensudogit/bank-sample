package com.example.bank.service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * H2 (Oracle compatibility mode) in-memory database initialised with the same scripts as the Oracle container
 * (db/init/*.sql), minus Oracle-only session statements.
 */
final class TestDatabase implements DataSource {
    private final String url;

    private TestDatabase(String url) { this.url = url; }

    static TestDatabase create() {
        String url = "jdbc:h2:mem:bank-" + UUID.randomUUID()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        TestDatabase db = new TestDatabase(url);
        Path dir = Paths.get(System.getProperty("bank.sqlDir", "../db/init"));
        // Same order as the Oracle container's init: every *.sql file, sorted by name
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().forEach(db::runScript);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir.toAbsolutePath(), e);
        }
        return db;
    }

    private void runScript(Path file) {
        String sql;
        try {
            sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file.toAbsolutePath(), e);
        }
        StringBuilder current = new StringBuilder();
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            for (String line : sql.split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--") || t.toUpperCase().startsWith("ALTER SESSION")
                        || t.toUpperCase().startsWith("ALTER USER")) {
                    continue;
                }
                current.append(line).append('\n');
                if (t.endsWith(";")) {
                    String stmt = current.toString().trim();
                    st.execute(stmt.substring(0, stmt.length() - 1));
                    current.setLength(0);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("script failed: " + file + " near: " + current, e);
        }
    }

    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
