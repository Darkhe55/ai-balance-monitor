package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenMuxProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesSubscriptionQuotaAndPayg() throws Exception {
        String sub = """
                {
                  "success": true,
                  "data": {
                    "plan": {"tier": "ultra", "amount_usd": 200, "interval": "month",
                             "expires_at": "2026-04-12T08:26:56.000Z"},
                    "currency": "usd",
                    "account_status": "healthy",
                    "quota_5_hour": {
                      "usage_percentage": 0.0715, "resets_at": "2026-03-24T08:35:09.000Z",
                      "max_flows": 800, "used_flows": 57.2, "remaining_flows": 742.8,
                      "used_value_usd": 1.88, "max_value_usd": 26.27
                    },
                    "quota_7_day": {
                      "usage_percentage": 0.0673, "resets_at": "2026-03-26T02:15:05.000Z",
                      "max_flows": 6182, "used_flows": 416.11, "remaining_flows": 5765.89,
                      "used_value_usd": 13.66, "max_value_usd": 202.99
                    },
                    "quota_monthly": {"max_flows": 34560, "max_value_usd": 1134.33}
                  }
                }
                """;
        String payg = """
                {"success": true, "data": {"currency": "usd", "total_credits": 482.74,
                 "top_up_credits": 35.0, "bonus_credits": 447.74}}
                """;
        BalanceSnapshot snap = ZenMuxProvider.parseData(
                ProviderId.ZENMUX, "a1", "ze", MAPPER.readTree(sub), MAPPER.readTree(payg));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // PAYG 为主展示
        assertEquals(482.74, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("订阅套餐")
                && d.value().equals("ultra")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("5小时窗口")
                && d.value().equals("剩余 92.9%")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("7天窗口")
                && d.value().equals("剩余 93.3%")));
    }

    @Test
    void paygOnlyWorks() throws Exception {
        String payg = """
                {"success": true, "data": {"currency": "usd", "total_credits": 10.0}}
                """;
        BalanceSnapshot snap = ZenMuxProvider.parseData(
                ProviderId.ZENMUX, "a1", "ze", null, MAPPER.readTree(payg));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(10.0, snap.primaryAmount(), 1e-9);
    }
}