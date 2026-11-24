#!/bin/sh
# kong.yml.tmpl の API キーの置き場所（__KEY_*__）を環境変数の値に置き換えて /tmp/kong.yml を作り、Kong を起動する。
# 鍵が未設定・形式不正なら起動しない（既定の鍵で動かさない＝安全側に倒す）。
set -eu

check() {
  name="$1"; value="$2"
  case "$value" in
    ''|*[!A-Za-z0-9_-]*) echo "render-config: $name must be 32+ chars of [A-Za-z0-9_-]" >&2; exit 1 ;;
  esac
  if [ "${#value}" -lt 32 ]; then echo "render-config: $name must be at least 32 characters" >&2; exit 1; fi
}

check KONG_KEY_ZENGIN "${KONG_KEY_ZENGIN:-}"
check KONG_KEY_BILLING "${KONG_KEY_BILLING:-}"
check KONG_KEY_FX "${KONG_KEY_FX:-}"
check KONG_KEY_OPS "${KONG_KEY_OPS:-}"

sed -e "s/__KEY_ZENGIN__/${KONG_KEY_ZENGIN}/" \
    -e "s/__KEY_BILLING__/${KONG_KEY_BILLING}/" \
    -e "s/__KEY_FX__/${KONG_KEY_FX}/" \
    -e "s/__KEY_OPS__/${KONG_KEY_OPS}/" \
    /kong/kong.yml.tmpl > /tmp/kong.yml
chmod 600 /tmp/kong.yml

export KONG_DECLARATIVE_CONFIG=/tmp/kong.yml
exec /docker-entrypoint.sh kong docker-start
