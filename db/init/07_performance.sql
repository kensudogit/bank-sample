-- Performance: indexes on lookup / foreign-key columns used by screens and batch jobs
-- Runs after 06_reliability.sql on first database creation. For an existing database apply it once:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/07_performance.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- Loans whose repayment account is this account (account closing check, overdraft interest at every settlement)
CREATE INDEX ix_loan_repay_account ON loan (repayment_account_no);

-- Collateral / collection history of one application or loan (screening and loan detail screens)
CREATE INDEX ix_collateral_app ON loan_collateral (application_id);
CREATE INDEX ix_collection_loan ON collection_action (loan_id);

-- Direct debit mandates and foreign remittances of a customer's accounts (lists, customer 360)
CREATE INDEX ix_dd_mandate_account ON direct_debit_mandate (account_no);
CREATE INDEX ix_fremit_account ON foreign_remittance (account_no);

COMMIT;
