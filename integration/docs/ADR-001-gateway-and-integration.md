# ADR-001 API ゲートウェイ（Kong）と連携基盤（MuleSoft）の配置

> 一部は [ADR-002](ADR-002-integration-hardening.md) で置き換えた（MuleSoft → 勘定系も Kong 経由、API キー → OAuth 2.0、実行履歴は Oracle）。

- 状態：提案（デモ環境で実装・検証済み。本番採用には利害関係者の承認が必要）
- 日付：2026-11-24

## 背景

bank-sample は画面（Next.js）→ nginx → Liberty の構成で、外部（他行・収納企業・相場提供元）とのデータ連携は画面の貼り付け
（役席の手作業）だけだった。外部から機械的に受け取るには、入口の統制（認証・流量・監査）と、外部形式（全銀の固定長・CSV・JSON）
から勘定系の形式への変換が必要になる。

## 決定

1. **Kong を API の入口にする**（画面の API と外部連携の両方）。
   - 画面の API：frontend → Kong（`Host: kong` に限定）→ Liberty ×2。ログイン 20 回/分・API 1,200 回/分（IP ごと）、1 MB 上限、
     能動・受動ヘルスチェック付きの負荷分散。
   - 外部連携：`/integration/v1/*` を API キー（key-auth）と連携先ごとの ACL で保護し、MuleSoft へ中継する。
2. **MuleSoft を外部形式の変換・検証・分割送信に使う**（Process API）。勘定系の業務ルール（入金・保留・重複判定）は勘定系に残す。
3. **MuleSoft → 勘定系は Kong を経由しない**（社内の lb:9080 を直接呼ぶ）。同じ要求に Kong を 2 回通す意味がなく、
   遅延と障害点が増えるため。代わりに勘定系側で最小権限のロール `INTEGRATION` を設けて認可する。
4. **ローカルでは MuleSoft 互換コンテナを使う**。Mule の Enterprise ランタイムはライセンス制で公開のコンテナイメージが無いため。
   Mule アプリ一式（フロー・DataWeave・OpenAPI・MUnit）は別に持ち、同じ fixtures でテストして挙動をそろえる。

## 判断基準（スキルの判定表の適用）

| API | 判定 | 理由 |
|---|---|---|
| 画面 → 勘定系 | Kong のみ | 変換・オーケストレーション不要。入口の統制と負荷分散だけ必要 |
| 全銀 被仕向 | Kong ＋ MuleSoft | 外部向けの入口統制と、固定長の検証・変換・分割送信が必要 |
| 請求データ CSV | Kong ＋ MuleSoft | 同上（CSV の正規化） |
| 為替相場 | Kong ＋ MuleSoft | 同上（提供元の形式から勘定系の形式へ、対象通貨の選別） |
| MuleSoft → 勘定系 | どちらも経由しない | 社内の信頼済み経路。勘定系のロールで認可する |

## 影響

- 画面の API が 1 段（Kong）増える（ローカル計測で数 ms）。nginx の 9080 は直接アクセスと MuleSoft 用に残す。
- Kong OSS の範囲で実装（OIDC・Vault 連携・高度なレート制限は Enterprise/Konnect が必要。`edition-matrix` は security-design.md 参照）。
- Mule の実行履歴はメモリ上（再起動で消える）。監査の正は勘定系の監査ログ（`svc-mule` の操作として残る）。

## 却下した案

- **nginx だけで外部公開**：API キー・ACL・利用者別の流量制限を自作することになり、運用標準にならない。
- **勘定系（Struts）に変換処理を追加**：固定長・CSV など外部形式の変更が勘定系のリリースに波及する。
- **MuleSoft だけで入口も担う**：画面の API まで MuleSoft を通すと不要な変換層を挟むことになる。
