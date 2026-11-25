# 外部連携の強化（12 項目）

「Kong 経由で送信」まわりの課題として挙げた 12 項目の対応です。学習用サンプルとしての実装で、本番の認証・規制への適合を示すものではありません。
確認は 2026-11-24 にローカル（docker compose）で行いました（[確認方法](#確認方法)）。

| 優先度 | 項目 | 実装 | 主なファイル |
|---|---|---|---|
| 最優先 | 実行履歴の永続化 | MuleSoft のメモリから勘定系の Oracle（`integration_run`）へ。開始・完了を API で記録し、再起動しても消えない。画面の実行履歴もここから読む | `db/init/13_integration_hardening.sql`、`IntegrationService`、`mule-compat/src/server.mjs`、`mulesoft/.../ops.xml` |
| 最優先 | 二重入金防止 | ファイル単位：`integration_run` の一意制約（利用者・種類・冪等キー）で同じ送信を 1 回だけ処理し、完了後の再送は保存した応答を返す。明細単位：`incoming_reference` の主キーを入金と同じトランザクションで登録（同時 10 件でも入金 1 回）。請求の同時取込の一意制約違反は、やり直して「重複」と判定（以前は 500 になっていた） | `IntegrationService.startRun`、`PaymentService.receiveIncoming`・`importBilling` |
| 最優先 | 職務分離 | 手動送信は二者承認：作成（承認待ち）→ 別の役席が承認したときだけ送信。作成者本人の承認・却下はアプリ（422）と DB の CHECK 制約の両方で拒否。承認時に作成時の内容（SHA-256）と一致することを確かめる | `integration_request`、`IntegrationService.approve`、`frontend/app/api/integration/requests/**`、`frontend/app/integration/page.tsx` |
| 最優先 | Gateway 迂回防止 | Liberty：Kong が付け直す共有シークレットのない要求を 403（`GatewayGuardFilter`）。MuleSoft：mTLS で Kong のクライアント証明書だけを受け付ける。Liberty・MuleSoft はホストにポートを出さず、docker のネットワークを層ごとに分離。nginx の API 用ポート（9080）を廃止。MuleSoft → 勘定系も Kong 経由 | `GatewayGuardFilter`、`kong.yml.tmpl`（request-transformer）、`docker-compose.yml`、`lb/nginx.conf` |
| 高 | 行員 ID の信頼性 | 平文の `X-Bank-Operator` をやめ、署名付きの行員トークン（HS256・2 分・作成者・承認者・依頼 ID・本文のハッシュ）に。Kong は平文のヘッダーを消し、`X-Consumer-Username` は Kong が上書き、MuleSoft は mTLS で Kong からしか受けない | `frontend/lib/integration-server.ts`、`mule-compat/src/operator.mjs`、`dw/operator-token.dwl` |
| 高 | システム間認証 | API キーを廃止し OAuth 2.0 クライアントクレデンシャル（RS256 の JWT）。Kong の jwt プラグイン＋独自プラグイン `bank-token-claims`（iss・aud・roles）。Kong → MuleSoft は mTLS。`AUTH_MODE=entra` で Entra ID に切り替え | `integration/auth-server/`、`kong/plugins/bank-token-claims/`、`kong/render-config.sh` |
| 高 | CSRF 対策の強化 | Origin の完全一致（`PUBLIC_ORIGIN`。Origin なしは拒否）と、セッションに結び付いた CSRF トークン（`X-CSRF-Token`）を追加。直接送信の API は廃止（405） | `frontend/lib/integration-server.ts`（`checkCsrf`）、`frontend/lib/integration.ts` |
| 高 | 障害時の再処理 | 実行の状態（処理中・完了・失敗・期限）を管理し、失敗・期限切れの実行は同じキーの再送で再処理。MuleSoft は勘定系の障害だけを指数バックオフで再試行（4xx はしない）。連携コンソールは送信失敗の依頼を「再送」できる | `IntegrationService`、`mule-compat/src/core.mjs`、`core-banking-sapi.xml`（until-successful） |
| 高 | 分散流量制御 | Kong のレート制限を `policy: redis` に（全台で共有。Redis 停止時は通信を止めない `fault_tolerant`）。トークン発行にも IP ごとの制限 | `kong.yml.tmpl`、`docker-compose.yml`（redis）、Terraform（redis サービス） |
| 高 | 監査ログ保護 | Oracle のトリガー：更新禁止・保存期間内の削除禁止・チェーン先頭の巻き戻し禁止・保存期間の短縮禁止・DDL 禁止。日次アンカー（DB とログ）で作り直しも検出。保存期間（既定 10 年）を過ぎた行だけを夜間に削除し、起点から検証を続ける | `db/init/14_audit_protection.sh`、`AuditService`・`AuditRepository`、`JobRunner`（AUDIT_ANCHOR・AUDIT_RETENTION） |
| 中 | 統合監視 | Kong の OpenTelemetry と Prometheus、MuleSoft の OTLP トレース（traceparent を引き継ぐ）と `/metrics`、auth の `/metrics`。OTel Collector → Jaeger、Prometheus（警告ルール 5 件）、Grafana（ダッシュボード） | `observability/`、`mule-compat/src/telemetry.mjs`、`docker-compose.yml`（profile: observability） |
| 中 | 構成管理 | Terraform（Railway の 7 サービス・ボリューム・ドメイン・秘密でない変数。既存は import ブロックで取り込み）、GitHub Actions（全テスト・Kong の構成・decK の規約・OpenAPI・compose・Prometheus・Terraform）、decK（`deck file lint` で安全側の約束を検査） | `infra/terraform/railway/`、`.github/workflows/ci.yml`、`integration/kong/deck-rules.yaml` |

## 送信の流れ（連携コンソール）

```
役席 A：承認依頼を作成 ──▶ Next.js（CSRF：XHR・Origin 完全一致・トークン／役席の確認）──▶ 勘定系 integration-request-create（PENDING）
役席 B：承認して送信  ──▶ Next.js ──▶ 勘定系 integration-request-approve（作成者本人なら 422。APPROVED と本文）
                              │  本文の SHA-256 を確認し、行員トークン（A・B・依頼 ID・ハッシュ）を署名
                              └──▶ Kong（bank-ops の Bearer トークン：署名・iss・aud・roles・ACL・Redis の流量制限）
                                     └─(mTLS)─▶ MuleSoft：行員トークンを検証 → integration-run-start（冪等キー approval:<依頼 ID>）
                                                  → 検証・変換 → 勘定系（Kong 経由・共有シークレット）→ integration-run-finish
                              ◀── 結果 ── Next.js が integration-request-complete（SENT／5xx なら FAILED → 「再送」）
```

## 確認方法

| 対象 | コマンド | 結果（2026-11-24） |
|---|---|---|
| 勘定系 | `cd backend && gradle test`（H2。新規 `IntegrationHardeningTest`・`GatewayGuardFilterTest`） | 119 件成功 |
| 画面 | `cd frontend && npx tsc --noEmit && npm test` | 49 ファイル・368 件成功 |
| MuleSoft 互換 | `cd integration/mule-compat && npm test`（mTLS は openssl で一時証明書を作る） | 21 件成功 |
| トークン発行 | `cd integration/auth-server && npm test` | 5 件成功 |
| Kong | `kong config parse`（描画した構成）、`deck file lint`（`deck-rules.yaml`） | 成功（規約違反を入れた構成は検出） |
| OpenAPI | `redocly lint bank-integration.yaml` | valid（既存の書式の警告 9 件） |
| Terraform | `terraform validate`・`terraform plan`（Railway） | valid。plan：取込 8・追加 49・変更 4・削除 0 |
| Oracle のトリガー | 監査ログの更新・期限内の削除・保存期間の短縮を試す | すべて ORA-2090x で拒否 |
| E2E（docker compose） | トークン・認可・冪等性・迂回・ネットワーク分離・CSRF・二者承認・永続化・Redis の 33 項目 | 33 件すべて成功 |

E2E の主な確認：トークンなし 401・別の連携先のトークン 403・署名の改ざん 401・同じ冪等キーの再送は同じ応答（`Idempotent-Replayed: true`）・
同じキーで別の内容 422・Liberty への直接アクセス 403（死活確認は 200）・クライアント証明書なしの MuleSoft は TLS で拒否・frontend から Liberty／MuleSoft に届かない・
ホストの 9080 は閉じている・CSRF トークンなし／別 Origin 403・直接送信 405・作成者の承認 422・別の役席の承認で送信（実行履歴に作成者と承認者）・
二度目の承認 409・MuleSoft を再起動しても実行履歴が残る・Redis にレート制限のカウンター。

## 残る制約

- Mule 4 アプリ（`mulesoft/bank-integration`）は Mule ランタイムでのビルド・MUnit の実行を確認していない（互換コンテナで同じ契約を確認）。
- Entra ID は設定の切り替え（`AUTH_MODE=entra`）を CI で描画・構文確認しただけで、実テナントでの確認はしていない。OSS の jwt プラグインは鍵の自動更新に追従しない。
- Kong ↔ Liberty は共有シークレット（mTLS ではない）。監査ログの外部アンカーはアプリケーションログで、WORM ストレージではない。
- Terraform の state はローカル（Git 管理外）。チームで使うときは `backend.tf` でリモートの保存先を設定する。

## AI Agent・課金（追加：2026-11-25）

認証認可（Entra ID 連携・Agent 経由のアクセス制御・追跡）は [agent-auth-design.md](agent-auth-design.md)、課金の可視化とコスト予測は
[finops-cost-model.md](finops-cost-model.md)。

| 対象 | 確認 | 結果 |
|---|---|---|
| トークン発行（Entra 互換） | `npm test`（OBO・セッションブリッジ・拒否） | 10 件成功 |
| AI Agent | `npm test`（偽の Messages API で SDK の tool runner を動かす。トークンの拒否・予算・スコープ・提案・費用の上限・strict のスキーマ） | 9 件成功 |
| 勘定系 | `gradle test`（`FinOpsServiceTest`：配賦・単価・予測・予算・Railway の取込み・委任の利用者） | 成功 |
| 画面 | `npm test`（AI アシスタント・コスト・課金・サーバー側の委任トークン） | 51 ファイル・378 件成功 |
| Kong | `kong config parse`・`deck file lint`（Agent の経路は委任トークン・GET のみ、メーターはアプリのロール） | 成功 |
| E2E（docker compose） | 委任トークン・OBO・宛先違い／アプリのみ／スコープ外の拒否、GET 以外の拒否、偽の識別ヘッダーの無視、顧客の利用拒否、CSRF、Claude の実行（1 回）、予算による停止、使用量・LLM の記録、部門への配賦、監査ログの Agent の記録 | 32 件すべて成功 |

E2E の Claude の実行は 1 回 4.51 円（入力 6・出力 580・キャッシュ読み込み 3,512・書き込み 3,550 トークン）。
Railway の使用量の取込みは、Railway のアカウント／ワークスペースのトークン（`RAILWAY_API_TOKEN`）を設定するまで「未設定」と表示する
（取込みの処理は偽の API でテスト済み。CLI のトークンで使用量 API の応答の形を確認済み）。