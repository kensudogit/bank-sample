variable "github_repo" {
  description = "ビルド元の GitHub リポジトリ（owner/name）"
  type        = string
  default     = "kensudogit/bank-sample"
}

variable "branch" {
  description = "自動デプロイするブランチ"
  type        = string
  default     = "main"
}

variable "region" {
  description = "Railway のリージョン"
  type        = string
  default     = "asia-southeast1-eqsg3a"
}

variable "environment_id" {
  description = "Railway の環境（production）の ID。プロジェクトの既定の環境"
  type        = string
  default     = "3ec2110b-04c8-4c4d-ad12-ceb3f58452bd"
}

variable "public_origin" {
  description = "画面の公開 URL（連携コンソールの CSRF 対策で Origin を完全一致させる）"
  type        = string
  default     = "https://frontend-production-b2e8901b.up.railway.app"
}
