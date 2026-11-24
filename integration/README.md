# 外部連携（Kong × MuleSoft）

永劫銀行（bank-sample）の API ゲートウェイと外部連携の層です。デモ用サンプル（架空データ）。本番利用不可。

```
ブラウザ ─ lb:3000 ─ frontend ×2 ─(Host: kong)─ Kong :8000 /bank/api/* ─(共有シークレット)─ liberty ×2 ─ Oracle
                                                    │                                         ▲
他行（全銀）・収納企業・相場提供元                     │                                         │
  ├─ 127.0.0.1:8088 Kong /oauth2/token ─ auth（OAuth 2.0 クライアントクレデンシャル・RS256）    │
  └─ 127.0.0.1:8088 Kong /integration/v1/*（Bearer：署名・iss・aud・exp・roles＋ACL）          │
        └─(mTLS)─ mule:8443 /api/v1/*（検証・変換・冪等性・再試行）─ Kong /bank/api ──────────┘
  Kong ─ redis（レート制限を全台で共有）
```

| 層 | 役割 | 実体 |
|---|---|---|
| Kong Gateway（OSS 3.9・DB-less） | 唯一の入口：経路・OAuth 2.0 のトークン検証（jwt＋独自プラグイン `bank-token-claims`）・ACL・流量制限（Redis）・サイズ制限・要求 ID・Prometheus 指標・OpenTelemetry・Liberty のヘルスチェック・Liberty への共有シークレットの付与・MuleSoft への mTLS | `kong/`（`kong.yml.tmpl` を起動時に描画） |
| トークン発行（auth） | OAuth 2.0 クライアントクレデンシャル。RS256 の JWT（Entra ID と同じ形：`iss`・`aud`・`azp`・`roles`）。本番は Entra ID に切り替える | `auth-server/`（Node.js 22・依存なし） |
| MuleSoft（Process API） | 外部形式 → 勘定系形式の変換：全銀 120 桁固定長・CSV・相場 JSON の検証、DataWeave、100 件ずつの送信、冪等性（Idempotency-Key）、再試行、RFC 9457 | `mulesoft/bank-integration`（Mule 4 アプリ一式） |
| MuleSoft 互換コンテナ | Mule ランタイムが無い環境で、同じ API 契約（`bank-integration.yaml`）を提供する | `mule-compat/`（Node.js 22・依存なし） |
| 勘定系 | 業務ルール・二重入金の防止（一意制約）・実行履歴と二者承認の保存・監査ログ（改ざん防止・保存期間） | Liberty（ロール `INTEGRATION` のサービスアカウント `svc-mule`）・Oracle |

- 強化項目（実行履歴の永続化・二重入金防止・職務分離・ゲートウェイ迂回防止など 12 項目）の実装と確認方法：[docs/hardening.md](docs/hardening.md)
- 配置の判断：[ADR-001](docs/ADR-001-gateway-and-integration.md)・[ADR-002](docs/ADR-002-integration-hardening.md)
- API ごとの経路：[docs/api-routing-matrix.md](docs/api-routing-matrix.md)／認証・認可：[docs/security-design.md](docs/security-design.md)
- 運用：[docs/operations-runbook.md](docs/operations-runbook.md)／本番化の条件：[docs/acceptance-checklist.md](docs/acceptance-checklist.md)

## 初回のセットアップ

1. 秘密情報を作って `.env` に追加する（Git Bash・Linux・macOS。openssl を使う）

   ```bash
   bash integration/scripts/gen-secrets.sh >> .env
   python -c "import secrets;print('MULE_CORE_PASSWORD=Mule-'+secrets.token_urlsafe(18)+'9a')" >> .env
   ```

   Kong ⇔ Liberty の共有シークレット、トークンの署名鍵と 4 者のクライアントシークレット（auth にはハッシュだけを渡す）、
   mTLS の証明書（社内 CA の秘密鍵は捨てる）、行員トークンの署名鍵、CSRF の鍵、Redis のパスワードが作られます。

