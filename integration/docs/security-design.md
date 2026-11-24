# セキュリティ設計（外部連携）

## 信頼境界

| # | 境界 | 仕組み |
|---|---|---|
| 1 | インターネット／連携先 → Kong | 唯一の外部入口（ローカル 127.0.0.1:8088、Railway は TLS 終端の後ろ）。OAuth 2.0 のアクセストークン（Bearer の JWT）を jwt プラグインで検証（RS256 の署名・`exp`・`nbf`・最長 1 時間）。独自プラグイン `bank-token-claims` が `iss`・`aud`・`roles`（API ごとに必要なロール）を検証し、ACL で利用者ごとに呼べる API を限定。トークンは `Authorization` ヘッダーだけから読む（URL・Cookie からは読まない） |
| 2 | 連携先 → トークン発行 | `/oauth2/token` は Kong 経由のみ（IP ごとに 1 分 30 回）。クライアント認証は client_secret_basic／post。auth はシークレットの SHA-256 だけを持ち、5 回失敗した client_id は 1 分止める |
| 3 | Kong → MuleSoft | **mTLS**：MuleSoft は社内 CA が発行した Kong のクライアント証明書（CN=kong-gateway）だけを受け付け、Kong は MuleSoft のサーバー証明書を社内 CA で検証する。MuleSoft の業務 API はホストにも公開ドメインにも出さない。平文のポート（8081）は `/live`・`/metrics` だけ |
| 4 | MuleSoft → 勘定系 | Kong の社内ルート（Host: kong）を通る。サービスアカウント `svc-mule`（ロール `INTEGRATION`）は 3 つの取込 API と実行履歴の API だけを呼べる |
| 5 | Kong → 勘定系 | **ゲートウェイ迂回の防止**：Kong が送信元の `X-Bank-Gateway-Secret` を消してから共有シークレットを付け直し、Liberty の `GatewayGuardFilter` が一定時間比較で確認する（未設定なら業務 API は 503）。Liberty・MuleSoft はホストにポートを公開せず、docker のネットワークも層ごとに分ける（edge／integration／core／cache） |
| 6 | 画面 → 連携コンソール | Next.js のサーバー側 API が、ブラウザの Cookie で勘定系の `/me` を呼んで役席を確認する。更新は `X-Requested-With`・Origin の完全一致（`PUBLIC_ORIGIN`）・セッションに結び付いた CSRF トークン（`X-CSRF-Token`）の 3 つを確認する。OAuth のシークレット・トークンはブラウザに渡らない |

## 行員 ID の信頼性

- 連携コンソールからの送信だけが行員（作成者・承認者）を伴う。行員 ID は平文のヘッダーではなく、Next.js のサーバー側が
  `OPERATOR_TOKEN_SECRET` で署名した **行員トークン**（HS256・2 分有効・`maker`・`approver`・`approvalId`・本文の `bodySha256`）で渡す。
- MuleSoft は、Kong が認証した利用者が `bank-ops` のときだけ行員トークンを求め、署名・期限・宛先・本文のハッシュ・作成者≠承認者を確かめる。
  ほかの利用者の行員ヘッダーは無視し、Kong も平文の `X-Bank-Operator` を取り除く。
- `X-Consumer-Username` は Kong が認証の結果として上書きするヘッダーで、mTLS により Kong 以外は MuleSoft に接続できない。

## 職務分離（二者承認）

- 手動送信は「作成（承認待ち）→ 別の役席が承認」したときだけ送る。作成者本人の承認・却下は勘定系が拒否し（422）、
  DB の CHECK 制約（`approver <> maker`）でも保証する。
- 承認時に送る内容は作成時の本文（SHA-256 一致）だけ。承認と送信は依頼 ID を冪等キーにし、送信の失敗（5xx）は同じ依頼として再送できる。
- 実行履歴と監査ログに作成者・承認者が残る。

## 二重処理の防止

| 層 | 仕組み |
|---|---|
| ファイル単位 | `integration_run` の一意制約（利用者・種類・冪等キー）。冪等キーは `Idempotency-Key`（なければ本文の SHA-256）。完了済みの再送は保存した応答を返し（`Idempotent-Replayed: true`）、処理中は 409、同じキーで内容が違えば 422 |
| 明細単位 | 被仕向は `incoming_reference`（仕向銀行・照会番号の主キー）を入金と同じトランザクションで登録。同時に届いても入金は 1 回（ほかは DUPLICATE）。請求は `uq_dd_request`（契約・引落日）。同時取込の一意制約違反は全体をやり直して「重複」と判定する |
| 再試行 | MuleSoft は勘定系の障害（接続失敗・5xx）だけを指数バックオフで再試行する（4xx はしない）。呼ぶ API はすべて冪等 |

