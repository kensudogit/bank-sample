# bank-sample の Railway 構成（プロジェクト・7 サービス・ボリューム・公開ドメイン・秘密でない環境変数）。
#
# - 各サービスは GitHub の main からフォルダごとにビルドし、watch_patterns に合う変更があったときだけ再ビルドする
# - 秘密情報（パスワード・鍵・証明書・クライアントシークレット）は Terraform で扱わない（state に残さない）。
#   deploy/railway.sh secrets が .env.railway から Railway に直接設定する
# - 社内ネットワーク（<service>.railway.internal）だけで通信し、公開ドメインは frontend（画面）と kong（連携先の入口）だけ
#
#   terraform init && terraform plan        # 変更内容の確認
#   terraform apply                          # 反映（GitHub Actions の terraform-railway でも実行できる）

locals {
  services = {
    oracle = {
      root = "db", watch = ["/db/**"], healthcheck = null, timeout = null
    }
    liberty = {
      root = "backend", watch = ["/backend/**"], healthcheck = "/bank/api/live", timeout = 300
    }
    kong = {
      root = "integration/kong", watch = ["/integration/kong/**"], healthcheck = "/gateway-health", timeout = 120
    }
    frontend = {
      root = "frontend", watch = ["/frontend/**"], healthcheck = "/login", timeout = 120
    }
    mule = {
      root = "integration/mule-compat", watch = ["/integration/mule-compat/**"], healthcheck = "/live", timeout = 60
    }
    auth = {
      root = "integration/auth-server", watch = ["/integration/auth-server/**"], healthcheck = "/live", timeout = 60
    }
  }

  # 秘密でない環境変数（サービス → 変数名 → 値）
  variables = {
    oracle = {
      APP_USER = "BANK"
      NLS_LANG = "AMERICAN_AMERICA.AL32UTF8"
    }
    liberty = {
      BANK_COOKIE_SECURE = "true"
      BANK_DB_URL        = "jdbc:oracle:thin:@//oracle.railway.internal:1521/FREEPDB1"
      BANK_DB_USER       = "BANK"
      BANK_JOBS_ENABLED  = "true"
      PORT               = "9080"
      TZ                 = "Asia/Tokyo"
    }
    kong = {
      PORT                          = "8000"
      KONG_DATABASE                 = "off"
      KONG_PROXY_LISTEN             = "0.0.0.0:8000, [::]:8000"
      KONG_ADMIN_LISTEN             = "off"
      KONG_STATUS_LISTEN            = "[::]:8100"
      KONG_HEADERS                  = "off"
      KONG_PROXY_ACCESS_LOG         = "/dev/stdout"
      KONG_PROXY_ERROR_LOG          = "/dev/stderr"
      KONG_TRUSTED_IPS              = "10.0.0.0/8,100.64.0.0/10,172.16.0.0/12,192.168.0.0/16,fc00::/7"
      KONG_REAL_IP_HEADER           = "X-Forwarded-For"
      KONG_REAL_IP_RECURSIVE        = "on"
      KONG_NGINX_WORKER_PROCESSES   = "2"
      KONG_INTERNAL_HOSTS           = "kong.railway.internal:8000"
      KONG_LIBERTY_TARGETS          = "liberty.railway.internal:9080"
      KONG_MULE_HOST                = "mule.railway.internal"
      KONG_AUTH_HOST                = "auth.railway.internal"
      KONG_REDIS_HOST               = "redis.railway.internal"
      KONG_REDIS_PORT               = "6379"
      AUTH_MODE                     = "local"
      KONG_TOKEN_AUDIENCE           = "api://bank-integration"
      KONG_TRACING_INSTRUMENTATIONS = "off"
    }
    frontend = {
      PORT                    = "3000"
      HOSTNAME                = "::"
      BANK_API_ORIGIN         = "http://kong.railway.internal:8000"
      BANK_API_INTERNAL       = "http://kong.railway.internal:8000/bank/api"
      INTEGRATION_GATEWAY_URL = "http://kong.railway.internal:8000/integration/v1"
      INTEGRATION_TOKEN_URL   = "http://kong.railway.internal:8000/oauth2/token"
      INTEGRATION_CLIENT_ID   = "bank-ops"
      PUBLIC_ORIGIN           = var.public_origin
    }
    mule = {
      PORT                 = "8081"
      TLS_PORT             = "8443"
      CORE_BASE_URL        = "http://kong.railway.internal:8000/bank/api"
      MULE_CORE_USER       = "svc-mule"
      OUR_BANK_CODE        = "9999"
      MTLS_ALLOWED_CLIENTS = "kong-gateway"
      TZ                   = "Asia/Tokyo"
    }
    auth = {
      PORT          = "8080"
      AUTH_ISSUER   = "https://auth.bank-sample.local"
      AUTH_AUDIENCE = "api://bank-integration"
    }
    redis = {}
  }

  service_ids = merge(
    { for k, s in railway_service.app : k => s.id },
    { redis = railway_service.redis.id },
  )

  flat_variables = merge([
    for svc, vars in local.variables : { for name, value in vars : "${svc}.${name}" => { service = svc, name = name, value = value } }
  ]...)
}

