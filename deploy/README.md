# Railway へのデプロイ

デモ用サンプル（架空データ）。本番利用不可。

```
ブラウザ ─ https://<frontend>.up.railway.app ─ frontend ─ kong.railway.internal:8000 ─ liberty ─ oracle（ボリューム）
連携先   ─ https://<kong>.up.railway.app/integration/v1/*（API キー）─ kong ─ mule ─ liberty
```

| サービス | ソース | 公開 | 備考 |
|---|---|---|---|
| oracle | `db/`（gvenzl/oracle-free ＋ `db/init`） | なし | ボリューム `/opt/oracle/oradata`（root 所有でマウントされるため `railway-entrypoint.sh` が起動時に所有者を oracle に直す）。初回起動で SQL を実行（数分）。`MULE_SERVICE_PASSWORD_HASH` で svc-mule を有効化 |
| liberty | `backend/` | なし | `BANK_COOKIE_SECURE=true`（HTTPS のみで Cookie を送る）。死活確認は `/bank/api/live` |
| mule | `integration/mule-compat/` | なし | MuleSoft 互換コンテナ |
| kong | `integration/kong/` | あり（連携先用） | 画面用の `/bank/api` は社内名（`kong.railway.internal:8000`）でしか受け付けない |
| frontend | `frontend/` | あり（画面） | `BANK_API_ORIGIN`（ビルド時）で Kong を指す |

docker compose の nginx（lb）は使いません（公開は Railway、Liberty への振り分けは Kong）。

## 手順

1. `railway login`（ブラウザで Railway にログイン）
2. 秘密情報を作る：`.env.railway`（Git 管理外）に `ORACLE_PASSWORD`・`BANK_DB_USER`・`BANK_DB_PASSWORD`・`KONG_KEY_*`・
   `MULE_CORE_PASSWORD`・`MULE_SERVICE_PASSWORD_HASH`（`MULE_CORE_PASSWORD` の PBKDF2 ハッシュ。形式は
   `integration/scripts/set_service_password.py` と同じ）。ローカルの `.env` とは別の値にする
3. `bash deploy/railway.sh`（プロジェクト・サービス・ボリューム・変数・ドメインを作成し、各サービスをデプロイ）。
   Windows の Git Bash では `MSYS_NO_PATHCONV=1 bash deploy/railway.sh`（`/opt/...` や `/dev/stdout` を Windows のパスに書き換えさせない）
4. Oracle の初回作成（数分）が終わると liberty が DB に接続し、画面からログインできる

## 注意

- デモユーザーのパスワードは README に公開されています（`BankDemo#2026`）。公開 URL では誰でもログインできるため、
  架空データ以外を入れないでください。必要なら Railway の設定で公開ドメインを外すか、各ユーザーのパスワードを変更してください。
- Oracle は常時 2 GB 前後のメモリを使います。Railway の料金（使用量課金）を確認してください。
- 連携先の API キーは `.env.railway` の `KONG_KEY_*` です。安全な経路で渡してください。
