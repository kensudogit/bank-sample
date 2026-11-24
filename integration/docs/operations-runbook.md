# 運用手順（外部連携）

## 役割

| 担当 | 範囲 |
|---|---|
| 標準化・運用 | API 一覧と経路（api-routing-matrix.md）、OpenAPI（bank-integration.yaml）、連携先の受付（クライアントの登録・ロール・ACL）、流量の見直し、二者承認の運用 |
| MuleSoft | Mule アプリ（フロー・DataWeave・MUnit）、ランタイムと配備、勘定系の資格情報、連携の障害対応 |
| Kong | 宣言的設定（kong.yml.tmpl）、プラグイン（bank-token-claims を含む）、トークンの公開鍵・mTLS の証明書・共有シークレット、Redis、Liberty の負荷分散とヘルスチェック、Kong の障害対応 |

## 監視

- Grafana（`docker compose --profile observability up -d` → http://localhost:3001）：ダッシュボード「bank-sample 外部連携」。
  Prometheus の警告（`observability/alerts.yml`）：Liberty の正常な台が 0・連携の失敗の増加・勘定系への再試行の増加・トークン発行の失敗・Kong の 5xx
- トレース（Jaeger http://localhost:16686）：Kong → MuleSoft → Kong → Liberty を `traceparent` でつないで表示（`X-Request-ID` でも検索できる）
- Kong：`kong:8100/status`・`/metrics`、MuleSoft：`mule:8081/metrics`・`/live`、auth：`auth:8080/metrics`
- 実行履歴：連携コンソール、または勘定系の `integration_run`（状態 PROCESSING／COMPLETED／FAILED・回数）
- 勘定系：監査ログ（利用者 `svc-mule`・`INTEGRATION_RUN`・二者承認の操作）、被仕向の保留（payments 画面の「全銀・被仕向」）、
  夜間バッチの `AUDIT_ANCHOR`（日次の先頭をログに `audit-anchor` として出力）・`AUDIT_RETENTION`（保存期間を過ぎた行の削除）

## 連携先の追加（オンボーディング）

1. 経路と ACL グループを決める（api-routing-matrix.md に追記して承認）
2. auth の `AUTH_CLIENTS` にクライアント（`client_id:シークレットの SHA-256:ロール+ロール`）を追加する（Entra ID ではアプリ登録とロールの割り当て）
3. `kong.yml.tmpl` に consumer（`username`・`jwt_secrets` の `key`＝client_id・`acls`）を追加し、`deck-rules.yaml` の規約を通す（CI）
4. `docker compose up -d auth kong`（DB-less のため設定ファイルの変更で反映）
5. クライアントシークレットを安全な経路で渡し、`/oauth2/token` → `/integration/v1/health` で疎通を確認

## よくある障害

| 症状 | 確認 | 対処 |
|---|---|---|
| 連携先が 401 | `Authorization: Bearer`・トークンの期限（5 分）・`azp` が consumer の key と一致するか | トークンの取り直し。シークレットの誤りはトークン発行で 401（5 回で 1 分停止） |
| 連携先が 403 | トークンの `iss`・`aud`・`roles`（Kong のログ `token rejected`）、ACL | ロールの付与（AUTH_CLIENTS／Entra のアプリロール）、consumer のグループ |
| 連携先が 409 | 同じ Idempotency-Key の送信が処理中 | 30 秒後に同じキーで再送（完了していれば前回の応答が返る） |
| 連携コンソールの送信が 403 | 画面の再読み込み（CSRF トークン）・`PUBLIC_ORIGIN` と画面の URL の一致 | `PUBLIC_ORIGIN` を画面の URL（スキーム・ポートまで）に合わせて frontend を再起動 |
| 承認できない（422） | 作成者本人ではないか | 別の役席が承認する（職務分離） |
| 送信失敗（FAILED） | MuleSoft のログ `integration-run`・勘定系の状態 | 復旧後に連携コンソールで「再送」（同じ依頼として処理され、二重には取り込まれない） |
| Liberty が 403（ゲートウェイ経由でない） | `BANK_GATEWAY_SECRET` と Kong の `KONG_GATEWAY_SECRET` が同じか | 値をそろえて kong・liberty を再起動 |
| Liberty が 503（業務 API がすべて） | `BANK_GATEWAY_SECRET` の未設定・32 文字未満 | 設定して再起動 |
| 連携先が 429 | `RateLimit-*` 応答ヘッダー | 送信間隔の調整、制限値の見直し（承認後） |
| 422（取り込まれない） | 応答の `errors`（行番号付き） | 連携先にファイルの修正・再送を依頼（照会番号で重複は防がれる） |
| 502（勘定系に接続できない） | `/integration/v1/health` の `core`、`svc-mule` の有効化・ロック、`mule_core_calls_total{result="retry"}` | `set_service_password.py` の再実行、Liberty の状態確認。実行は FAILED になるので、連携先には同じキーで再送を依頼 |
| 画面が 502 | Kong のコンテナ・`liberty.upstream` の状態（/metrics） | `docker compose restart kong`（Liberty は Kong 経由でしか呼べない） |
| 流量制限が効かない | Redis の状態（Kong のログ） | Redis を復旧する（停止中は fault_tolerant で通信を通す） |

## 秘密情報のローテーション

| 対象 | 手順 |
|---|---|
| クライアントシークレット | 新しいシークレットの SHA-256 で `AUTH_CLIENTS` を更新して auth を再起動（旧シークレットは即無効。発行済みのトークンは最長 5 分有効）。bank-ops は frontend の `INTEGRATION_CLIENT_SECRET` も更新 |
| トークンの署名鍵 | `gen-secrets.sh` で鍵を作り、`AUTH_SIGNING_KEY`（auth）と `KONG_TOKEN_PUBLIC_KEY`（Kong）を同時に更新 |
| mTLS の証明書（825 日） | `gen-secrets.sh` の `MTLS_CA_CERT`・`MULE_TLS_*`・`KONG_MTLS_CLIENT_*` をまとめて更新し、mule → kong の順に再起動 |
| 共有シークレット | `BANK_GATEWAY_SECRET` を変え、liberty と kong を同時に再起動（ずれている間は 403） |
| 行員トークン・CSRF の鍵 | `OPERATOR_TOKEN_SECRET`（frontend・mule）、`CONSOLE_CSRF_SECRET`（frontend）。CSRF の鍵を変えると画面の再読み込みが必要 |

Railway では `.env.railway` を更新して `bash deploy/railway.sh secrets` の後に再デプロイする。

## Mule ランタイム用の鍵ストア（PKCS#12）

```bash
set -a; . ./.env; set +a
echo "$MULE_TLS_CERT" | base64 -d > mule.crt; echo "$MULE_TLS_KEY" | base64 -d > mule.key; echo "$MTLS_CA_CERT" | base64 -d > ca.crt
openssl pkcs12 -export -in mule.crt -inkey mule.key -out mule-server.p12 -passout env:KEYSTORE_PASSWORD
keytool -importcert -noprompt -alias internal-ca -file ca.crt -keystore internal-ca.p12 -storetype pkcs12 -storepass "$TRUSTSTORE_PASSWORD"
rm mule.key
```