resource "railway_project" "bank_sample" {
  name        = "bank-sample"
  description = "学習用の銀行サンプル（Struts 2 / Liberty / Oracle / Next.js / Kong / MuleSoft）。架空データのみ"
  private     = true

  default_environment = {
    name = "production"
  }
}

# GitHub からフォルダごとにビルドするサービス（Dockerfile と railway.toml は各フォルダにある）
resource "railway_service" "app" {
  for_each = local.services

  project_id         = railway_project.bank_sample.id
  name               = each.key
  source_repo        = var.github_repo
  source_repo_branch = var.branch
  root_directory     = each.value.root
  dockerfile_path    = "Dockerfile"
  watch_patterns     = each.value.watch

  healthcheck_path    = each.value.healthcheck
  healthcheck_timeout = each.value.timeout

  restart_policy_type        = "ON_FAILURE"
  restart_policy_max_retries = 5

  regions = [{ region = var.region, num_replicas = 1 }]

  # Oracle のデータファイル（ボリュームは root 所有でマウントされる。db/railway-entrypoint.sh が所有者を直す）
  volume = each.key == "oracle" ? { name = "oracle-volume", mount_path = "/opt/oracle/oradata" } : null
}

# Kong のレート制限のカウンター（全台で共有）。データは持たない（再起動で消えてよい）
resource "railway_service" "redis" {
  project_id                 = railway_project.bank_sample.id
  name                       = "redis"
  source_image               = "redis:7.4.1-alpine"
  start_command              = "sh -c 'exec redis-server --requirepass \"$REDIS_PASSWORD\" --bind :: 0.0.0.0 --save \"\" --appendonly no --maxmemory 64mb --maxmemory-policy volatile-ttl'"
  restart_policy_type        = "ON_FAILURE"
  restart_policy_max_retries = 5
  regions                    = [{ region = var.region, num_replicas = 1 }]
}

resource "railway_variable" "config" {
  for_each = local.flat_variables

  environment_id = var.environment_id
  service_id     = local.service_ids[each.value.service]
  name           = each.value.name
  value          = each.value.value
}

# 公開ドメイン：画面と、連携先の入口（Kong）だけ。auth・mule・liberty・oracle・redis は社内ネットワークのみ
resource "railway_service_domain" "frontend" {
  environment_id = var.environment_id
  service_id     = railway_service.app["frontend"].id
  subdomain      = "frontend-production-b2e8901b"
  target_port    = 3000
}

resource "railway_service_domain" "kong" {
  environment_id = var.environment_id
  service_id     = railway_service.app["kong"].id
  subdomain      = "kong-production-27feb5b1"
  target_port    = 8000
}
