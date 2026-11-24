# 既存の Railway のリソースを Terraform の管理下に入れる（Terraform 1.5 以降の import ブロック。初回の apply で取り込み、以後は何もしない）。
# auth・redis と、新しく追加した環境変数は Terraform が作成する。

import {
  to = railway_project.bank_sample
  id = "4fce715e-de7c-4c29-b06b-9bc463625983"
}

import {
  for_each = {
    oracle   = "54b2f03e-3b64-4117-a60f-20e5be3c19ec"
    liberty  = "5b72b826-5779-4ef2-a76b-ba155828dd3e"
    kong     = "6e3871ab-ee3e-4b40-a4a7-ddd03dd6894f"
    frontend = "de7b1051-9cd0-44ad-9b51-31b1507dd6b7"
    mule     = "f46a1ded-79f2-45b9-baaf-30a5f49c25ed"
  }
  to = railway_service.app[each.key]
  id = each.value
}

import {
  to = railway_service_domain.frontend
  id = "de7b1051-9cd0-44ad-9b51-31b1507dd6b7:production:frontend-production-b2e8901b.up.railway.app"
}

import {
  to = railway_service_domain.kong
  id = "6e3871ab-ee3e-4b40-a4a7-ddd03dd6894f:production:kong-production-27feb5b1.up.railway.app"
}
