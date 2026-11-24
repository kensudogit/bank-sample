-- bank-token-claims：jwt プラグイン（署名・exp・nbf を検証）の後で、OAuth 2.0 アクセストークンの
-- 発行者（iss）・宛先（aud）・ロール（roles）を検証する。OSS の jwt プラグインは iss・aud・scope を見ないため、その補完。
local typedefs = require "kong.db.schema.typedefs"

return {
  name = "bank-token-claims",
  fields = {
    { protocols = typedefs.protocols_http },
    { consumer = typedefs.no_consumer },
    { config = {
        type = "record",
        fields = {
          { issuer = { type = "string", required = true } },
          { audiences = { type = "array", required = true, elements = { type = "string" }, len_min = 1 } },
          -- いずれか 1 つを持っていればよい（空なら iss・aud だけを検証する）
          { required_roles = { type = "array", required = true, default = {}, elements = { type = "string" } } },
          { roles_claim = { type = "string", required = true, default = "roles" } },
        },
    } },
  },
}
