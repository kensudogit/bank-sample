# 受入チェックリスト

## ローカルのデモ（2026-11-24 に確認）

- [x] ADR（ADR-001-gateway-and-integration.md）・API 一覧と経路・セキュリティ設計・運用手順を作成
- [x] OpenAPI（mulesoft/bank-integration/src/main/resources/api/bank-integration.yaml）
- [x] Mule 4 アプリ一式（pom・mule-artifact・フロー・DataWeave・設定・MUnit）— XML の整形式のみ確認。Mule ランタイムでのビルド・実行は未確認
- [x] 互換コンテナのテスト成功（MUnit と同じ fixtures。強化後は 21 件）
- [x] 勘定系のテスト成功（サービスアカウントの既定無効・INTEGRATION ロール）
- [x] フロントエンドのテスト成功（連携コンソール・サーバー側 API・Kong への中継）
- [x] docker compose で kong・mule・liberty ×2・frontend ×2 が healthy
- [x] Kong 経由の否定テスト（401・403・404）と、全銀 被仕向の取込（入金・保留・再送で重複・検証 NG で 422）、請求データ、為替相場
- [x] `svc-mule` の最小権限（参照 API・未許可の更新 API は 403）

## 外部連携の強化（2026-11-24 に確認。詳細は hardening.md）

- [x] 実行履歴の永続化（Oracle）・冪等キー・再処理の状態管理（勘定系のテスト・E2E で MuleSoft 再起動後も残る）
- [x] 二重入金の防止：同時 10 件の同じ被仕向で入金 1 回、同時の請求取込で 1 件（勘定系のテスト）
- [x] 二者承認：作成者本人の承認を拒否（アプリ・DB の CHECK 制約・E2E）
- [x] ゲートウェイ迂回の防止：Liberty 直接 403、MuleSoft はクライアント証明書なしで TLS 拒否、ネットワーク分離（E2E）
- [x] OAuth 2.0（RS256・iss/aud/roles・ACL）、署名付きの行員トークン、Origin 完全一致＋CSRF トークン（テスト・E2E）
- [x] Redis 共有のレート制限、監査ログのトリガー保護・保存期間・日次アンカー、OpenTelemetry・Prometheus・Grafana
- [x] GitHub Actions（全テスト・kong config parse・decK lint・OpenAPI・compose・Terraform）、Terraform（Railway）の validate・plan

## 本番化の条件（未完了）

- [ ] ADR と経路の承認（業務・セキュリティ・運用）
- [ ] Entra ID の実テナントでの確認、鍵の自動更新（Kong Enterprise の OIDC）、鍵・証明書を Vault 等で管理しローテーション
- [ ] Kong ↔ Liberty の mTLS、監査ログの WORM ストレージへの転送、Terraform の state のリモート保存
- [ ] MuleSoft のライセンス・ランタイム（CloudHub 2.0 / Runtime Fabric / オンプレミス）・コネクタのバージョン確定、MUnit を CI で実行
- [ ] API Manager のポリシー（JWT/mTLS）を MuleSoft 側でも独立に適用
- [ ] 全区間 TLS（Kong ↔ MuleSoft の mTLS とネットワーク分離はデモで実装済み）
- [ ] 全銀データの文字コード・桁位置を、接続する全銀システム（または中継サービス）の正式な仕様で照合
- [ ] SLO（遅延・可用性）、負荷試験、障害試験（Liberty 1 台停止・MuleSoft 停止・勘定系の遅延）
- [ ] 監視とアラート（Kong の指標、Anypoint Monitoring、保留の滞留）、運用手順の訓練
