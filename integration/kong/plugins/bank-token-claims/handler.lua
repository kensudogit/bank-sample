-- bank-token-claims：jwt プラグインが検証済みのトークン（kong.ctx.shared.authenticated_jwt_token）の
-- iss・aud・tid・種類・roles・scp を確かめる。満たさなければ 403（トークン自体がない・壊れていれば 401）。
-- forward_identity=true なら、検証済みのクレームだけから上流への識別ヘッダーを作る（送信元が付けた同名ヘッダーは必ず消す）。
local cjson = require "cjson.safe"

local BankTokenClaims = {
  -- jwt（1450）より後、acl（950）より前に動く
  PRIORITY = 1005,
  VERSION = "1.1.0",
}

-- 上流に渡す識別ヘッダー（送信元からは受け付けない）
local IDENTITY_HEADERS = {
  "X-Bank-Auth", "X-Bank-Delegated-User", "X-Bank-Delegated-Oid", "X-Bank-Agent-Id",
  "X-Bank-Scopes", "X-Bank-Roles", "X-Bank-Token-Id", "X-Bank-Tenant",
}

local function b64url_decode(s)
  s = s:gsub("-", "+"):gsub("_", "/")
  local pad = #s % 4
  if pad > 0 then s = s .. string.rep("=", 4 - pad) end
  return ngx.decode_base64(s)
end

local function contains(list, value)
  if type(list) == "string" then return list == value end
  if type(list) ~= "table" then return false end
  for _, v in ipairs(list) do
    if v == value then return true end
  end
  return false
end

local function any_of(have, wanted)
  for _, w in ipairs(wanted) do
    if contains(have, w) then return true end
  end
  return false
end

local function split_spaces(s)
  local out = {}
  if type(s) ~= "string" then return out end
  for item in s:gmatch("%S+") do out[#out + 1] = item end
  return out
end

local function safe(value)
  -- ヘッダー値に使えない文字（改行など）を含むクレームは使わない
  if type(value) ~= "string" or value == "" or value:find("[%c]") then return nil end
  return value
end

local function deny(status, message)
  return kong.response.exit(status, { message = message },
    { ["Content-Type"] = "application/json", ["WWW-Authenticate"] = 'Bearer error="invalid_token"' })
end

function BankTokenClaims:access(conf)
  -- 送信元が付けた識別ヘッダーは、検証の結果にかかわらず消す
  for _, h in ipairs(IDENTITY_HEADERS) do kong.service.request.clear_header(h) end

  local token = kong.ctx.shared.authenticated_jwt_token
  if not token then
    return deny(401, "Unauthorized")
  end
  local payload_part = token:match("^[^.]+%.([^.]+)%.")
  local raw = payload_part and b64url_decode(payload_part)
  local claims = raw and cjson.decode(raw)
  if type(claims) ~= "table" then
    return deny(401, "Unauthorized")
  end
  if claims.iss ~= conf.issuer then
    kong.log.notice("token rejected: unexpected issuer")
    return deny(403, "Forbidden")
  end
  if not any_of(claims.aud, conf.audiences) then
    kong.log.notice("token rejected: unexpected audience")
    return deny(403, "Forbidden")
  end
  if #conf.allowed_tenants > 0 and not contains(conf.allowed_tenants, claims.tid) then
    kong.log.notice("token rejected: tenant not allowed")
    return deny(403, "Forbidden")
  end
  local is_app = claims.idtyp == "app"
  if conf.token_type == "app" and not is_app then
    kong.log.notice("token rejected: application token required")
    return deny(403, "Forbidden")
  end
  if conf.token_type == "delegated" and (is_app or not safe(claims.oid) or type(claims.scp) ~= "string") then
    kong.log.notice("token rejected: delegated (user) token required")
    return deny(403, "Forbidden")
  end
  if #conf.required_roles > 0 and not any_of(claims[conf.roles_claim], conf.required_roles) then
    kong.log.notice("token rejected: missing role")
    return deny(403, "Forbidden")
  end
  local scopes = split_spaces(claims.scp)
  if #conf.required_scopes > 0 and not any_of(scopes, conf.required_scopes) then
    kong.log.notice("token rejected: missing scope")
    return deny(403, "Forbidden")
  end

  if conf.forward_identity then
    local set = kong.service.request.set_header
    set("X-Bank-Auth", is_app and "app-token" or "delegated-token")
    if safe(claims.azp) then set("X-Bank-Agent-Id", claims.azp) end
    if safe(claims.jti) then set("X-Bank-Token-Id", claims.jti) end
    if safe(claims.tid) then set("X-Bank-Tenant", claims.tid) end
    if not is_app then
      if safe(claims.preferred_username) then set("X-Bank-Delegated-User", claims.preferred_username) end
      set("X-Bank-Delegated-Oid", claims.oid)
      set("X-Bank-Scopes", table.concat(scopes, " "))
    elseif type(claims.roles) == "table" then
      set("X-Bank-Roles", table.concat(claims.roles, " "))
    end
  end
end

return BankTokenClaims
