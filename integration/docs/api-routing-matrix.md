# API 一覧と経路

| API ID | 入口（Kong） | 転送先 | 利用者（Kong consumer / ACL） | 認証（デモ） | 認証（本番の想定） | 制限 | データ区分 |
|---|---|---|---|---|---|---|---|
| CORE-LOGIN | `POST /bank/api/login`（Host: kong） | liberty.upstream | 画面（frontend） | 勘定系のログイン | 同左＋WAF | 20 回/分/IP | 認証情報 |
| CORE-API | `/bank/api/*`（Host: kong） | liberty.upstream | 画面（frontend） | 勘定系のセッション Cookie | 同左 | 1,200 回/分/IP・1 MB | 機密（顧客・口座） |
| INT-ZENGIN | `POST /integration/v1/zengin/incoming` | mule `/api/v1/zengin/incoming` | zengin-network, bank-ops | API キー | mTLS＋OAuth2 クライアント認証 | 30 回/分・5 MB | 機密（口座・氏名・金額） |
| INT-BILLING | `POST /integration/v1/billing/import` | mule `/api/v1/billing/import` | billing-agency, bank-ops | API キー | 同上 | 30 回/分・5 MB | 機密（お客さま番号・金額） |
| INT-FX | `POST /integration/v1/fx/rates` | mule `/api/v1/fx/rates` | fx-provider, bank-ops | API キー | 同上 | 60 回/分・64 KB | 公開相当（相場） |
| INT-HEALTH | `GET /integration/v1/health` | mule `/api/v1/health` | 全連携先 | API キー | 同上 | — | 内部 |
| INT-RUNS | `GET /integration/v1/runs` | mule `/api/v1/runs` | bank-ops | API キー | 社内のみ | — | 内部（件数・結果のみ） |

## MuleSoft → 勘定系（Kong を経由しない）

| Mule の処理 | 勘定系 API | 勘定系のロール | 1 回の上限 |
|---|---|---|---|
| 全銀 被仕向 | `POST /bank/api/remit-incoming` | SUPERVISOR, INTEGRATION | 100 件（Mule が分割） |
| 請求データ | `POST /bank/api/billing-import` | TELLER, SUPERVISOR, INTEGRATION | 1,000 行 |
| 為替相場 | `POST /bank/api/fx-rate-update` | SUPERVISOR, INTEGRATION | 1 通貨 |

`INTEGRATION` は上の 3 つ以外（`AUTHENTICATED` の API を含む）を呼べない（`ApiGuardInterceptor`）。
