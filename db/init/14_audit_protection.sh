#!/bin/bash
# 監査ログの改ざん防止（Oracle のみ。テストの H2 は *.sql だけを読むので対象外）。
#  - audit_log：更新は常に禁止。削除は保存期間（audit_policy）を過ぎた行だけ
#  - audit_chain：チェーン先頭は進めることしかできない（巻き戻し・差し替えの禁止）
#  - audit_anchor（日次の先頭記録）：追記のみ
#  - audit_policy：保存期間は延ばすことしかできない（短くして消すのを防ぐ）
#  - 上の表の DROP・TRUNCATE・ALTER（アプリの DB 利用者 BANK による DDL）を禁止
# アプリの DB 利用者（BANK）では外せない。移行などで外すときは DBA（SYS）が明示的にトリガーを無効にする。
#
# 既存のデータベースには一度だけ適用する（13_integration_hardening.sql の後）:
#   docker compose exec oracle bash /container-entrypoint-initdb.d/14_audit_protection.sh
#
# 注意：gvenzl/oracle-free は *.sh を「source」で読み込むため、本体はサブシェルで実行する。
(
  sqlplus -s / as sysdba <<'SQL'
WHENEVER SQLERROR EXIT FAILURE
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

CREATE OR REPLACE TRIGGER trg_audit_log_guard
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW
DECLARE
  v_days NUMBER;
BEGIN
  IF UPDATING THEN
    RAISE_APPLICATION_ERROR(-20901, 'audit_log is append-only');
  END IF;
  SELECT retention_days INTO v_days FROM audit_policy WHERE policy_id = 1;
  IF :OLD.occurred_at >= SYSTIMESTAMP - NUMTODSINTERVAL(v_days, 'DAY') THEN
    RAISE_APPLICATION_ERROR(-20902, 'audit_log rows within the retention period cannot be deleted');
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_audit_chain_guard
BEFORE UPDATE OR DELETE ON audit_chain
FOR EACH ROW
BEGIN
  IF DELETING THEN
    RAISE_APPLICATION_ERROR(-20903, 'audit_chain cannot be deleted');
  END IF;
  IF :NEW.last_id < :OLD.last_id OR :NEW.base_id < :OLD.base_id
     OR (:NEW.last_id = :OLD.last_id AND :NEW.last_hash <> :OLD.last_hash)
     OR (:NEW.base_id = :OLD.base_id AND :NEW.base_hash <> :OLD.base_hash) THEN
    RAISE_APPLICATION_ERROR(-20904, 'audit_chain can only move forward');
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_audit_anchor_guard
BEFORE UPDATE OR DELETE ON audit_anchor
BEGIN
  RAISE_APPLICATION_ERROR(-20905, 'audit_anchor is append-only');
END;
/

CREATE OR REPLACE TRIGGER trg_audit_policy_guard
BEFORE UPDATE OR DELETE ON audit_policy
FOR EACH ROW
BEGIN
  IF DELETING OR :NEW.retention_days < :OLD.retention_days THEN
    RAISE_APPLICATION_ERROR(-20906, 'the audit retention period can only be extended');
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_audit_ddl_guard
BEFORE DROP OR TRUNCATE OR ALTER OR RENAME ON BANK.SCHEMA
BEGIN
  IF ora_dict_obj_name IN ('AUDIT_LOG', 'AUDIT_CHAIN', 'AUDIT_ANCHOR', 'AUDIT_POLICY',
                           'TRG_AUDIT_LOG_GUARD', 'TRG_AUDIT_CHAIN_GUARD', 'TRG_AUDIT_ANCHOR_GUARD',
                           'TRG_AUDIT_POLICY_GUARD', 'TRG_AUDIT_DDL_GUARD') THEN
    RAISE_APPLICATION_ERROR(-20907, 'audit objects are protected: ' || ora_sysevent || ' ' || ora_dict_obj_name);
  END IF;
END;
/
EXIT
SQL
) && echo "14_audit_protection: audit log protection installed." \
  || echo "14_audit_protection: failed to install audit log protection." >&2
