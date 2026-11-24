# API 一覧と経路

| API ID | 入口（Kong） | 転送先 | 利用者（Kong consumer / ACL）・必要なロール | 認証（デモ） | 認証（本番の想定） | 制限（Redis で全台共有） | データ区分 |
|---|---|---|---|---|---|---|---|
| CORE-LOGIN | `POST /bank/api/login`（Host: kong） | liberty.upstream（共有シークレット付き） | 画面（frontend）・MuleSoft | 勘定系のログイン | 同左＋WAF | 20 回/分/IP | 認証情報 |
| CORE-UPLOAD | `POST /bank/api/integration-request-create`（Host: kong） | liberty.upstream | 連携コンソール（役席） | 勘定系のセッション Cookie | 同左 | 8 MB | 機密（送信データ） |
| CORE-API | `/bank/api/*`（Host: kong） | liberty.upstream | 画面（frontend）・MuleSoft（svc-mule） | 勘定系のセッション Cookie | 同左 | 1,200 回/分/IP・1 MB | 機密（顧客・口座） |
| OAUTH-TOKEN | `POST /oauth2/token` | auth `/oauth2/token` | 全連携先 | クライアントシークレット | Entra ID のトークンエンドポイント | 30 回/分/IP・8 KB | 認証情報 |
| INT-ZENGIN | `POST /integration/v1/zengin/incoming` | mule `/api/v1/zengin/incoming`（mTLS） | zengin-network, bank-ops・`Zengin.Submit` / `Ops.Submit` | OAuth 2.0（RS256 JWT） | Entra ID ＋ Kong Enterprise OIDC | 30 回/分・5 MB | 機密（口座・氏名・金額） |
| INT-BILLING | `POST /integration/v1/billing/import` | mule `/api/v1/billing/import`（mTLS） | billing-agency, bank-ops・`Billing.Submit` / `Ops.Submit` | 同上 | 同上 | 30 回/分・5 MB | 機密（お客さま番号・金額） |
| INT-FX | `POST /integration/v1/fx/rates` | mule `/api/v1/fx/rates`（mTLS） | fx-provider, bank-ops・`Fx.Submit` / `Ops.Submit` | 同上 | 同上 | 60 回/分・64 KB | 公開相当（相場） |
| INT-HEALTH | `GET /integration/v1/health` | mule `/api/v1/health`（mTLS） | 全連携先（ロール不問） | 同上 | 同上 | — | 内部 |
| INT-RUNS | `GET /integration/v1/runs` | mule `/api/v1/runs`（mTLS） | bank-ops・`Ops.Read` | 同上 | 社内のみ | — | 内部（件数・結果のみ） |

## MuleSoft → 勘定系（Kong の社内ルート経由。Liberty は Kong の共有シークレットがない要求を拒否する）

| Mule の処理 | 勘定系 API | 勘定系のロール | 1 回の上限 |
|---|---|---|---|
| 全銀 被仕向 | `POST /bank/api/remit-incoming` | SUPERVISOR, INTEGRATION | 100 件（Mule が分割） |
| 請求データ | `POST /bank/api/billing-import` | TELLER, SUPERVISOR, INTEGRATION | 1,000 行 |
| 為替相場 | `POST /bank/api/fx-rate-update` | SUPERVISOR, INTEGRATION | 1 通貨 |
| 実行の登録・完了・一覧 | `POST /bank/api/integration-run-start`・`-finish`、`GET /integration-runs` | INTEGRATION | — |

## 連携コンソール → 勘定系（役席のセッション）

| 操作 | 勘定系 API | 条件 |
|---|---|---|
| 依頼の作成・一覧 | `integration-request-create`・`integration-requests` | 役席 |
| 承認（送信データを受け取る）・却下 | `integration-request-approve`・`-reject` | 作成者以外の役席（DB の CHECK 制約でも保証） |
| 再送・結果の記録 | `integration-request-redispatch`・`-complete` | 役席 |

`INTEGRATION` は上の API 以外（`AUTHENTICATED` の API を含む）を呼べない（`ApiGuardInterceptor`）。
