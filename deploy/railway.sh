#!/usr/bin/env bash
# bank-sample の Railway 運用（デモ用。本番利用不可）。
#
#   ブラウザ ─ https://<frontend>.up.railway.app ─ frontend ─ kong.railway.internal:8000 ─ liberty ─ oracle（ボリューム）
#   連携先   ─ https://<kong>.up.railway.app/oauth2/token（auth）・/integration/v1/*（Bearer）─ kong ─ mule（mTLS）─ kong ─ liberty
#   kong ─ redis（レート制限の共有）
#
# 構成（プロジェクト・サービス・ボリューム・ドメイン・秘密でない変数）は Terraform（infra/terraform/railway）で管理する。
# このスクリプトは Terraform に載せない秘密情報の設定と、既存 DB への移行だけを行う。
#
# 前提：railway login 済み・プロジェクトにリンク済み（railway link）。秘密情報は .env.railway（Git 管理外）。
#   bash integration/scripts/gen-secrets.sh --railway >> .env.railway   （外部連携の秘密情報を追加で作る）
# 使い方：bash deploy/railway.sh [secrets|migrate|cleanup|deploy]
#   secrets … 秘密情報を各サービスに設定する（値は標準入力で渡し、コマンドライン・履歴に残さない）
#   migrate … 作成済みの Oracle に db/init の 13・14 を一度だけ適用する（初回作成時は自動で実行される）
#   cleanup … 廃止した API キー（KONG_KEY_*・INTEGRATION_OPS_KEY）を削除する（新しい構成が動いてから）
#   deploy  … GitHub を経由せずに各サービスのソースを直接アップロードしてデプロイする（通常は main への push で自動）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SECRETS_FILE="$ROOT/.env.railway"

