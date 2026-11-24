# 受入チェックリスト

## ローカルのデモ（2026-11-24 に確認）

- [x] ADR（ADR-001-gateway-and-integration.md）・API 一覧と経路・セキュリティ設計・運用手順を作成
- [x] OpenAPI（mulesoft/bank-integration/src/main/resources/api/bank-integration.yaml）
- [x] Mule 4 アプリ一式（pom・mule-artifact・フロー・DataWeave・設定・MUnit）— XML の整形式のみ確認。Mule ランタイムでのビルド・実行は未確認
- [x] 互換コンテナのテスト 18 件成功（MUnit と同じ fixtures）
- [x] 勘定系のテスト成功（サービスアカウントの既定無効・INTEGRATION ロール）
- [x] フロントエンドのテスト成功（連携コンソール・サーバー側 API・Kong への中継）
- [x] docker compose で kong・mule・liberty ×2・frontend ×2 が healthy
- [x] Kong 経由の否定テスト（401・403・404）と、全銀 被仕向の取込（入金・保留・再送で重複・検証 NG で 422）、請求データ、為替相場
- [x] `svc-mule` の最小権限（参照 API・未許可の更新 API は 403）

## 本番化の条件（未完了）

- [ ] ADR と経路の承認（業務・セキュリティ・運用）
- [ ] 連携先の認証を mTLS＋OAuth2 に変更（API キーは廃止）、鍵・証明書を Vault 等で管理しローテーション
- [ ] Kong のエディションとプラグインの確認（OIDC・mTLS・Redis 共有のレート制限）、宣言的設定のレビューと CI（decK）
- [ ] MuleSoft のライセンス・ランタイム（CloudHub 2.0 / Runtime Fabric / オンプレミス）・コネクタのバージョン確定、MUnit を CI で実行
- [ ] API Manager のポリシー（JWT/mTLS）を MuleSoft 側でも独立に適用
- [ ] 全区間 TLS、Kong ↔ MuleSoft の mTLS、ネットワーク分離
- [ ] 全銀データの文字コード・桁位置を、接続する全銀システム（または中継サービス）の正式な仕様で照合
- [ ] SLO（遅延・可用性）、負荷試験、障害試験（Liberty 1 台停止・MuleSoft 停止・勘定系の遅延）
- [ ] 監視とアラート（Kong の指標、Anypoint Monitoring、保留の滞留）、運用手順の訓練
