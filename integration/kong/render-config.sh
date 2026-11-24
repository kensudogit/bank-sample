#!/bin/sh
# kong.yml.tmpl の __*__ を環境変数の値に置き換えて /tmp/kong.yml を作り、Kong を起動する。
# 値が未設定・形式不正なら起動しない（既定の鍵・素通しで動かさない＝安全側に倒す）。
#
#   KONG_GATEWAY_SECRET     Liberty と共有するシークレット（32 文字以上の [A-Za-z0-9_-]。Liberty の BANK_GATEWAY_SECRET と同じ値）
#   AUTH_MODE               local（既定。auth サービスが発行）/ entra（Microsoft Entra ID が発行）
#   KONG_TOKEN_PUBLIC_KEY   トークン署名の公開鍵（PEM を Base64 で 1 行にしたもの）
#   KONG_TOKEN_ISSUER       iss（local の既定 https://auth.bank-sample.local。entra は ENTRA_TENANT_ID から作る）
#   KONG_TOKEN_AUDIENCE     aud（既定 api://bank-integration。entra はアプリ登録の Application ID URI）
#   KONG_CLIENT_ID_ZENGIN / _BILLING / _FX / _OPS   トークンの azp（local の既定は利用者名。entra はアプリ（クライアント）ID）
#   KONG_MTLS_CA_CERT / KONG_MTLS_CLIENT_CERT / KONG_MTLS_CLIENT_KEY   Kong → MuleSoft の mTLS（PEM を Base64 で 1 行）
#   KONG_REDIS_HOST / KONG_REDIS_PORT / KONG_REDIS_PASSWORD   レート制限の共有先（未設定なら各台で数える。警告を出す）
#   KONG_OTEL_ENDPOINT      OpenTelemetry Collector の OTLP/HTTP（例 http://otel-collector:4318/v1/traces。未設定なら送らない）
#   KONG_LIBERTY_TARGETS / KONG_INTERNAL_HOSTS / KONG_MULE_HOST / KONG_AUTH_HOST   転送先（既定は docker compose の構成）
#   引数 --render-only：Kong を起動せず /tmp/kong.yml（または KONG_RENDER_OUT）を作るだけ（CI の検証用）
set -eu

fail() { echo "render-config: $1" >&2; exit 1; }

check_secret() {
  name="$1"; value="$2"
  case "$value" in
    ''|*[!A-Za-z0-9_-]*) fail "$name must be 32+ chars of [A-Za-z0-9_-]" ;;
  esac
  [ "${#value}" -ge 32 ] || fail "$name must be at least 32 characters"
}

check_name() {
  case "$2" in ''|*[!A-Za-z0-9.:/_-]*) fail "invalid $1: $2" ;; esac
}

# Base64 の PEM を読み、YAML の二重引用符の中に置けるよう改行を \n にする
pem() {
  name="$1"; value="$2"
  [ -n "$value" ] || fail "$name is not set"
  decoded=$(printf '%s' "$value" | base64 -d 2>/dev/null) || fail "$name is not valid base64"
  case "$decoded" in -----BEGIN*) ;; *) fail "$name is not a PEM" ;; esac
  printf '%s\n' "$decoded" | awk 'NF { printf "%s\\n", $0 }'
}

# "a,b" → [{ target: "a" }, { target: "b" }] / ["a", "b"]（値は host[:port] の文字だけを許す）
to_list() {
  list="$1"; template="$2"; out=""
  case "$list" in *[!A-Za-z0-9.:,_-]*|'') fail "invalid host list: $list" ;; esac
  old_ifs="$IFS"; IFS=','
  for item in $list; do
    [ -n "$item" ] || continue
    entry=$(printf "$template" "$item")
    out="${out:+$out, }$entry"
  done
  IFS="$old_ifs"
  printf '[%s]' "$out"
}

check_secret KONG_GATEWAY_SECRET "${KONG_GATEWAY_SECRET:-}"

