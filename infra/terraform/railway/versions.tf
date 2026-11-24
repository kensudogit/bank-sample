terraform {
  required_version = ">= 1.6.0"

  required_providers {
    railway = {
      source  = "terraform-community-providers/railway"
      version = "~> 0.6.3"
    }
  }
}

# 認証：環境変数 RAILWAY_TOKEN（Railway のアカウント／ワークスペースのトークン。GitHub Actions ではシークレット）
provider "railway" {}
