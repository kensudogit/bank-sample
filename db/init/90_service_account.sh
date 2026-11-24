#!/bin/bash
# 初回の DB 作成時に、MuleSoft 連携のサービスアカウント（svc-mule）を有効化する（Railway など、
# set_service_password.py を docker compose exec で実行できない環境向け）。
# 環境変数 MULE_SERVICE_PASSWORD_HASH にパスワードのハッシュ（pbkdf2_sha256$回数$ソルト$ハッシュ）だけを渡す。
# パスワード自体はこのサービスに置かない。未設定なら何もしない（svc-mule は無効のまま）。
#
# 注意：gvenzl/oracle-free は *.sh を「source」で読み込む。exit や set -e が起動処理全体に効かないよう、
# 本体はサブシェル（ ( ... ) ）の中で実行する。
(
  set -uo pipefail
  HASH="${MULE_SERVICE_PASSWORD_HASH:-}"
  if [ -z "$HASH" ]; then
    echo "90_service_account: MULE_SERVICE_PASSWORD_HASH is not set; svc-mule stays disabled."
    exit 0
  fi
  if ! [[ "$HASH" =~ ^pbkdf2_sha256\$[0-9]+\$[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+$ ]]; then
    echo "90_service_account: MULE_SERVICE_PASSWORD_HASH has an invalid format; svc-mule stays disabled." >&2
    exit 0
  fi
  # ハッシュに含まれる $ をシェルに展開させないよう、printf の引数で SQL に埋め込む
  printf "WHENEVER SQLERROR EXIT FAILURE
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;
UPDATE app_user SET password_hash = '%s', enabled = 1, failed_count = 0, locked_until = NULL,
  must_change_password = 0, password_changed_at = CURRENT_TIMESTAMP
  WHERE user_id = 'svc-mule' AND role = 'INTEGRATION';
COMMIT;
EXIT
" "$HASH" | sqlplus -s / as sysdba && echo "90_service_account: svc-mule enabled." \
    || echo "90_service_account: failed to enable svc-mule." >&2
)
