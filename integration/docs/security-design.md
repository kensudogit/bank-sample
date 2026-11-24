# セキュリティ設計（外部連携）

## 信頼境界

1. **インターネット／連携先 → Kong**：唯一の外部入口（127.0.0.1:8088。本番は TLS 終端の後ろ）。API キー（`apikey` ヘッダー）で
   利用者を特定し、ACL で呼べる API を限定。鍵は上流へ転送しない（`hide_credentials`）。
2. **Kong → MuleSoft**：社内ネットワークのみ（`mule` はホストにポートを公開しない）。Kong が付ける `X-Consumer-Username` で
   連携先を識別し、実行履歴に残す。`X-Bank-Operator`（行員名）は利用者が `bank-ops` の場合だけ信用する。
3. **MuleSoft → 勘定系**：サービスアカウント `svc-mule`（ロール `INTEGRATION`）でログイン。呼べるのは 3 つの API だけで、
   口座一覧などの参照 API（`AUTHENTICATED`）も拒否される。勘定系の監査ログには `svc-mule` の操作として残る。
4. **画面 → 連携コンソール**：Next.js のサーバー側 API が、ブラウザの Cookie で勘定系の `/me` を呼んで役席を確認してから、
   `bank-ops` の鍵で Kong を呼ぶ。鍵はブラウザに渡らない。送信は `X-Requested-With` と同一オリジンを確認（CSRF 対策）。

## 二重処理の防止

- 全銀データの照会番号 `ZG-<仕向銀行>-<取組日>-<顧客コード1>` を勘定系が `incoming_reference`（主キー）で管理し、同じデータの
  再送・並行送信でも二重に入金しない（再送は DUPLICATE として返る）。
- 検証（件数・合計・桁数）が 1 件でも NG のファイルは 1 件も取り込まない（422）。
- 勘定系がセッション切れ（401）を返した場合だけ 1 回再ログインして再送する（401 は処理前の拒否なので二重実行にならない）。
  それ以外の失敗（タイムアウトなど）は自動で再送せず、連携先に再送を任せる（照会番号で重複判定される）。

## 秘密情報

- Kong の鍵（`KONG_KEY_*`）と `MULE_CORE_PASSWORD` は `.env`（Git 管理外）。Kong の設定ファイルには埋め込まず、起動時に生成する。
  鍵が 32 文字未満・未設定なら Kong は起動しない。
- `svc-mule` は既定で無効（照合できないハッシュ）。`set_service_password.py` がハッシュだけを SQL*Plus の標準入力で渡す。
- 実行履歴・ログには口座番号・氏名・金額の明細を残さない（件数と結果のみ）。

## デモの制約と本番で必要なもの（製品のエディション）

| 項目 | デモ（このリポジトリ） | 本番 |
|---|---|---|
| 連携先の認証 | API キー（Kong OSS key-auth） | mTLS＋OAuth2 クライアント認証（Kong Enterprise/Konnect の OIDC・mTLS プラグイン、または前段の TLS 終端） |
| 鍵の管理 | `.env` | Vault・Konnect のシークレット管理、定期ローテーション |
| 流量制限 | ノード内（`policy: local`） | 複数ノードで共有（Redis。Kong Enterprise は高度なレート制限） |
| MuleSoft | 互換コンテナ | Mule 4 ランタイム（ライセンス）。API Manager で JWT/mTLS ポリシー、Anypoint Monitoring |
| 勘定系の資格情報 | サービスアカウント＋パスワード | 証明書・トークン交換（Mule 側で独立に認可） |
| 通信 | HTTP（社内 Docker ネットワーク） | 全区間 TLS、Kong ↔ MuleSoft は mTLS |

## 確認済みの否定テスト（2026-11-24・ローカル）

- 鍵なし → 401／他の連携先の鍵 → 403（ACL）／外向けポートから `/bank/api/*` → 404（Host 限定）
- 検証 NG のファイル → 422（problem+json、勘定系を呼ばない）／同じファイルの再送 → 全件 DUPLICATE（残高不変）
- `svc-mule` で `/accounts`・`/customers`・`/account`・`/remit-resolve` → 403
- 役席以外の連携コンソール → 403／`X-Requested-With` なし・別オリジン → 403（フロントエンドのテスト）
