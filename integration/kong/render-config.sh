#!/bin/sh
# kong.yml.tmpl の置き場所を環境変数の値に置き換えて /tmp/kong.yml を作り、Kong を起動する。
#   __KEY_*__             連携先の API キー（KONG_KEY_ZENGIN / BILLING / FX / OPS。32 文字以上の [A-Za-z0-9_-]）
#   __LIBERTY_TARGETS__   勘定系の転送先（KONG_LIBERTY_TARGETS。カンマ区切り。既定は docker compose の 2 台）
#   __INTERNAL_HOSTS__    画面用 API（/bank/api）を受け付ける社内のホスト名（KONG_INTERNAL_HOSTS。既定 kong,kong:8000）
#   __MULE_HOST__         MuleSoft のホスト名（KONG_MULE_HOST。既定 mule）
# 鍵が未設定・形式不正なら起動しない（既定の鍵で動かさない＝安全側に倒す）。
set -eu

fail() { echo "render-config: $1" >&2; exit 1; }

check_key() {
  name="$1"; value="$2"
  case "$value" in
    ''|*[!A-Za-z0-9_-]*) fail "$name must be 32+ chars of [A-Za-z0-9_-]" ;;
  esac
  [ "${#value}" -ge 32 ] || fail "$name must be at least 32 characters"
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

check_key KONG_KEY_ZENGIN "${KONG_KEY_ZENGIN:-}"
check_key KONG_KEY_BILLING "${KONG_KEY_BILLING:-}"
check_key KONG_KEY_FX "${KONG_KEY_FX:-}"
check_key KONG_KEY_OPS "${KONG_KEY_OPS:-}"

TARGETS=$(to_list "${KONG_LIBERTY_TARGETS:-bank-sample-liberty-1:9080,bank-sample-liberty-2:9080}" '{ target: "%s" }')
HOSTS=$(to_list "${KONG_INTERNAL_HOSTS:-kong,kong:8000}" '"%s"')
MULE_HOST="${KONG_MULE_HOST:-mule}"
case "$MULE_HOST" in *[!A-Za-z0-9.-]*|'') fail "invalid KONG_MULE_HOST: $MULE_HOST" ;; esac

sed -e "s/__KEY_ZENGIN__/${KONG_KEY_ZENGIN}/" \
    -e "s/__KEY_BILLING__/${KONG_KEY_BILLING}/" \
    -e "s/__KEY_FX__/${KONG_KEY_FX}/" \
    -e "s/__KEY_OPS__/${KONG_KEY_OPS}/" \
    -e "s/__LIBERTY_TARGETS__/${TARGETS}/" \
    -e "s/__INTERNAL_HOSTS__/${HOSTS}/" \
    -e "s/__MULE_HOST__/${MULE_HOST}/g" \
    /kong/kong.yml.tmpl > /tmp/kong.yml
chmod 600 /tmp/kong.yml

export KONG_DECLARATIVE_CONFIG=/tmp/kong.yml
exec /docker-entrypoint.sh kong docker-start
