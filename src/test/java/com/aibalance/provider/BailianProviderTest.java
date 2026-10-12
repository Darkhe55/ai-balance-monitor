package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesModelQuotas() throws Exception {
        String body = """
                {"success": true, "output": {
                   "total": 2, "page_no": 1, "page_size": 10,
                   "quotas": [
                     {"model": "qwen3-max", "workspace_id": "llm-ws1",
                      "model_limit": {"request_limit": 500, "request_limit_period": 1,
                                      "usage_limit": 500000, "usage_limit_field": "total_tokens",
                                      "usage_limit_period": 6},
                      "workspace_limit": null},
                     {"model": "qwen-image-max", "workspace_id": "llm-ws1",
                      "model_limit": {"request_limit": 2, "request_limit_period": 60,
                                      "usage_limit": 1000000, "usage_limit_field": "total_tokens",
                                      "usage_limit_period": 60},
                      "workspace_limit": null}
                   ]
                 }}
                """;
        BalanceSnapshot snap = BailianProvider.fromJson(
                "b1", "百炼配额", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(ProviderId.BAILIAN, snap.providerId());
        assertEquals(2, snap.primaryAmount(), 1e-9);
        assertEquals("个", snap.primaryCurrency());
        assertEquals("模型限流配额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("qwen3-max")
                && l.value().contains("请求 500 次/秒") && l.value().contains("用量 500000 total_tokens/秒")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("qwen-image-max")
                && l.value().contains("请求 2 次/分钟")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("口径")));
    }

    @Test
    void noQuotasReportsError() throws Exception {
        BalanceSnapshot snap = BailianProvider.fromJson(
                "b2", "百炼", MAPPER.readTree("{\"success\": true, \"output\": {\"quotas\": []}}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
