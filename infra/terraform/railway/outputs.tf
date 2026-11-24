output "frontend_url" {
  description = "画面の公開 URL"
  value       = "https://${railway_service_domain.frontend.domain}"
}

output "gateway_url" {
  description = "連携先の入口（Kong）。/oauth2/token と /integration/v1/*"
  value       = "https://${railway_service_domain.kong.domain}"
}

output "service_ids" {
  description = "サービス名 → Railway のサービス ID"
  value       = local.service_ids
}
