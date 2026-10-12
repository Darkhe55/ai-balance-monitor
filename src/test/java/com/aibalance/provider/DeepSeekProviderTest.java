package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesFullBalancePayload() throws Exception {
        String body = """
                {
                  "is_available": true,
                  "balance_infos": [
                    {"currency": "CNY", "total_balance": "110.00",
                     "granted_balance": "0.00", "topped_up_balance": "110.00"}
                  ]
                }
                """;
        BalanceSnapshot snap = DeepSeekProvider.fromJson(
                ProviderId.DEEPSEEK, "a1", "主账户", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(110.00, snap.primaryAmount(), 1e-9);
        assertEquals("CNY", snap.primaryCurrency());
        assertEquals("账户余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("充值余额")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("可用状态") && l.value().equals("可用")));
    }

    @Test
    void handlesNumericBalanceFields() throws Exception {
        String body = """
                {"is_available": true, "balance_infos": [
                  {"currency": "CNY", "total_balance": 42.5, "granted_balance": 2.5, "topped_up_balance": 40.0}]}
                """;
        BalanceSnapshot snap = DeepSeekProvider.fromJson(
                ProviderId.DEEPSEEK, "a1", "主账户", MAPPER.readTree(body));
        assertEquals(42.5, snap.primaryAmount(), 1e-9);
    }

    @Test
    void handlesMissingBalanceInfos() throws Exception {
        BalanceSnapshot snap = DeepSeekProvider.fromJson(
                ProviderId.DEEPSEEK, "a1", "主账户", MAPPER.readTree("{\"is_available\": false}"));
        assertNull(snap.primaryAmount());
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNotNull(snap.message());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("可用状态") && l.value().contains("不可用")));
    }
}