## 監査ログの保護

- 各行はハッシュチェーン（前の行のハッシュ＋内容）。チェーン先頭は別表で管理し、末尾の削除も検出する。
- Oracle のトリガー（`14_audit_protection.sh`）：更新は常に禁止、削除は保存期間（既定 10 年。延長のみ可）を過ぎた行だけ、
  チェーン先頭は進めることしかできない、アンカーは追記のみ、アプリの DB 利用者による表の DROP・TRUNCATE・ALTER は禁止。
- 毎日のアンカー（その日の先頭の ID とハッシュ）を DB とアプリケーションログ（DB とは別の保存先）の両方に記録し、
  チェーンを丸ごと作り直す改ざんも検出する。保存期間を過ぎた行の削除後は、削除範囲の最後の行を起点にして検証を続ける。

## 秘密情報

- すべて `.env`／`.env.railway`（Git 管理外）。`integration/scripts/gen-secrets.sh` で作る。Kong の設定ファイル・Terraform の state には載せない。
- 値が未設定・短すぎる場合は起動しない（Kong の `render-config.sh`・auth）か、業務 API を止める（Liberty の 503・MuleSoft の行員トークン拒否）。
- 実行履歴・ログには口座番号・氏名・金額の明細を残さない（件数と結果のみ）。トレースからは Authorization・Cookie・クエリを消す（OTel Collector）。

## Entra ID への切り替え

auth の代わりに Microsoft Entra ID がトークンを発行する。アプリ登録（API と 4 つのクライアント）とアプリロール
（`Zengin.Submit`・`Billing.Submit`・`Fx.Submit`・`Ops.Submit`・`Ops.Read`・`Integration.Health`）を作り、API のマニフェストで
`accessTokenAcceptedVersion: 2` にする。Kong に次を設定する（テナントの値はリポジトリに書かない）。

| 変数 | 値 |
|---|---|
| `AUTH_MODE` | `entra` |
| `ENTRA_TENANT_ID` | テナント ID（`iss` は `https://login.microsoftonline.com/<tenant>/v2.0`） |
| `KONG_TOKEN_AUDIENCE` | API の Application ID URI（またはクライアント ID） |
| `KONG_CLIENT_ID_ZENGIN`・`_BILLING`・`_FX`・`_OPS` | 各クライアントのアプリケーション ID（トークンの `azp`） |
| `KONG_TOKEN_PUBLIC_KEY` | Entra の署名鍵（JWKS の `x5c` から作った公開鍵 PEM を Base64） |

連携コンソールは `INTEGRATION_TOKEN_URL=https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` と bank-ops のクライアント ID・シークレットを使う
（`INTEGRATION_TOKEN_SCOPE=<Application ID URI>/.default` も設定する）。
OSS の jwt プラグインは鍵を 1 つしか持てず、Entra の鍵の更新（ローテーション）に自動では追従しない。本番は Kong Enterprise／Konnect の
OpenID Connect プラグイン（JWKS の自動取得）を使うか、鍵の更新を CI（decK）で反映する。

## デモの制約と本番で必要なもの

| 項目 | デモ（このリポジトリ） | 本番 |
|---|---|---|
| トークン発行 | 自前の auth（RS256） | Entra ID などの IdP。Kong Enterprise の OIDC（JWKS 自動更新・トークンの失効確認） |
| 鍵・証明書 | `.env`（`gen-secrets.sh`）。証明書は 825 日 | Vault・Konnect のシークレット管理、証明書の自動更新、定期ローテーション |
| Kong ↔ Liberty | 共有シークレット＋ネットワーク分離 | mTLS（Liberty の `transportSecurity`）または同一 Pod のサイドカー |
| 監査ログ | DB のトリガー＋ハッシュチェーン＋日次アンカー（ログ） | WORM ストレージ（S3 Object Lock など）への定期転送、DBA 権限の分離 |
| MuleSoft | 互換コンテナ | Mule 4 ランタイム（ライセンス）。API Manager のポリシー、Anypoint Monitoring |
