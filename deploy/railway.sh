#!/usr/bin/env bash
# bank-sample を Railway にデプロイする（デモ用。本番利用不可）。
#
#   ブラウザ ─ https://<frontend>.up.railway.app ─ frontend ─ kong.railway.internal:8000 ─ liberty ─ oracle（ボリューム）
#   連携先   ─ https://<kong>.up.railway.app/integration/v1/*（API キー）─ kong ─ mule ─ liberty
#
# 前提：railway login 済み。秘密情報は .env.railway（Git 管理外。値はコマンドライン引数に出さず標準入力で渡す）。
# 使い方：bash deploy/railway.sh [setup|deploy|all]（既定 all）
#   setup  … プロジェクト・サービス・ボリューム・変数・ドメインを作る（作成済みなら飛ばす）
#   deploy … 各サービスのソースをアップロードしてビルド・デプロイする
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROJECT_NAME="${RAILWAY_PROJECT_NAME:-bank-sample}"
SERVICES=(oracle liberty mule kong frontend)
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

# 標準入力で秘密の値を設定する（ps やシェル履歴に値を残さない）
secret() { printf '%s' "$3" | railway variable set "$2" --stdin --service "$1" --skip-deploys >/dev/null; }
# 秘密でない値（参照変数 ${{...}} を含む）を設定する。社内の名前は <サービス名>.railway.internal（デプロイ前でも決まっている）
plain() { railway variable set "$2=$3" --service "$1" --skip-deploys >/dev/null; }

service_exists() { railway service list --json 2>/dev/null | python -c "import sys,json; d=json.load(sys.stdin); names=[s.get('name') for s in (d if isinstance(d,list) else d.get('services',[]))]; sys.exit(0 if '$1' in names else 1)"; }

setup() {
  if ! railway status >/dev/null 2>&1; then
    log "プロジェクト $PROJECT_NAME を作成"
    railway init --name "$PROJECT_NAME"
  fi

  for s in "${SERVICES[@]}"; do
    if service_exists "$s"; then echo "service $s: exists"; else log "サービス $s を作成"; railway add --service "$s"; fi
  done

  log "oracle：Oracle Database 23ai Free（初回起動で db/init の SQL を実行し、svc-mule をハッシュで有効化）"
  secret oracle ORACLE_PASSWORD "$ORACLE_PASSWORD"
  plain  oracle APP_USER "$BANK_DB_USER"
  secret oracle APP_USER_PASSWORD "$BANK_DB_PASSWORD"
  secret oracle MULE_SERVICE_PASSWORD_HASH "$MULE_SERVICE_PASSWORD_HASH"
  plain  oracle NLS_LANG "AMERICAN_AMERICA.AL32UTF8"
  railway service link oracle >/dev/null
  if ! railway volume list --json 2>/dev/null | grep -q '/opt/oracle/oradata'; then
    railway volume add --mount-path /opt/oracle/oradata
  fi

  log "liberty：勘定系 API（WebSphere Liberty）"
  plain  liberty BANK_DB_URL 'jdbc:oracle:thin:@//oracle.railway.internal:1521/FREEPDB1'
  plain  liberty BANK_DB_USER '${{oracle.APP_USER}}'
  plain  liberty BANK_DB_PASSWORD '${{oracle.APP_USER_PASSWORD}}'
  plain  liberty BANK_JOBS_ENABLED true
  plain  liberty BANK_COOKIE_SECURE true
  plain  liberty TZ Asia/Tokyo
  plain  liberty PORT 9080

  log "mule：MuleSoft 互換コンテナ（公開しない）"
  plain  mule CORE_BASE_URL 'http://liberty.railway.internal:9080/bank/api'
  plain  mule MULE_CORE_USER svc-mule
  secret mule MULE_CORE_PASSWORD "$MULE_CORE_PASSWORD"
  plain  mule OUR_BANK_CODE 9999
  plain  mule TZ Asia/Tokyo
  plain  mule PORT 8081

  log "kong：API ゲートウェイ"
  plain  kong KONG_DATABASE off
  plain  kong KONG_PROXY_LISTEN '0.0.0.0:8000, [::]:8000'
  plain  kong KONG_ADMIN_LISTEN off
  plain  kong KONG_STATUS_LISTEN '[::]:8100'
  plain  kong KONG_HEADERS off
  plain  kong KONG_PROXY_ACCESS_LOG /dev/stdout
  plain  kong KONG_PROXY_ERROR_LOG /dev/stderr
  plain  kong KONG_TRUSTED_IPS '10.0.0.0/8,100.64.0.0/10,172.16.0.0/12,192.168.0.0/16,fc00::/7'
  plain  kong KONG_REAL_IP_HEADER X-Forwarded-For
  plain  kong KONG_REAL_IP_RECURSIVE on
  plain  kong KONG_NGINX_WORKER_PROCESSES 2
  plain  kong KONG_LIBERTY_TARGETS 'liberty.railway.internal:9080'
  plain  kong KONG_INTERNAL_HOSTS 'kong.railway.internal:8000'
  plain  kong PORT 8000
  secret kong KONG_KEY_ZENGIN "$KONG_KEY_ZENGIN"
  secret kong KONG_KEY_BILLING "$KONG_KEY_BILLING"
  secret kong KONG_KEY_FX "$KONG_KEY_FX"
  secret kong KONG_KEY_OPS "$KONG_KEY_OPS"
  # mule のホスト名は kong.yml.tmpl では "mule"。Railway の社内名に合わせる
  plain  kong KONG_MULE_HOST 'mule.railway.internal'

  log "frontend：画面（Next.js）"
  plain  frontend BANK_API_ORIGIN 'http://kong.railway.internal:8000'
  plain  frontend BANK_API_INTERNAL 'http://kong.railway.internal:8000/bank/api'
  plain  frontend INTEGRATION_GATEWAY_URL 'http://kong.railway.internal:8000/integration/v1'
  plain  frontend INTEGRATION_OPS_KEY '${{kong.KONG_KEY_OPS}}'
  plain  frontend HOSTNAME '::'
  plain  frontend PORT 3000

  log "公開ドメイン（frontend：画面、kong：連携先の入口）"
  railway domain list --service frontend 2>/dev/null | grep -q 'railway.app' || railway domain --service frontend --port 3000
  railway domain list --service kong 2>/dev/null | grep -q 'railway.app' || railway domain --service kong --port 8000
}

deploy() {
  log "デプロイ（oracle → liberty → mule → kong → frontend）"
  railway up db --path-as-root --service oracle --detach -m "oracle"
  railway up backend --path-as-root --service liberty --detach -m "liberty"
  railway up integration/mule-compat --path-as-root --service mule --detach -m "mule-compat"
  railway up integration/kong --path-as-root --service kong --detach -m "kong"
  railway up frontend --path-as-root --service frontend --detach -m "frontend"
}

case "${1:-all}" in
  setup) setup ;;
  deploy) deploy ;;
  all) setup; deploy ;;
  *) echo "usage: $0 [setup|deploy|all]" >&2; exit 2 ;;
esac
