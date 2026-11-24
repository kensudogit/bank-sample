# 運用手順（外部連携）

## 役割

| 担当 | 範囲 |
|---|---|
| 標準化・運用 | API 一覧と経路（api-routing-matrix.md）、OpenAPI（bank-integration.yaml）、連携先の受付（鍵の発行・ACL）、流量の見直し |
| MuleSoft | Mule アプリ（フロー・DataWeave・MUnit）、ランタイムと配備、勘定系の資格情報、連携の障害対応 |
| Kong | 宣言的設定（kong.yml.tmpl）、プラグイン、鍵、Liberty の負荷分散とヘルスチェック、Kong の障害対応 |

## 監視

- Kong：`kong:8100/status`（稼働）、`kong:8100/metrics`（Prometheus：経路別の件数・応答コード・遅延、Liberty の各台の状態）
- MuleSoft：`GET /integration/v1/health`（勘定系への接続）、`GET /integration/v1/runs`（実行履歴）、コンテナログの `integration-run`
- 勘定系：監査ログ（利用者 `svc-mule`）、被仕向の保留（payments 画面の「全銀・被仕向」）

## 連携先の追加（オンボーディング）

1. 経路と ACL グループを決める（api-routing-matrix.md に追記して承認）
2. `kong.yml.tmpl` に consumer（`username`・`acls`・鍵の置き場所 `__KEY_*__`）を追加し、`render-config.sh` に置換を追加
3. `.env` に鍵を生成して `docker compose up -d kong`（DB-less のため設定ファイルの変更で反映）
4. 連携先に鍵を安全な経路で渡し、`/integration/v1/health` で疎通を確認

## よくある障害

| 症状 | 確認 | 対処 |
|---|---|---|
| 連携先が 401 | 鍵のヘッダー名（`apikey`）・値 | 鍵の再発行（`.env` 更新 → kong 再起動） |
| 連携先が 403 | Kong の ACL | consumer のグループと経路の allow を確認 |
| 連携先が 429 | `RateLimit-*` 応答ヘッダー | 送信間隔の調整、制限値の見直し（承認後） |
| 422（取り込まれない） | 応答の `errors`（行番号付き） | 連携先にファイルの修正・再送を依頼（照会番号で重複は防がれる） |
| 502（勘定系に接続できない） | `/integration/v1/health` の `core`、`svc-mule` の有効化・ロック | `set_service_password.py` の再実行、Liberty の状態確認。連携先には時間をおいて再送を依頼 |
| 画面が 502 | Kong のコンテナ・`liberty.upstream` の状態（/metrics） | `docker compose restart kong`。緊急時は frontend の `BANK_API_ORIGIN` を `http://lb:9080` に戻して再ビルド |

## 鍵のローテーション

1. `.env` の `KONG_KEY_<連携先>` を新しい値に変更し `docker compose up -d kong`（旧鍵は即無効）
2. 無停止で切り替える場合は、一時的に consumer に新旧 2 つの鍵（keyauth_credentials）を登録してから旧鍵を削除する
3. `KONG_KEY_OPS` を変えたら frontend も再起動（環境変数で読むため）
