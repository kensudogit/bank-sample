-- Reliability: idempotent replay, tamper-evident ledger, duplicate protection for files from outside
-- Runs after 05_banking.sql on first database creation. For an existing database apply it once:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/06_reliability.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- Idempotency: keep the result of a completed request so that a retry with the same key gets the same answer
ALTER TABLE operation_request ADD (
  fingerprint   VARCHAR2(200),
  result_json   CLOB,
  completed_at  TIMESTAMP
);

-- Ledger: every transaction row carries a SHA-256 chained to the previous row of the same account
-- (computed while the account row is locked, so the chain is linear per account)
ALTER TABLE account_transaction ADD (
  prev_hash  VARCHAR2(64),
  row_hash   VARCHAR2(64)
);

-- Incoming credits from other banks: the sender's reference is registered once per sending bank
-- (a separate table: a composite unique index with a NULL column would also reject outgoing rows in Oracle)
ALTER TABLE domestic_remittance ADD (external_ref VARCHAR2(40));
CREATE TABLE incoming_reference (
  bank_code     VARCHAR2(4)   NOT NULL,
  external_ref  VARCHAR2(40)  NOT NULL,
  remit_id      NUMBER(19)    NOT NULL,
  received_at   TIMESTAMP     NOT NULL,
  CONSTRAINT pk_incoming_reference PRIMARY KEY (bank_code, external_ref)
);

-- Billing from the same biller for the same payer and due date is imported once
CREATE UNIQUE INDEX uq_dd_request ON direct_debit_request (mandate_id, due_date);

-- Audit log: the idempotency key of the request, to link ledger rows to the operation (who / when / where)
ALTER TABLE audit_log ADD (request_key VARCHAR2(64));
CREATE INDEX ix_audit_request_key ON audit_log (request_key);

COMMIT;
