package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToApisProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesBalance() throws Exception {
        String body = """
                {"credits_per_usd": 200, "remain_balance": 10.5, "remain_credits": 2100,
                 "success": true, "unlimited_quota": false, "used_balance": 2.5, "used_credits": 500}
                """;
        BalanceSnapshot snap = ToApisProvider.parseBalance(
                ProviderId.TOAPIS, "a1", "yy", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(10.5, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("剩余 Credits")
                && d.value().equals("2100")));
    }

    @Test
    void unlimitedQuotaHidesNegativeBalance() throws Exception {
        String body = """
                {"credits_per_usd": 200, "remain_balance": -1, "remain_credits": 0,
                 "success": true, "unlimited_quota": true, "used_balance": 0, "used_credits": 0}
                """;
        BalanceSnapshot snap = ToApisProvider.parseBalance(
                ProviderId.TOAPIS, "a1", "yy", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNull(snap.primaryAmount());
        assertEquals("不限量套餐", snap.message());
        // 不展示负余额指标与 0 余量
        assertTrue(snap.details().stream().noneMatch(d -> d.label().equals("剩余 Credits")));
        assertTrue(snap.metrics().isEmpty());
    }
}