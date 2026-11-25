#!/usr/bin/env bash
# AI Agent（Claude）と Entra ID 互換の認証に必要な秘密情報を作り、.env 形式で標準出力に出す（gen-secrets.sh の追加分）。
#   bash integration/scripts/gen-agent-secrets.sh >> .env
#   bash integration/scripts/gen-agent-secrets.sh >> .env.railway
# 作るもの：モックのテナント ID（tid）、画面（bank-console）と Agent（bank-agent）のクライアントシークレット、
#           auth に渡すクライアント定義（シークレットの SHA-256 だけ）
# ANTHROPIC_API_KEY（Claude API のキー）はここでは作らない。Anthropic のコンソールで発行して別に設定する。
set -euo pipefail

rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "${1:-48}"; }
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
uuid() { local h; h=$(openssl rand -hex 16); printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" $(( (16#${h:16:1} & 3) | 8 )) "${h:17:3}" "${h:20:12}"; }

S_CONSOLE=$(rand); S_AGENT=$(rand)
cat <<EOF
# ---- AI Agent・Entra ID 互換の認証（integration/scripts/gen-agent-secrets.sh で生成。$(date -u +%Y-%m-%dT%H:%M:%SZ)）----
# モックのテナント ID（auth が tid に入れ、Kong と Agent が許可リストで確かめる）
AUTH_TENANT_ID=$(uuid)
# auth に追加するクライアント（画面：ロールなし・セッションブリッジ専用、Agent：メーター用のロール）
AUTH_CLIENTS_AGENT=bank-console:$(sha "$S_CONSOLE"):,bank-agent:$(sha "$S_AGENT"):Agent.Meter
OAUTH_SECRET_CONSOLE=$S_CONSOLE
OAUTH_SECRET_AGENT=$S_AGENT
EOF
