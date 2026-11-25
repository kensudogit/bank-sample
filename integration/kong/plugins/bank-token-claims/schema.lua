-- bank-token-claims：jwt プラグイン（署名・exp・nbf を検証）の後で、OAuth 2.0 アクセストークンの
-- 発行者（iss）・宛先（aud）・テナント（tid）・種類（アプリのみ／委任）・ロール（roles）・スコープ（scp）を検証する。
-- OSS の jwt プラグインは iss・aud・scope を見ないため、その補完。
-- forward_identity=true なら、検証済みのクレームを上流（Liberty）への識別ヘッダーにする（送信元の同名ヘッダーは消す）。
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
          -- いずれか 1 つを持っていればよい（空なら検証しない）
          { required_roles = { type = "array", required = true, default = {}, elements = { type = "string" } } },
          { required_scopes = { type = "array", required = true, default = {}, elements = { type = "string" } } },
          { roles_claim = { type = "string", required = true, default = "roles" } },
          -- app：アプリのみ（idtyp=app）、delegated：行員の委任（oid と scp があり、idtyp=app でない）、any：どちらでも
          { token_type = { type = "string", required = true, default = "any", one_of = { "any", "app", "delegated" } } },
          -- 受け付けるテナント（tid）。空なら検証しない
          { allowed_tenants = { type = "array", required = true, default = {}, elements = { type = "string" } } },
          { forward_identity = { type = "boolean", required = true, default = false } },
        },
    } },
  },
}
