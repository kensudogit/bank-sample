package com.example.bank.action;

import com.example.bank.service.BankService;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/health — DB 接続確認（コンテナのヘルスチェック用）。 */
public class HealthAction extends BaseApiAction {

    public HealthAction() { this(BankService.getDefault()); }

    public HealthAction(BankService service) { super(service); }

    @Override
    public String execute() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("database", service.ping() ? "UP" : "DOWN");
        return ok(data);
    }
}