[ -f "$SECRETS_FILE" ] || { echo "missing $SECRETS_FILE" >&2; exit 1; }
# KEY=VALUE を文字どおりに読む（ハッシュに含まれる $ をシェルに展開させないため、source しない）
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in ''|'#'*) continue ;; esac
  key="${line%%=*}"; value="${line#*=}"
  [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
  printf -v "$key" '%s' "$value"
  export "${key?}"
done < "$SECRETS_FILE"

railway whoami >/dev/null 2>&1 || { echo "railway login が必要です" >&2; exit 1; }

log() { printf '\n== %s\n' "$*"; }
need() { [ -n "${!1:-}" ] || { echo ".env.railway に $1 がありません（integration/scripts/gen-secrets.sh --railway）" >&2; exit 1; }; }

# 標準入力で秘密の値を設定する（ps やシェル履歴に値を残さない）。--skip-deploys：全部そろってからまとめて再起動する
secret() { need "$3"; printf '%s' "${!3}" | railway variable set "$2" --stdin --service "$1" --skip-deploys >/dev/null; }
# 他のサービスの変数を参照する（値は Railway の中だけで解決される）
ref() { railway variable set "$2=$3" --service "$1" --skip-deploys >/dev/null; }

secrets() {
  log "oracle"
  secret oracle ORACLE_PASSWORD ORACLE_PASSWORD
  secret oracle APP_USER_PASSWORD BANK_DB_PASSWORD
  secret oracle MULE_SERVICE_PASSWORD_HASH MULE_SERVICE_PASSWORD_HASH

  log "liberty（DB の接続情報は oracle の変数を参照。Kong と共有するシークレット）"
  ref    liberty BANK_DB_PASSWORD '${{oracle.APP_USER_PASSWORD}}'
  secret liberty BANK_GATEWAY_SECRET BANK_GATEWAY_SECRET

  log "redis"
  secret redis REDIS_PASSWORD REDIS_PASSWORD

  log "auth（トークンの署名鍵と、クライアントシークレットのハッシュ。画面・AI Agent のクライアントを含む）"
  secret auth AUTH_SIGNING_KEY AUTH_SIGNING_KEY
  need AUTH_CLIENTS_AGENT
  ALL_CLIENTS="$AUTH_CLIENTS,$AUTH_CLIENTS_AGENT"
  secret auth AUTH_CLIENTS ALL_CLIENTS
  secret auth AUTH_TENANT_ID AUTH_TENANT_ID

  log "kong（公開鍵・mTLS のクライアント証明書・Liberty と共有するシークレット・Redis）"
  ref    kong KONG_GATEWAY_SECRET '${{liberty.BANK_GATEWAY_SECRET}}'
  secret kong KONG_TOKEN_PUBLIC_KEY KONG_TOKEN_PUBLIC_KEY
  secret kong KONG_MTLS_CA_CERT MTLS_CA_CERT
  secret kong KONG_MTLS_CLIENT_CERT KONG_MTLS_CLIENT_CERT
  secret kong KONG_MTLS_CLIENT_KEY KONG_MTLS_CLIENT_KEY
  ref    kong KONG_REDIS_PASSWORD '${{redis.REDIS_PASSWORD}}'
  ref    kong KONG_ALLOWED_TENANT '${{auth.AUTH_TENANT_ID}}'

  log "agent（AI アシスタント：Agent のクライアントシークレット・許可するテナント・Claude API のキー）"
  secret agent AGENT_CLIENT_SECRET OAUTH_SECRET_AGENT
  ref    agent AUTH_ALLOWED_TENANTS '${{auth.AUTH_TENANT_ID}}'
  secret agent ANTHROPIC_API_KEY ANTHROPIC_API_KEY

  log "mule（mTLS のサーバー証明書・行員トークンの検証鍵・勘定系のサービスアカウント）"
  secret mule MULE_CORE_PASSWORD MULE_CORE_PASSWORD
  secret mule MULE_TLS_CERT MULE_TLS_CERT
  secret mule MULE_TLS_KEY MULE_TLS_KEY
  secret mule MTLS_CA_CERT MTLS_CA_CERT
  secret mule OPERATOR_TOKEN_SECRET OPERATOR_TOKEN_SECRET

  log "frontend（連携コンソール：OAuth のクライアントシークレット・行員トークンの署名鍵・CSRF の鍵）"
  secret frontend INTEGRATION_CLIENT_SECRET OAUTH_SECRET_OPS
  ref    frontend OPERATOR_TOKEN_SECRET '${{mule.OPERATOR_TOKEN_SECRET}}'
  secret frontend CONSOLE_CSRF_SECRET CONSOLE_CSRF_SECRET
  secret frontend AGENT_CONSOLE_CLIENT_SECRET OAUTH_SECRET_CONSOLE

  # コスト・課金：Railway の使用量を読むトークン（任意。アカウント／ワークスペースのトークン）
  if [ -n "${RAILWAY_API_TOKEN:-}" ]; then secret liberty RAILWAY_API_TOKEN RAILWAY_API_TOKEN; fi

  echo "done（反映は次のデプロイ。main への push、または Railway の Redeploy）"
}

cleanup() {
  log "廃止した API キーを削除（新しい kong・frontend が動いてから。古い kong はこの値がないと起動しない）"
  for v in KONG_KEY_ZENGIN KONG_KEY_BILLING KONG_KEY_FX KONG_KEY_OPS; do railway variable delete "$v" --service kong >/dev/null; done
  railway variable delete INTEGRATION_OPS_KEY --service frontend >/dev/null
  echo "done（kong・frontend が再デプロイされる）"
}

migrate() {
  log "Oracle に 13_integration_hardening.sql と 14_audit_protection.sh を適用（作成済みの DB に一度だけ）"
  railway ssh --service oracle -- bash -c 'export NLS_LANG=AMERICAN_AMERICA.AL32UTF8; \
    sqlplus -s / as sysdba @/container-entrypoint-initdb.d/13_integration_hardening.sql </dev/null && \
    bash /container-entrypoint-initdb.d/14_audit_protection.sh'
}

deploy() {
  log "デプロイ（oracle → liberty → auth → mule → kong → frontend）"
  railway up db --path-as-root --service oracle --detach -m "oracle"
  railway up backend --path-as-root --service liberty --detach -m "liberty"
  railway up integration/auth-server --path-as-root --service auth --detach -m "auth"
  railway up integration/mule-compat --path-as-root --service mule --detach -m "mule-compat"
  railway up integration/kong --path-as-root --service kong --detach -m "kong"
  railway up frontend --path-as-root --service frontend --detach -m "frontend"
}

case "${1:-}" in
  secrets) secrets ;;
  migrate) migrate ;;
  cleanup) cleanup ;;
  deploy) deploy ;;
  *) echo "usage: $0 [secrets|migrate|cleanup|deploy]" >&2; exit 2 ;;
esac
