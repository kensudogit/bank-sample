#!/usr/bin/env bash
# 外部連携の強化に必要な秘密情報を作り、.env 形式（KEY=value）で標準出力に出す。リポジトリには保存しない。
#   bash integration/scripts/gen-secrets.sh >> .env           （ローカル）
#   bash integration/scripts/gen-secrets.sh --railway >> .env.railway
# 作るもの：
#   - Kong ⇔ Liberty の共有シークレット（ゲートウェイ迂回の防止）
#   - OAuth 2.0 のトークン署名鍵（RSA 2048）と、連携先 4 者のクライアントシークレット（auth にはハッシュだけを渡す）
#   - Kong → MuleSoft の mTLS：社内 CA・MuleSoft のサーバー証明書・Kong のクライアント証明書（CA の秘密鍵は捨てる）
#   - 行員 ID の署名鍵（連携コンソール → MuleSoft）、CSRF トークンの鍵、Redis のパスワード
# 必要なもの：openssl・base64・sha256sum（Git Bash・Linux・macOS に標準で入っている）
set -euo pipefail

RAILWAY=false
[ "${1:-}" = "--railway" ] && RAILWAY=true
MULE_SANS="DNS:mule,DNS:localhost,IP:127.0.0.1"
$RAILWAY && MULE_SANS="DNS:mule.railway.internal,DNS:mule"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "${1:-48}"; }
b64() { base64 < "$1" | tr -d '\n'; }
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

# ---- OAuth 2.0 ----
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out token.key 2>/dev/null
openssl pkey -in token.key -pubout -out token.pub 2>/dev/null
S_ZENGIN=$(rand); S_BILLING=$(rand); S_FX=$(rand); S_OPS=$(rand)

# ---- mTLS（社内 CA → サーバー証明書・クライアント証明書）----
export MSYS_NO_PATHCONV=1
openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=bank-sample internal CA" \
  -keyout ca.key -out ca.crt -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=mule" -keyout mule.key -out mule.csr 2>/dev/null
printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n' "$MULE_SANS" > mule.ext
openssl x509 -req -in mule.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile mule.ext -out mule.crt 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=kong-gateway" -keyout kong.key -out kong.csr 2>/dev/null
printf 'extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n' > kong.ext
openssl x509 -req -in kong.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile kong.ext -out kong.crt 2>/dev/null
# PKCS#8 にそろえる（Kong・Node.js のどちらでも読める）
openssl pkcs8 -topk8 -nocrypt -in kong.key -out kong.p8 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in mule.key -out mule.p8 2>/dev/null

cat <<EOF
# ---- 外部連携の強化（integration/scripts/gen-secrets.sh で生成。$(date -u +%Y-%m-%dT%H:%M:%SZ)）----
# ゲートウェイ迂回の防止（Kong と Liberty で同じ値）
BANK_GATEWAY_SECRET=$(rand 48)
# OAuth 2.0（auth は署名鍵とシークレットのハッシュ、Kong は公開鍵だけを持つ）
AUTH_SIGNING_KEY=$(b64 token.key)
KONG_TOKEN_PUBLIC_KEY=$(b64 token.pub)
AUTH_CLIENTS=zengin-network:$(sha "$S_ZENGIN"):Zengin.Submit+Integration.Health,billing-agency:$(sha "$S_BILLING"):Billing.Submit+Integration.Health,fx-provider:$(sha "$S_FX"):Fx.Submit+Integration.Health,bank-ops:$(sha "$S_OPS"):Ops.Submit+Ops.Read+Integration.Health
# 連携先に安全な経路で渡すクライアントシークレット（bank-ops は frontend が使う）
OAUTH_SECRET_ZENGIN=$S_ZENGIN
OAUTH_SECRET_BILLING=$S_BILLING
OAUTH_SECRET_FX=$S_FX
OAUTH_SECRET_OPS=$S_OPS
# Kong → MuleSoft の mTLS
MTLS_CA_CERT=$(b64 ca.crt)
MULE_TLS_CERT=$(b64 mule.crt)
MULE_TLS_KEY=$(b64 mule.p8)
KONG_MTLS_CLIENT_CERT=$(b64 kong.crt)
KONG_MTLS_CLIENT_KEY=$(b64 kong.p8)
# 連携コンソール → MuleSoft の行員 ID の署名、CSRF トークン、Redis
OPERATOR_TOKEN_SECRET=$(rand 48)
CONSOLE_CSRF_SECRET=$(rand 48)
REDIS_PASSWORD=$(rand 32)
EOF
