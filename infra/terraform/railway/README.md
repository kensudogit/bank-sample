# Railway の構成（Terraform）

bank-sample の Railway プロジェクトを Terraform（コミュニティ provider `terraform-community-providers/railway`）で管理します。
以前の `.railway/railway.ts`（Railway の構成コード）は廃止しました。

| 管理するもの | 管理しないもの |
|---|---|
| プロジェクト、7 サービス（oracle・liberty・kong・frontend・mule・auth・redis）、GitHub のビルド元・フォルダ・監視パス、ヘルスチェック、再起動の方針、リージョン、Oracle のボリューム、公開ドメイン（frontend・kong）、秘密でない環境変数 | 秘密情報（パスワード・鍵・証明書・クライアントシークレット）。state に残さないため `deploy/railway.sh secrets` で設定する |

## 使い方

```bash
export RAILWAY_TOKEN=...        # Railway のアカウント（ワークスペース）トークン。ダッシュボードの Account Settings → Tokens
cd infra/terraform/railway
terraform init
terraform plan                  # 初回は既存のプロジェクト・5 サービス・ドメインを import ブロックで取り込み、auth・redis を作る
terraform apply
```

- 既存の環境変数と同じ名前の変数は、作成時に値が上書きされます（同じ値なら変化なし）。
- state はローカル（`terraform.tfstate`・Git 管理外）です。チームや GitHub Actions から apply するときは、`backend.tf` でリモートの保存先
  （S3・Terraform Cloud など）を設定してください。CI（`.github/workflows/ci.yml`）は `fmt`・`validate` だけを行います。
- Windows では Docker でも実行できます：`docker run --rm -e RAILWAY_TOKEN -v "$PWD:/tf" -w /tf hashicorp/terraform:1.9.8 plan`
