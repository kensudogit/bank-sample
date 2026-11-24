-- 外部連携（MuleSoft）のサービスアカウント。
-- ロール INTEGRATION は struts.xml で明示した 3 つの API（被仕向振込の取込・口座振替の請求取込・為替相場の更新）だけを呼べる。
-- 既定では無効（ログイン不可・照合できないハッシュ）。パスワードは .env の MULE_CORE_PASSWORD から
--   python integration/scripts/set_service_password.py
-- で設定し、同時に有効化する（設定しない限りログインできない＝安全側に倒す）。
-- 既存のデータベースには一度だけ適用する:
--   docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/12_integration.sql"
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

-- ロールに INTEGRATION を追加する
ALTER TABLE app_user DROP CONSTRAINT ck_user_role;
ALTER TABLE app_user ADD CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER', 'TELLER', 'SUPERVISOR', 'INTEGRATION'));

INSERT INTO app_user (user_id, display_name, role, customer_id, password_hash, password_changed_at, enabled)
SELECT 'svc-mule', 'MuleSoft 連携（サービス）', 'INTEGRATION', NULL, 'disabled', CURRENT_TIMESTAMP, 0 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE user_id = 'svc-mule');

COMMIT;
