# 外部連携（Kong × MuleSoft）

永劫銀行（bank-sample）の API ゲートウェイと外部連携の層です。デモ用サンプル（架空データ）。本番利用不可。

```
ブラウザ ─ lb:3000 ─ frontend ×2 ─(Host: kong)─ Kong :8000 /bank/api/* ──────────────┐ ヘルスチェック付き負荷分散
                                                                                    ├─ liberty ×2 ─ Oracle
他行（全銀）・収納企業・相場提供元                                                    │
  └─ 127.0.0.1:8088 Kong /integration/v1/*（API キー＋ACL）─ mule:8081 /api/v1/* ─ lb:9080 ┘
                                          MuleSoft（検証・変換・分割送信・実行履歴）
```

| 層 | 役割 | 実体 |
|---|---|---|
| Kong Gateway（OSS 3.9・DB-less） | 入口の統制：経路・API キー認証・連携先ごとの ACL・流量制限・サイズ制限・要求 ID・Prometheus 指標・Liberty 2 台のヘルスチェック | `kong/kong.yml.tmpl`（起動時に `.env` の鍵を埋め込む） |
| MuleSoft（Process API） | 外部形式 → 勘定系形式の変換：全銀 120 桁固定長・CSV・相場 JSON の検証、DataWeave 変換、100 件ずつの送信、RFC 9457 のエラー、実行履歴 | `mulesoft/bank-integration`（Mule 4 アプリ一式） |
| MuleSoft 互換コンテナ | Mule ランタイムが無いローカル環境で、同じ API 契約（`bank-integration.yaml`）を提供する | `mule-compat`（Node.js 22・依存なし） |
| 勘定系 | 業務ルール・二重入金の防止（照会番号）・監査ログ | Liberty（ロール `INTEGRATION` のサービスアカウント `svc-mule`） |

配置の判断は [docs/ADR-001-gateway-and-integration.md](docs/ADR-001-gateway-and-integration.md)、API ごとの経路は
[docs/api-routing-matrix.md](docs/api-routing-matrix.md)、認証・認可は [docs/security-design.md](docs/security-design.md)、
運用は [docs/operations-runbook.md](docs/operations-runbook.md)、本番化の条件は [docs/acceptance-checklist.md](docs/acceptance-checklist.md)。

## 初回のセットアップ

1. `.env` に鍵とサービスアカウントのパスワードを追加する（値は例。必ず自分で生成する）

   ```bash
   python -c "import secrets;[print(f'{k}={secrets.token_urlsafe(32)}') for k in ['KONG_KEY_ZENGIN','KONG_KEY_BILLING','KONG_KEY_FX','KONG_KEY_OPS']];print('MULE_CORE_PASSWORD=Mule-'+secrets.token_urlsafe(18)+'9a')" >> .env
   ```

2. 既存の DB にサービスアカウントを追加し（新規作成の DB では自動）、パスワードを設定して有効化する

   ```bash
   MSYS_NO_PATHCONV=1 docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/12_integration.sql"
   python integration/scripts/set_service_password.py
   ```

   `svc-mule` は既定で無効です。このスクリプトを実行しない限りログインできません（`--disable` で無効に戻す）。

3. 起動（Windows でビルドに失敗するときは先頭に `COMPOSE_BAKE=false DOCKER_CONTEXT=desktop-linux`）

   ```bash
   docker compose up -d --build
   ```

## 連携先からの呼び出し（例）

```bash
K=http://127.0.0.1:8088/integration/v1
F=integration/mulesoft/bank-integration/src/test/resources/fixtures
# 全銀（被仕向）。Shift_JIS のファイルは charset を付ける
curl -X POST $K/zengin/incoming -H "apikey: $KONG_KEY_ZENGIN" -H "Content-Type: text/plain; charset=Shift_JIS" --data-binary @$F/zengin-incoming-sample.sjis.txt
# 口座振替の請求データ
curl -X POST $K/billing/import -H "apikey: $KONG_KEY_BILLING" -H "Content-Type: text/csv" --data-binary @$F/billing-sample.csv
# 為替相場
curl -X POST $K/fx/rates -H "apikey: $KONG_KEY_FX" -H "Content-Type: application/json" --data-binary @$F/fx-rates-sample.json
# 疎通確認
curl $K/health -H "apikey: $KONG_KEY_FX"
```

役席は画面の ADMIN →「外部連携」から、同じデータを送信して結果と実行履歴を確認できます（Kong の鍵はサーバー側だけで使い、ブラウザには渡しません）。

## テスト

```bash
cd integration/mule-compat && npm test          # 変換・検証・HTTP の振る舞い（MUnit と同じ fixtures）
cd integration/mulesoft/bank-integration && mvn test   # MUnit（Anypoint のリポジトリへの接続と Mule ランタイムが必要）
```

## 本物の Mule ランタイムに切り替える

`docker-compose.yml` の `mule` サービスを、`mulesoft/bank-integration` をデプロイした Mule ランタイム（Runtime Fabric・
オンプレミスの Mule 4.6 以降など）に置き換えます。サービス名 `mule`・ポート 8081・パス `/api/v1/*` が同じなら Kong の設定は変わりません。
`core.password` は Secure Properties（暗号化した `config-<env>.secure.yaml`）か `-Dcore.password` で渡します。
同梱の Mule アプリは、このリポジトリでは Mule ランタイムでのビルド・実行を確認していません（XML の整形式のみ確認）。
導入時に Anypoint Studio でのビルド・MUnit の実行と、コネクタのバージョン調整を行ってください。
