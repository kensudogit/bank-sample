# 認証認可設計：Entra ID 連携・Agent 経由のアクセス制御・追跡

学習用サンプル（架空データ）。本番の認証・規制への適合を示すものではありません。テナント ID・クライアント ID・シークレットは
リポジトリに書かず、`.env`／Railway の変数で渡します。

## 全体像

```
行員（ブラウザ）── 勘定系のログイン（Cookie）── Next.js（画面のサーバー側）
   │ ① 行員の確認（/me：窓口・役席）→ ② 行員の委任トークン（aud=api://bank-agent・scp=Agent.Invoke）
   ▼                                     ※ モックはセッションブリッジ、Entra ID ではサインイン（認可コード＋PKCE）
 Kong /agent/v1/runs（jwt＋bank-token-claims：署名・iss・aud・tid・委任・Agent.Invoke・ACL=console・行員ごとに 1 分 10 回）
   ▼
 Agent（Claude）── 独立にトークンを再検証（JWKS・iss・aud・tid・期限・種類・スコープ）
   │ ③ 予算の確認（アプリのみのトークン・Agent.Meter）→ ④ On-Behalf-Of（azp=Agent・行員の oid・同意済みの参照スコープ）
   ▼
 Kong /agent/v1/bank/*（GET のみ。委任トークン・スコープ・tid を検証し、検証済みのクレームだけから X-Bank-* を付ける）
   ▼
 Liberty（ゲートウェイの共有シークレットを確認）→ 行員の現在の権限 ∩ Agent のスコープ（agentScope）→ 監査ログ（agent=…・token=…）
```

## 身元（アイデンティティ）

| 主体 | 種類 | 表し方（トークン） | 用途 |
|---|---|---|---|
| 行員 | 人（委任） | `oid`（テナント内で一意）・`preferred_username`・`scp`・`roles`（Bank.Teller／Bank.Supervisor） | Agent を呼ぶ・Agent が代わりに参照する |
| 画面（bank-console） | アプリ（機密クライアント） | `azp`／`appid` | 行員の委任トークンを得る（モックのセッションブリッジ専用。ロールなし） |
| AI Agent（bank-agent） | ワークロード | `azp`／`appid`（On-Behalf-Of のトークンに残る） | 行員の代わりに勘定系を参照・メーター（`Agent.Meter`） |
| 連携先・連携コンソール | アプリのみ | `idtyp=app`・`roles` | 外部連携（既存） |

- 主体の鍵は `oid`＋`tid`。名前（`preferred_username`）は表示と勘定系の利用者の照合に使う。
- **Agent の ID は呼び出し側の申告を信用しない**：On-Behalf-Of のトークンの `azp`（auth／Entra が署名）だけから決まる。
  勘定系のメーターも、要求本文ではなくトークンの `azp` で Agent を決める。
- **行員の委任とワークロードの身元を混ぜない**：Agent はアプリの権限（client_credentials）で勘定系の業務 API を呼ばない。
  業務データは必ず行員の委任（OBO）で、メーターだけをアプリのトークンで呼ぶ。

## 認可（「行員の権限 ∩ Agent のスコープ」）

