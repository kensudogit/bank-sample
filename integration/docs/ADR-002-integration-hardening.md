# ADR-002：外部連携の強化（認証・冪等性・職務分離・迂回防止）

- 状態：採用（2026-11-24）
- 置き換え：ADR-001 の「MuleSoft → 勘定系は Kong を経由しない」「API キー」「実行履歴はメモリ」

## 背景

ADR-001 の構成では、(1) 実行履歴が MuleSoft の再起動で消える、(2) 連携コンソールの送信が役席 1 人で完結する、
(3) 社内ネットワークから Liberty・MuleSoft を直接呼べる、(4) 行員名が平文のヘッダーで、(5) 連携先の認証が API キーだった。

## 決定

1. **保存先は既存の Oracle**。実行履歴・二者承認の依頼・監査ログを勘定系と同じ DB に置き、明細の一意制約と同じトランザクションで扱える
   ようにする（PostgreSQL を新設すると、元帳と履歴が別のトランザクションになる）。MuleSoft は勘定系の API 経由で読み書きする。
2. **OAuth 2.0 クライアントクレデンシャル**。学習用に自前の発行サーバー（auth・RS256）を置き、トークンの形を Entra ID に合わせる。
   Kong OSS の jwt プラグインは iss・aud・scope を見ないため、独自プラグイン `bank-token-claims` で補う。`AUTH_MODE=entra` で切り替える。
3. **全区間を Kong 経由に**。MuleSoft → 勘定系も Kong の社内ルートを通し、Liberty は Kong の共有シークレットがない要求を拒否する。
   Kong → MuleSoft は mTLS。docker のネットワークを層ごとに分ける。
4. **二者承認**：作成と承認を別の役席にし、DB の CHECK 制約でも保証する。送信は承認した内容（ハッシュ）と行員の署名を伴う。
5. **冪等性はファイル単位と明細単位の二重**：Idempotency-Key（なければ本文のハッシュ）で実行を 1 回にし、完了済みは応答を再現する。
   明細は従来どおり業務の一意制約で守る。
6. **構成管理**：Railway は Terraform（コミュニティ provider）に移し、`.railway/railway.ts` は廃止。秘密情報は Terraform の state に
   載せず `deploy/railway.sh secrets` で設定する。Kong の構成は Kong 自身のパーサーと decK の規約で CI で検査する。

## 結果

- 画面からの送信は 2 人の操作が必要になる（デモでは supervisor1・supervisor2）。
- MuleSoft → 勘定系が Kong を 1 段多く通る（ローカルで数 ms）。Kong が止まると連携も止まる（Kong の冗長化が前提）。
- 秘密情報が増えた（`gen-secrets.sh` で一括生成）。証明書（825 日）の更新手順が必要。
- Entra ID の鍵の自動更新は OSS では扱えない（Kong Enterprise の OIDC か、CI での鍵の反映）。
