# Railway へのデプロイ

デモ用サンプル（架空データ）。本番利用不可。

```
ブラウザ ─ https://<frontend>.up.railway.app ─ frontend ─ kong.railway.internal:8000 ─(共有シークレット)─ liberty ─ oracle（ボリューム）
連携先   ─ https://<kong>.up.railway.app/oauth2/token ─ kong ─ auth（OAuth 2.0）
        ─ https://<kong>.up.railway.app/integration/v1/*（Bearer）─ kong ─(mTLS)─ mule ─ kong ─ liberty
kong ─ redis（レート制限を全台で共有）
```

| サービス | ソース | 公開 | 備考 |
|---|---|---|---|
| oracle | `db/`（gvenzl/oracle-free ＋ `db/init`） | なし | ボリューム `/opt/oracle/oradata`（root 所有でマウントされるため `railway-entrypoint.sh` が所有者を直す）。初回起動で SQL を実行 |
| liberty | `backend/` | なし | `BANK_GATEWAY_SECRET`（Kong と共有。ないと業務 API は 503）、`BANK_COOKIE_SECURE=true` |
| auth | `integration/auth-server/` | なし | OAuth 2.0 のトークン発行。連携先へは Kong の `/oauth2/token` で公開 |
| mule | `integration/mule-compat/` | なし | 8443（mTLS・Kong だけ）／8081（`/live`・`/metrics`） |
| kong | `integration/kong/` | あり（連携先用） | 画面用の `/bank/api` は社内名（`kong.railway.internal:8000`）でしか受け付けない |
| redis | イメージ `redis:7.4.1-alpine` | なし | Kong のレート制限のカウンターだけ（永続化しない） |
| frontend | `frontend/` | あり（画面） | `BANK_API_ORIGIN`（ビルド時）で Kong を指す。`PUBLIC_ORIGIN` は公開 URL（CSRF の Origin の完全一致） |

docker compose の nginx（lb）と監視（Prometheus・Grafana・Jaeger）は Railway では使いません（費用のため。指標は各サービスの `/metrics`）。

## 構成管理：Terraform（infra/terraform/railway）

プロジェクト・7 サービス・ボリューム・公開ドメイン・秘密でない環境変数は Terraform で管理します（[infra/terraform/railway/README.md](../infra/terraform/railway/README.md)）。
秘密情報は Terraform の state に残さないよう、次のスクリプトで設定します。

```bash
bash integration/scripts/gen-secrets.sh --railway >> .env.railway   # 外部連携の秘密情報（mTLS の証明書は *.railway.internal 向け）
bash deploy/railway.sh secrets                                      # 値は標準入力で渡す（コマンドライン・履歴に残さない）
```

`.env.railway`（Git 管理外）には、ほかに `ORACLE_PASSWORD`・`BANK_DB_USER`・`BANK_DB_PASSWORD`・`MULE_CORE_PASSWORD`・
`MULE_SERVICE_PASSWORD_HASH`（`MULE_CORE_PASSWORD` の PBKDF2 ハッシュ。形式は `integration/scripts/set_service_password.py` と同じ）を置きます。ローカルの `.env` とは別の値にしてください。

## GitHub からの自動デプロイ

各サービスは GitHub（`kensudogit/bank-sample` の `main`）に接続されており、push のたびに Railway がビルドします。
変更したファイルが `watch_patterns` に合うサービスだけが再ビルドされます（例：`frontend/**` の変更では frontend だけ。
Oracle は `db/**` を変えたときだけ再起動し、データはボリュームに残る）。CI（`.github/workflows/ci.yml`）も同じ push で動きます。

## 既存の DB への移行

`db/init` の SQL は Oracle の初回作成時にだけ実行されます。作成済みの DB には、起動のたびに動く `db/startdb/10_integration_hardening.sh` が
未適用の `13_integration_hardening.sql`（表）と `14_audit_protection.sh`（監査ログの保護）を一度だけ適用します（適用済みなら何もしない）。
oracle の再デプロイ（`db/**` の変更、または Redeploy）で反映されます。SSH キーを登録済みなら `bash deploy/railway.sh migrate` でも適用できます。

## 注意

- デモユーザーのパスワードは README に公開されています（`BankDemo#2026`）。公開 URL では誰でもログインできるため、
  架空データ以外を入れないでください。必要なら公開ドメインを外すか、各ユーザーのパスワードを変更してください。
- Oracle は常時 2 GB 前後のメモリを使います。Railway の料金（使用量課金）を確認してください。
- 連携先のクライアントシークレットは `.env.railway` の `OAUTH_SECRET_*` です。安全な経路で渡してください。