AUTH_MODE="${AUTH_MODE:-local}"
case "$AUTH_MODE" in
  local)
    ISSUER="${KONG_TOKEN_ISSUER:-https://auth.bank-sample.local}"
    AUDIENCE="${KONG_TOKEN_AUDIENCE:-api://bank-integration}"
    ID_ZENGIN="${KONG_CLIENT_ID_ZENGIN:-zengin-network}"; ID_BILLING="${KONG_CLIENT_ID_BILLING:-billing-agency}"
    ID_FX="${KONG_CLIENT_ID_FX:-fx-provider}"; ID_OPS="${KONG_CLIENT_ID_OPS:-bank-ops}"
    ;;
  entra)
    # Entra ID の v2.0 アクセストークン（アプリ登録のマニフェストで accessTokenAcceptedVersion: 2）
    tenant="${ENTRA_TENANT_ID:-}"
    case "$tenant" in ''|*[!0-9a-f-]*) fail "ENTRA_TENANT_ID must be the tenant GUID" ;; esac
    ISSUER="https://login.microsoftonline.com/${tenant}/v2.0"
    AUDIENCE="${KONG_TOKEN_AUDIENCE:-}"; [ -n "$AUDIENCE" ] || fail "KONG_TOKEN_AUDIENCE (Application ID URI or client ID of the API) is required"
    ID_ZENGIN="${KONG_CLIENT_ID_ZENGIN:-}"; ID_BILLING="${KONG_CLIENT_ID_BILLING:-}"
    ID_FX="${KONG_CLIENT_ID_FX:-}"; ID_OPS="${KONG_CLIENT_ID_OPS:-}"
    ;;
  *) fail "AUTH_MODE must be local or entra" ;;
esac
check_name KONG_TOKEN_ISSUER "$ISSUER"
check_name KONG_TOKEN_AUDIENCE "$AUDIENCE"
for pair in "ZENGIN:$ID_ZENGIN" "BILLING:$ID_BILLING" "FX:$ID_FX" "OPS:$ID_OPS"; do
  check_name "KONG_CLIENT_ID_${pair%%:*}" "${pair#*:}"
done

PUBLIC_KEY=$(pem KONG_TOKEN_PUBLIC_KEY "${KONG_TOKEN_PUBLIC_KEY:-}")
MTLS_CA=$(pem KONG_MTLS_CA_CERT "${KONG_MTLS_CA_CERT:-}")
MTLS_CERT=$(pem KONG_MTLS_CLIENT_CERT "${KONG_MTLS_CLIENT_CERT:-}")
MTLS_KEY=$(pem KONG_MTLS_CLIENT_KEY "${KONG_MTLS_CLIENT_KEY:-}")

if [ -n "${KONG_REDIS_HOST:-}" ]; then
  check_name KONG_REDIS_HOST "$KONG_REDIS_HOST"
  port="${KONG_REDIS_PORT:-6379}"
  case "$port" in ''|*[!0-9]*) fail "invalid KONG_REDIS_PORT" ;; esac
  redis_password="${KONG_REDIS_PASSWORD:-}"
  case "$redis_password" in *[!A-Za-z0-9_-]*) fail "KONG_REDIS_PASSWORD must be [A-Za-z0-9_-]" ;; esac
  # Redis が止まっても通信は止めない（fault_tolerant）。その間は制限が効かないので監視で検知する
  STORE="policy: redis, fault_tolerant: true, redis: { host: \"$KONG_REDIS_HOST\", port: $port, password: \"$redis_password\", timeout: 2000, database: 0 }"
else
  echo "render-config: WARNING KONG_REDIS_HOST is not set; rate limits are counted per Kong node" >&2
  STORE="policy: local"
fi

