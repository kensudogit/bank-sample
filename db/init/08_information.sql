-- Information systems (情報系): daily snapshot tables (data warehouse) loaded by the nightly ETL.
-- Management reports, customer analytics and standard reports read only these tables, so they never
-- lock or slow down the transactional (勘定系) tables.
-- Runs after 07_performance.sql on first database creation. For an existing database apply it once:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/08_information.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- Account balances at the end of each business day (rebuilt from the ledger: the last balance_after of the day)
CREATE TABLE dw_account_daily (
  snapshot_date  DATE               NOT NULL,
  account_no     VARCHAR2(7)        NOT NULL,
  customer_id    VARCHAR2(10)       NOT NULL,
  account_type   VARCHAR2(10)       NOT NULL,
  currency       VARCHAR2(3)        NOT NULL,
  status         VARCHAR2(10)       NOT NULL,
  balance        NUMBER(15,0)       NOT NULL,
  balance_jpy    NUMBER(17,0)       NOT NULL,
  CONSTRAINT pk_dw_account_daily PRIMARY KEY (snapshot_date, account_no)
);
CREATE INDEX ix_dw_account_customer ON dw_account_daily (snapshot_date, customer_id);

-- Loan balances at the end of each business day (rebuilt from loan_transaction.outstanding_after)
CREATE TABLE dw_loan_daily (
  snapshot_date   DATE               NOT NULL,
  loan_id         NUMBER(19)         NOT NULL,
  customer_id     VARCHAR2(10)       NOT NULL,
  product         VARCHAR2(12)       NOT NULL,
  grade           VARCHAR2(1)        NOT NULL,
  borrower_class  VARCHAR2(10)       NOT NULL,
  outstanding     NUMBER(15,0)       NOT NULL,
  CONSTRAINT pk_dw_loan_daily PRIMARY KEY (snapshot_date, loan_id)
);

-- Bank-wide KPIs per business day (one row per day)
CREATE TABLE dw_daily_kpi (
  snapshot_date          DATE           NOT NULL,
  total_deposits         NUMBER(17,0)   NOT NULL,
  demand_deposits        NUMBER(17,0)   NOT NULL,
  time_deposits          NUMBER(17,0)   NOT NULL,
  fx_deposits            NUMBER(17,0)   NOT NULL,
  total_loans            NUMBER(17,0)   NOT NULL,
  npl_amount             NUMBER(17,0)   NOT NULL,
  customer_count         NUMBER(10)     NOT NULL,
  account_count          NUMBER(10)     NOT NULL,
  active_accounts        NUMBER(10)     NOT NULL,
  new_accounts           NUMBER(10)     NOT NULL,
  closed_accounts        NUMBER(10)     NOT NULL,
  tx_count               NUMBER(10)     NOT NULL,
  inflow_amount          NUMBER(17,0)   NOT NULL,
  outflow_amount         NUMBER(17,0)   NOT NULL,
  loan_interest_income   NUMBER(17,0)   NOT NULL,
  fee_income             NUMBER(17,0)   NOT NULL,
  deposit_interest_paid  NUMBER(17,0)   NOT NULL,
  loaded_at              TIMESTAMP      NOT NULL,
  loaded_by              VARCHAR2(30)   NOT NULL,
  CONSTRAINT pk_dw_daily_kpi PRIMARY KEY (snapshot_date)
);

-- Customer metrics per business day: assets, loans, RFM scores and the resulting segment
CREATE TABLE dw_customer_daily (
  snapshot_date     DATE               NOT NULL,
  customer_id       VARCHAR2(10)       NOT NULL,
  customer_type     VARCHAR2(10)       NOT NULL,
  total_assets      NUMBER(17,0)       NOT NULL,
  total_loans       NUMBER(17,0)       NOT NULL,
  product_count     NUMBER(3)          NOT NULL,
  last_activity     DATE,
  recency_days      NUMBER(6),
  frequency_90d     NUMBER(6)          NOT NULL,
  r_score           NUMBER(1)          NOT NULL,
  f_score           NUMBER(1)          NOT NULL,
  m_score           NUMBER(1)          NOT NULL,
  segment           VARCHAR2(12)       NOT NULL,
  CONSTRAINT pk_dw_customer_daily PRIMARY KEY (snapshot_date, customer_id),
  CONSTRAINT ck_dw_customer_segment CHECK (segment IN ('LOYAL', 'GROWTH', 'AT_RISK', 'LAPSED', 'STANDARD'))
);

-- The nightly batch loads the previous day; this index serves "latest date" lookups
CREATE INDEX ix_dw_kpi_loaded ON dw_daily_kpi (loaded_at);

COMMENT ON TABLE dw_account_daily IS '情報系: 口座残高の日次スナップショット';
COMMENT ON TABLE dw_loan_daily IS '情報系: 貸出残高の日次スナップショット';
COMMENT ON TABLE dw_daily_kpi IS '情報系: 経営指標の日次集計';
COMMENT ON TABLE dw_customer_daily IS '情報系: 顧客別指標（預かり資産・RFM・セグメント）の日次集計';

COMMIT;
