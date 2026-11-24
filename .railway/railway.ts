import { defineRailway, github, preserve, project, service, volume } from "railway/iac";

// GitHub（kensudogit/bank-sample の main）から、サービスごとのフォルダをビルドする。
// watchPatterns に合うファイルが変わったときだけ、そのサービスを再ビルドする（Oracle は db/ の変更時のみ再起動）。
const repo = (rootDirectory: string) => github("kensudogit/bank-sample", { branch: "main", rootDirectory });

export default defineRailway(() => {
  const oracleVolume = volume("oracle-volume", { alerts: { usage: { "100": {}, "80": {}, "95": {} } }, allowOnlineResize: true, region: "asia-southeast1-eqsg3a", sizeMB: 50000 });
  const oracle = service("oracle", {
    source: repo("db"),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile", watchPatterns: ["/db/**"] },
    deploy: { restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5 },
    replicas: { "asia-southeast1-eqsg3a": 1 },
    volumeMounts: { "/opt/oracle/oradata": oracleVolume },
    env: { APP_USER: preserve(), APP_USER_PASSWORD: preserve(), MULE_SERVICE_PASSWORD_HASH: preserve(), NLS_LANG: preserve(), ORACLE_PASSWORD: preserve() },
  });
  const liberty = service("liberty", {
    source: repo("backend"),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile", watchPatterns: ["/backend/**"] },
    deploy: { healthcheckPath: "/bank/api/live", healthcheckTimeout: 300, restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5 },
    replicas: { "asia-southeast1-eqsg3a": 1 },
    env: { BANK_COOKIE_SECURE: preserve(), BANK_DB_PASSWORD: preserve(), BANK_DB_URL: preserve(), BANK_DB_USER: preserve(), BANK_JOBS_ENABLED: preserve(), PORT: preserve(), TZ: preserve() },
  });
  const kong = service("kong", {
    source: repo("integration/kong"),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile", watchPatterns: ["/integration/kong/**"] },
    deploy: { healthcheckPath: "/gateway-health", healthcheckTimeout: 120, restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5 },
    replicas: { "asia-southeast1-eqsg3a": 1 },
    env: { KONG_ADMIN_LISTEN: preserve(), KONG_DATABASE: preserve(), KONG_HEADERS: preserve(), KONG_INTERNAL_HOSTS: preserve(), KONG_KEY_BILLING: preserve(), KONG_KEY_FX: preserve(), KONG_KEY_OPS: preserve(), KONG_KEY_ZENGIN: preserve(), KONG_LIBERTY_TARGETS: preserve(), KONG_MULE_HOST: preserve(), KONG_NGINX_WORKER_PROCESSES: preserve(), KONG_PROXY_ACCESS_LOG: preserve(), KONG_PROXY_ERROR_LOG: preserve(), KONG_PROXY_LISTEN: preserve(), KONG_REAL_IP_HEADER: preserve(), KONG_REAL_IP_RECURSIVE: preserve(), KONG_STATUS_LISTEN: preserve(), KONG_TRUSTED_IPS: preserve(), PORT: preserve() },
  });
  const frontend = service("frontend", {
    source: repo("frontend"),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile", watchPatterns: ["/frontend/**"] },
    deploy: { healthcheckPath: "/login", healthcheckTimeout: 120, restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5 },
    replicas: { "asia-southeast1-eqsg3a": 1 },
    env: { BANK_API_INTERNAL: preserve(), BANK_API_ORIGIN: preserve(), HOSTNAME: preserve(), INTEGRATION_GATEWAY_URL: preserve(), INTEGRATION_OPS_KEY: preserve(), PORT: preserve() },
  });
  const mule = service("mule", {
    source: repo("integration/mule-compat"),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile", watchPatterns: ["/integration/mule-compat/**"] },
    deploy: { healthcheckPath: "/api/v1/health", healthcheckTimeout: 60, restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5 },
    replicas: { "asia-southeast1-eqsg3a": 1 },
    env: { CORE_BASE_URL: preserve(), MULE_CORE_PASSWORD: preserve(), MULE_CORE_USER: preserve(), OUR_BANK_CODE: preserve(), PORT: preserve(), TZ: preserve() },
  });

  return project("bank-sample", {
    resources: [oracle, liberty, kong, frontend, mule, oracleVolume],
  });
});