if [ -n "${KONG_OTEL_ENDPOINT:-}" ]; then
  check_name KONG_OTEL_ENDPOINT "$KONG_OTEL_ENDPOINT"
  OTEL="  - name: opentelemetry
    config: { traces_endpoint: \"$KONG_OTEL_ENDPOINT\", resource_attributes: { service.name: kong }, header_type: w3c, propagation: { default_format: w3c } }"
else
  OTEL=""
fi

TARGETS=$(to_list "${KONG_LIBERTY_TARGETS:-bank-sample-liberty-1:9080,bank-sample-liberty-2:9080}" '{ target: "%s" }')
HOSTS=$(to_list "${KONG_INTERNAL_HOSTS:-kong,kong:8000}" '"%s"')
MULE_HOST="${KONG_MULE_HOST:-mule}"; check_name KONG_MULE_HOST "$MULE_HOST"
AUTH_HOST="${KONG_AUTH_HOST:-auth}"; check_name KONG_AUTH_HOST "$AUTH_HOST"

OUT="${KONG_RENDER_OUT:-/tmp/kong.yml}"
# 値の中の & や \ を特別扱いしないよう、awk で文字列として置き換える
GATEWAY_SECRET="$KONG_GATEWAY_SECRET" PUBLIC_KEY="$PUBLIC_KEY" MTLS_CA="$MTLS_CA" MTLS_CERT="$MTLS_CERT" MTLS_KEY="$MTLS_KEY" \
STORE="$STORE" OTEL="$OTEL" TARGETS="$TARGETS" HOSTS="$HOSTS" MULE_HOST="$MULE_HOST" AUTH_HOST="$AUTH_HOST" \
ISSUER="$ISSUER" AUDIENCE="$AUDIENCE" ID_ZENGIN="$ID_ZENGIN" ID_BILLING="$ID_BILLING" ID_FX="$ID_FX" ID_OPS="$ID_OPS" \
awk '
function rep(s, from, to,    out, i) {
  out = ""
  while ((i = index(s, from)) > 0) { out = out substr(s, 1, i - 1) to; s = substr(s, i + length(from)) }
  return out s
}
{
  if ($0 == "__OTEL_PLUGIN__") { if (ENVIRON["OTEL"] != "") print ENVIRON["OTEL"]; next }
  line = $0
  line = rep(line, "__GATEWAY_SECRET__", ENVIRON["GATEWAY_SECRET"])
  line = rep(line, "__TOKEN_PUBLIC_KEY__", ENVIRON["PUBLIC_KEY"])
  line = rep(line, "__MTLS_CA_CERT__", ENVIRON["MTLS_CA"])
  line = rep(line, "__MTLS_CLIENT_CERT__", ENVIRON["MTLS_CERT"])
  line = rep(line, "__MTLS_CLIENT_KEY__", ENVIRON["MTLS_KEY"])
  line = rep(line, "__RATE_LIMIT_STORE__", ENVIRON["STORE"])
  line = rep(line, "__LIBERTY_TARGETS__", ENVIRON["TARGETS"])
  line = rep(line, "__INTERNAL_HOSTS__", ENVIRON["HOSTS"])
  line = rep(line, "__MULE_HOST__", ENVIRON["MULE_HOST"])
  line = rep(line, "__AUTH_HOST__", ENVIRON["AUTH_HOST"])
  line = rep(line, "__TOKEN_ISSUER__", ENVIRON["ISSUER"])
  line = rep(line, "__TOKEN_AUDIENCE__", ENVIRON["AUDIENCE"])
  line = rep(line, "__CLIENT_ID_ZENGIN__", ENVIRON["ID_ZENGIN"])
  line = rep(line, "__CLIENT_ID_BILLING__", ENVIRON["ID_BILLING"])
  line = rep(line, "__CLIENT_ID_FX__", ENVIRON["ID_FX"])
  line = rep(line, "__CLIENT_ID_OPS__", ENVIRON["ID_OPS"])
  print line
}' /kong/kong.yml.tmpl > "$OUT"
chmod 600 "$OUT"
if grep -q '__[A-Z_]*__' "$OUT"; then fail "unreplaced placeholder in $OUT"; fi

[ "${1:-}" = "--render-only" ] && exit 0

export KONG_DECLARATIVE_CONFIG="$OUT"
exec /docker-entrypoint.sh kong docker-start
