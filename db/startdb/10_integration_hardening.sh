#!/bin/bash
# 起動のたびに実行される（gvenzl/oracle-free の /container-entrypoint-startdb.d）。
# 作成済みの DB に外部連携の強化（13_integration_hardening.sql・14_audit_protection.sh）がまだなければ、一度だけ適用する。
# 適用済みなら何もしない（表・トリガーの有無で判定するので、何度起動しても安全）。
# Railway のように SQL*Plus を手で実行しにくい環境で、既存のボリュームの DB を移行するためのもの。
#
# 注意：gvenzl/oracle-free は *.sh を「source」で読み込むため、本体はサブシェルで実行する。
(
  INIT=/container-entrypoint-initdb.d
  count() {
    sqlplus -s / as sysdba <<SQL | tr -d '[:space:]'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0
ALTER SESSION SET CONTAINER = FREEPDB1;
$1
EXIT
SQL
  }
  tables=$(count "SELECT COUNT(*) FROM dba_tables WHERE owner = 'BANK' AND table_name = 'INTEGRATION_RUN';")
  case "$tables" in ''|*[!0-9]*) echo "10_integration_hardening: database not ready; skipped" >&2; exit 0 ;; esac
  if [ "$tables" = "0" ]; then
    echo "10_integration_hardening: applying 13_integration_hardening.sql"
    NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus -s / as sysdba @"$INIT/13_integration_hardening.sql" </dev/null
  fi
  triggers=$(count "SELECT COUNT(*) FROM dba_triggers WHERE owner = 'BANK' AND trigger_name = 'TRG_AUDIT_DDL_GUARD';")
  if [ "$triggers" = "0" ]; then
    echo "10_integration_hardening: applying 14_audit_protection.sh"
    bash "$INIT/14_audit_protection.sh"
  fi
  echo "10_integration_hardening: up to date (integration_run=${tables:-?}, audit triggers=${triggers:-?} before this start)"
)