2. 既存の DB に追加の表とトリガーを適用し（新規作成の DB では自動）、サービスアカウントを有効化する

   ```bash
   MSYS_NO_PATHCONV=1 docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/12_integration.sql"
   MSYS_NO_PATHCONV=1 docker compose exec oracle bash -c "NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus -s / as sysdba @/container-entrypoint-initdb.d/13_integration_hardening.sql </dev/null"
   MSYS_NO_PATHCONV=1 docker compose exec oracle bash /container-entrypoint-initdb.d/14_audit_protection.sh
   python integration/scripts/set_service_password.py
   ```

3. 起動（Windows でビルドに失敗するときは先頭に `COMPOSE_BAKE=false DOCKER_CONTEXT=desktop-linux`）

   ```bash
   docker compose up -d --build
   docker compose --profile observability up -d     # 監視（Grafana http://localhost:3001・Jaeger http://localhost:16686）
   ```

   トレースを送るときは `.env` に `KONG_OTEL_ENDPOINT=http://otel-collector:4318/v1/traces`・`KONG_TRACING_INSTRUMENTATIONS=all`・
   `MULE_OTEL_ENDPOINT=http://otel-collector:4318` を加えて kong・mule を作り直します。

## 連携先からの呼び出し（例）

```bash
G=http://127.0.0.1:8088
F=integration/mulesoft/bank-integration/src/test/resources/fixtures
# 1. アクセストークン（5 分有効）。クライアントシークレットは .env の OAUTH_SECRET_*
T=$(curl -s -u "zengin-network:$OAUTH_SECRET_ZENGIN" -d grant_type=client_credentials $G/oauth2/token | jq -r .access_token)
# 2. 全銀（被仕向）。Shift_JIS のファイルは charset を付ける。Idempotency-Key を付けると同じキーの再送は前回の応答を返す
curl -X POST $G/integration/v1/zengin/incoming -H "Authorization: Bearer $T" -H "Idempotency-Key: zengin-20261124-001" \
  -H "Content-Type: text/plain; charset=Shift_JIS" --data-binary @$F/zengin-incoming-sample.sjis.txt
# 疎通確認
curl $G/integration/v1/health -H "Authorization: Bearer $T"
```

収納企業（`billing-agency`・`/integration/v1/billing/import`）と相場提供元（`fx-provider`・`/integration/v1/fx/rates`）も同じ手順です。

役席は画面の ADMIN →「外部連携」から同じデータを送れます。送信は**二者承認**です：作成した役席とは別の役席が「承認して送信」
したときだけ、Next.js のサーバー側が bank-ops のトークンと、作成者・承認者を署名した行員トークンを付けて Kong に送ります。

## テスト

```bash
cd integration/mule-compat && npm test          # 変換・検証・冪等性・再試行・行員トークン・mTLS（MUnit と同じ fixtures）
cd integration/auth-server && npm test          # トークン発行（署名・クレーム・クライアント認証・総当たり対策）
cd integration/mulesoft/bank-integration && mvn test   # MUnit（Anypoint のリポジトリへの接続と Mule ランタイムが必要）
```

Kong の構成の検証（`kong config parse`・decK の規約 `kong/deck-rules.yaml`）は GitHub Actions（`.github/workflows/ci.yml`）で行います。

## 本物の Mule ランタイムに切り替える

`docker-compose.yml` の `mule` サービスを、`mulesoft/bank-integration` をデプロイした Mule ランタイム（Runtime Fabric・
オンプレミスの Mule 4.6 以降など）に置き換えます。サービス名 `mule`・ポート 8443（mTLS）・パス `/api/v1/*` が同じなら Kong の設定は変わりません。
`core.password`・`operator.secret`・鍵ストアのパスワードは Secure Properties（暗号化した `config-<env>.secure.yaml`）で渡します。
鍵ストアは `MULE_TLS_CERT`・`MULE_TLS_KEY`・`MTLS_CA_CERT` から PKCS#12 を作ります（[運用手順](docs/operations-runbook.md)）。
同梱の Mule アプリは、このリポジトリでは Mule ランタイムでのビルド・実行を確認していません（XML の整形式・OpenAPI の lint のみ確認）。
導入時に Anypoint Studio でのビルド・MUnit の実行と、コネクタのバージョン調整を行ってください。
