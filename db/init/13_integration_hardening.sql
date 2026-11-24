-- 外部連携の強化：実行履歴の永続化・冪等性（再処理の状態管理）・二者承認（職務分離）・監査ログの保存期間。
-- Oracle の初回作成時に 12_integration.sql の後で実行される（テストの H2 でも同じスクリプトを使う）。
-- 既存のデータベースには一度だけ適用する（14_audit_protection.sh も続けて実行する）:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/13_integration_hardening.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- ---- 連携の実行履歴（MuleSoft が勘定系の API 経由で記録する。再起動しても消えない） ----
-- 冪等性：同じ利用者・同じ種類・同じ冪等キーの実行は 1 件だけ（一意制約）。
--   完了（COMPLETED）した実行への再送は保存した応答をそのまま返し、業務処理を繰り返さない。
--   失敗（FAILED：勘定系の障害など再試行できるもの）や、処理中のまま期限（lease_until）を過ぎた実行は再処理できる。
CREATE TABLE integration_run (
  run_id           VARCHAR2(36)        NOT NULL,
  kind             VARCHAR2(20)        NOT NULL,
  consumer         VARCHAR2(40)        NOT NULL,
  idempotency_key  VARCHAR2(80)        NOT NULL,
  payload_sha256   VARCHAR2(64)        NOT NULL,
  status           VARCHAR2(12)        NOT NULL,
  outcome          VARCHAR2(10),
  operator         VARCHAR2(30),
  approver         VARCHAR2(30),
  request_id       VARCHAR2(64),
  summary          VARCHAR2(400 CHAR),
  http_status      NUMBER(3),
  response_json    CLOB,
  attempts         NUMBER(5)           DEFAULT 1 NOT NULL,
  started_at       TIMESTAMP           NOT NULL,
  lease_until      TIMESTAMP,
  finished_at      TIMESTAMP,
  duration_ms      NUMBER(10),
  CONSTRAINT pk_integration_run PRIMARY KEY (run_id),
  CONSTRAINT uq_integration_run_key UNIQUE (consumer, kind, idempotency_key),
  CONSTRAINT ck_integration_run_kind CHECK (kind IN ('ZENGIN_INCOMING', 'BILLING_IMPORT', 'FX_RATES')),
  CONSTRAINT ck_integration_run_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
  CONSTRAINT ck_integration_run_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'PARTIAL', 'FAILURE'))
);
CREATE INDEX ix_integration_run_started ON integration_run (started_at);

-- ---- 画面からの手動送信（二者承認）。作成者（maker）と承認者（approver）は別の役席でなければならない ----
-- 承認されるまで Kong・MuleSoft には送らない。送信の可否・結果・再送の回数もここに残る。
CREATE TABLE integration_request (
  request_id       VARCHAR2(36)        NOT NULL,
  kind             VARCHAR2(20)        NOT NULL,
  content_type     VARCHAR2(60)        NOT NULL,
  payload          BLOB                NOT NULL,
  payload_sha256   VARCHAR2(64)        NOT NULL,
  payload_bytes    NUMBER(10)          NOT NULL,
  file_name        VARCHAR2(200 CHAR),
  comment_text     VARCHAR2(200 CHAR),
  status           VARCHAR2(12)        NOT NULL,
  maker            VARCHAR2(30)        NOT NULL,
  created_at       TIMESTAMP           NOT NULL,
  approver         VARCHAR2(30),
  decided_at       TIMESTAMP,
  reject_reason    VARCHAR2(200 CHAR),
  attempts         NUMBER(5)           DEFAULT 0 NOT NULL,
  dispatched_at    TIMESTAMP,
  completed_at     TIMESTAMP,
  run_id           VARCHAR2(36),
  result_status    NUMBER(3),
  result_json      CLOB,
  CONSTRAINT pk_integration_request PRIMARY KEY (request_id),
  CONSTRAINT ck_integration_request_kind CHECK (kind IN ('ZENGIN_INCOMING', 'BILLING_IMPORT', 'FX_RATES')),
  CONSTRAINT ck_integration_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SENT', 'FAILED')),
  -- 職務分離をデータベースでも保証する（アプリケーションの不具合で同じ人が承認しても記録できない）
  CONSTRAINT ck_integration_request_sod CHECK (approver IS NULL OR approver <> maker)
);
CREATE INDEX ix_integration_request_created ON integration_request (created_at);

-- ---- 監査ログの保存期間と外部アンカー ----
-- 保存期間（既定 10 年）を過ぎた行だけを古い順に削除できる。削除した範囲の最後の行のハッシュを
-- チェーンの起点（base_id / base_hash）として残すので、削除後も残りの行のつながりを検証できる。
ALTER TABLE audit_chain ADD (
  base_id    NUMBER(19)    DEFAULT 0 NOT NULL,
  base_hash  VARCHAR2(64)  DEFAULT '0000000000000000000000000000000000000000000000000000000000000000' NOT NULL
);
CREATE TABLE audit_policy (
  policy_id       NUMBER(1)  NOT NULL,
  retention_days  NUMBER(5)  NOT NULL,
  CONSTRAINT pk_audit_policy PRIMARY KEY (policy_id),
  CONSTRAINT ck_audit_policy_days CHECK (retention_days >= 365)
);
INSERT INTO audit_policy (policy_id, retention_days) VALUES (1, 3650);
-- 毎日のチェーン先頭（最終 ID・最終ハッシュ）。アプリケーションログ（外部の保存先）にも同じ値を出力し、
-- データベースだけを書き換えても過去の先頭と食い違うようにする
CREATE TABLE audit_anchor (
  anchor_date  DATE          NOT NULL,
  last_id      NUMBER(19)    NOT NULL,
  last_hash    VARCHAR2(64)  NOT NULL,
  created_at   TIMESTAMP     NOT NULL,
  CONSTRAINT pk_audit_anchor PRIMARY KEY (anchor_date)
);

COMMENT ON TABLE integration_run IS '外部連携の実行履歴（冪等キー・再処理の状態）';
COMMENT ON TABLE integration_request IS '連携コンソールからの手動送信（二者承認）';
COMMENT ON TABLE audit_anchor IS '監査ログのチェーン先頭の日次記録（改ざん検知の外部アンカー）';

COMMIT;
