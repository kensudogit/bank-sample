-- Authentication / authorization, audit log (tamper-evident hash chain), batch job runs
-- Runs after 03_lifecycle.sql on first database creation. For an existing database apply it once:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/04_security.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- ---- Users (customers for online banking, bank staff) ----
-- password_hash: pbkdf2_sha256$<iterations>$<salt base64>$<hash base64>
CREATE TABLE app_user (
  user_id               VARCHAR2(30)       NOT NULL,
  display_name          VARCHAR2(50 CHAR)  NOT NULL,
  role                  VARCHAR2(12)       NOT NULL,
  customer_id           VARCHAR2(10),
  password_hash         VARCHAR2(200)      NOT NULL,
  must_change_password  NUMBER(1)          DEFAULT 0 NOT NULL,
  failed_count          NUMBER(3)          DEFAULT 0 NOT NULL,
  locked_until          TIMESTAMP,
  last_login_at         TIMESTAMP,
  password_changed_at   TIMESTAMP,
  enabled               NUMBER(1)          DEFAULT 1 NOT NULL,
  created_at            TIMESTAMP          DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT pk_app_user PRIMARY KEY (user_id),
  CONSTRAINT fk_user_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
  CONSTRAINT uq_user_customer UNIQUE (customer_id),
  CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER', 'TELLER', 'SUPERVISOR')),
  CONSTRAINT ck_user_customer CHECK ((role = 'CUSTOMER' AND customer_id IS NOT NULL)
                                     OR (role <> 'CUSTOMER' AND customer_id IS NULL))
);

-- Who registered the application / who decided KYC (segregation of duties)
ALTER TABLE customer ADD (
  registered_by    VARCHAR2(30),
  kyc_decided_by   VARCHAR2(30)
);

-- ---- Audit log: every row hashes the previous row's hash (改ざん検知) ----
CREATE TABLE audit_log (
  audit_id     NUMBER(19)          NOT NULL,
  occurred_at  TIMESTAMP           NOT NULL,
  user_id      VARCHAR2(30),
  user_role    VARCHAR2(12),
  action       VARCHAR2(40)        NOT NULL,
  target       VARCHAR2(100 CHAR),
  outcome      VARCHAR2(10)        NOT NULL,
  detail       VARCHAR2(400 CHAR),
  client_ip    VARCHAR2(45),
  prev_hash    VARCHAR2(64)        NOT NULL,
  row_hash     VARCHAR2(64)        NOT NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
  CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);
CREATE INDEX ix_audit_time ON audit_log (occurred_at);
CREATE INDEX ix_audit_user ON audit_log (user_id, occurred_at);

-- Head of the chain; locked (FOR UPDATE) while appending so the chain stays linear
CREATE TABLE audit_chain (
  chain_id   NUMBER(1)     NOT NULL,
  last_id    NUMBER(19)    NOT NULL,
  last_hash  VARCHAR2(64)  NOT NULL,
  CONSTRAINT pk_audit_chain PRIMARY KEY (chain_id)
);
INSERT INTO audit_chain (chain_id, last_id, last_hash)
  VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- ---- Batch job runs: the primary key lets only one run per job and business date ----
CREATE TABLE job_run (
  job_name     VARCHAR2(30)        NOT NULL,
  run_date     DATE                NOT NULL,
  started_at   TIMESTAMP           NOT NULL,
  finished_at  TIMESTAMP,
  outcome      VARCHAR2(10),
  detail       VARCHAR2(400 CHAR),
  CONSTRAINT pk_job_run PRIMARY KEY (job_name, run_date),
  CONSTRAINT ck_job_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'FAILURE', 'SKIPPED'))
);

CREATE INDEX ix_operation_request_created ON operation_request (created_at);

COMMENT ON TABLE app_user IS '利用者（顧客のオンラインバンキング・行員）';
COMMENT ON TABLE audit_log IS '監査ログ（ハッシュチェーンで改ざん検知）';
COMMENT ON TABLE job_run IS 'バッチ実行記録（ジョブ・業務日ごとに 1 回）';

-- ---- Demo users (fictional). Password for all demo users: see README (デモユーザー) ----
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('yamada', '山田 太郎', 'CUSTOMER', 'CU0001',
   'pbkdf2_sha256$120000$GGKO8xFIStXnSfCQu7ABkQ==$palil21ygHuOM8/Q64aqhxr7HhomD4rifBLhmnCIX/0=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('sato', '佐藤 花子', 'CUSTOMER', 'CU0002',
   'pbkdf2_sha256$120000$z6a4UnLz1WyIpxVrmIiW6Q==$CocRz5lSIB9KLlSu9buNScKrYK4B1dXDv4zdyqH96vY=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('suzuki', '鈴木 一郎', 'CUSTOMER', 'CU0003',
   'pbkdf2_sha256$120000$JtxlY6Sm+cmQHE/B/ZwKdg==$51S7YTBtWyLH4R65JpQz3nr4jHYCPOKfP7dJ8D3j77g=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('takahashi', '高橋 美咲', 'CUSTOMER', 'CU0004',
   'pbkdf2_sha256$120000$wKtVQsMnduOinSuACk+lfg==$fWhQPHUukFieiRaZCdI2zBovhq2WcrEgQC3w00LyUZ4=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('teller1', '窓口 一郎', 'TELLER', NULL,
   'pbkdf2_sha256$120000$5td1Kf9WtzgDqXKcJ6TeOg==$rERRhOJfiP0heFbIOwT9u1rQO4Bm2oqEpSnkDXiqDyQ=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('supervisor1', '役席 花子', 'SUPERVISOR', NULL,
   'pbkdf2_sha256$120000$7bpUenJ8F7bx5Guext/U5g==$fM5OrBxbpISLDMK7bGDgPv5xVpViyA+h6xTklZ7aHeU=', CURRENT_TIMESTAMP);
INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at) VALUES
  ('supervisor2', '役席 次郎', 'SUPERVISOR', NULL,
   'pbkdf2_sha256$120000$O6Bf8ETifA/fhHhvswx8ew==$kgoxwSrKc9CfstTGdkOWBabW5JF9xzaxZwAsZIypX6g=', CURRENT_TIMESTAMP);

COMMIT;