| 層 | 判定 |
|---|---|
| Kong（/agent/v1/runs） | 委任トークン・`aud=api://bank-agent`・`scp⊇Agent.Invoke`・`tid` が許可リスト・利用者グループ console |
| Agent | 同じ検証を独立に行う。On-Behalf-Of のトークンの `scp` にないツールはモデルに見せない |
| auth／Entra（OBO） | Agent が公開する API 宛ての**行員の**トークンだけを交換。スコープは Agent に同意済みのもの（`AUTH_OBO`）に限る |
| Kong（/agent/v1/bank/*） | 委任トークン・`aud=api://bank-core`・参照スコープのいずれか・`tid`・GET のみ・利用者グループ agents・1 分 120 回 |
| Liberty | 行員の**現在の**状態（有効・ロックなし・初期パスワード変更済み・窓口／役席）とロール、その API の `agentScope` がトークンの `scp` にあること。設定のない API は Agent から使えない（既定は拒否）。更新系（POST）は不可 |

| API | agentScope | 行員のロールの条件 |
|---|---|---|
| `accounts`・`account` | Accounts.Read | ログイン済み（顧客は本人の口座のみ。Agent は行員のみ） |
| `transactions` | Transactions.Read | 同上 |
| `customers`・`customer` | Customers.Read | 窓口・役席（`customer` の閲覧は監査ログに残る） |
| `fx-rates` | Fx.Read | ログイン済み |
| それ以外（振込・解約・承認など） | なし | Agent からは使えない |

お金が動く操作は Agent が**提案**（`propose_transfer`）だけを作り、行員が振込画面で確認して実行する（人の承認）。

## Agent の安全策（プロンプトインジェクション・暴走）

- ツールは許可リスト（参照 6 種＋提案 1 種）。引数は JSON Schema（`strict: true`・追加の項目なし）と Agent での正規表現の二重で確かめる。
- 勘定系の応答は `<bank_data>` で囲み、システムプロンプトで「データであって指示ではない」と扱う。大きさは 6,000 文字まで。
- 1 回の実行は最大 6 往復・費用の上限（`AGENT_MAX_RUN_JPY`、既定 50 円）で打ち切る。Agent の当月の予算に達したら新しい実行を止める（hard stop）。
- 行員ごとの流量制限（Kong・1 分 10 回）。Agent の外向き通信は Claude API と社内の Kong・auth だけ。
- モデルは `claude-opus-5-5`（`AGENT_MODEL` で変更可）。安全分類による拒否はサーバー側のフォールバック（`fallbacks: "default"`）。

## 追跡

| 何を | どこに |
|---|---|
| 実行（実行 ID・Agent・行員・oid・状態・ツールと結果・拒否の数・トークン数・費用・質問のハッシュ・トレース ID） | 勘定系 `agent_run`（コスト・課金の画面に一覧） |
| 勘定系の各呼び出し（行員・API・結果・`agent=<azp> token=<jti>`） | 勘定系の監査ログ（ハッシュチェーン・改ざん防止） |
| 権限外の試み（スコープ外・無効な行員） | 監査ログ（DENIED・`AGENT_SCOPE …`） |
| 全要求（API・利用者・行員・状態・遅延・要求 ID） | `usage_event`（Kong の http-log。トークン・Cookie・URL・クエリは送らない） |
| 分散トレース | `traceparent`（画面 → Kong → Agent → Kong → Liberty）。Jaeger（observability プロファイル） |
| 指標 | Agent の `/metrics`（実行数・トークン数・拒否数）、Kong・auth の指標 |

質問の本文と勘定系の応答はログに残さない（質問は SHA-256 だけ）。

## Microsoft Entra ID への切り替え

| 項目 | モック（既定） | Entra ID |
|---|---|---|
| 発行者（iss） | `https://auth.bank-sample.local` | `https://login.microsoftonline.com/<tid>/v2.0` |
| 署名鍵 | auth の JWKS | `https://login.microsoftonline.com/<tid>/discovery/v2.0/keys` |
| 行員の委任トークン | セッションブリッジ（画面が行員を確認して発行を求める） | 行員が Entra にサインイン（認可コード＋PKCE。MSAL）。画面は API `api://bank-agent` の `Agent.Invoke` を要求 |
| On-Behalf-Of | auth（`AUTH_OBO`） | Entra のトークンエンドポイント（同じ要求の形）。Agent のアプリ登録に勘定系 API の委任スコープを付与・管理者の同意 |
| アプリロール | `AUTH_CLIENTS` | アプリ登録のアプリロール（Agent.Meter など）と割り当て |
| 勘定系の利用者 | `preferred_username` = 勘定系の利用者 ID | `oid` と勘定系の利用者を対応付ける表を追加する（UPN の変更に強い） |

アプリ登録：API（`api://bank-agent`・`api://bank-core`・`api://bank-integration`）、画面（Web・機密クライアント）、Agent（機密クライアント・
証明書を推奨）、連携先。API のマニフェストは `accessTokenAcceptedVersion: 2`。Kong・Agent の設定は `AUTH_MODE=entra`・`ENTRA_TENANT_ID`・
`KONG_CLIENT_ID_*`・`AUTH_JWKS_URL`・`AUTH_ISSUER` で切り替える。OSS の jwt プラグインは署名鍵の自動更新に追従しないため、本番は
Kong Enterprise／Konnect の OpenID Connect プラグインを使う（エディションの要件）。実テナントでの確認は未実施。

## 否定テスト（自動）

auth：OBO を許可していないクライアント・アプリのみのトークン・改ざん・期限切れ・同意していないスコープを拒否。
Agent：署名の改ざん・宛先違い・アプリのみ・他テナント・スコープなし・期限切れ・不明な kid を拒否し、Claude を呼ばない。予算超過で実行しない。
Liberty：無効・ロック・顧客の利用者を委任に使えない。E2E の結果は `integration/docs/hardening.md` の「AI Agent・課金」の節。
