-- Performance of the information systems (情報系) and period reports: indexes on transaction timestamps
-- Used by the daily warehouse load (the day's transactions, 90-day frequency), and the TX_SUMMARY report.
-- Runs after 09_loan_management.sql on first database creation. For an existing database apply it once:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/10_dw_performance.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- Transactions of one day / one period (daily flows, incremental snapshot, 90-day frequency, transaction summary report)
CREATE INDEX ix_tx_created ON account_transaction (created_at);

-- Loan transactions of one day (loan interest income, incremental loan balances)
CREATE INDEX ix_loan_tx_created ON loan_transaction (created_at);

COMMIT;
