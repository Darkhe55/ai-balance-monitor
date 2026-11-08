package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MistralProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesSubscriptionBalanceAndBudget() throws Exception {
        String body = """
                {
                  "plan_name": "Pay-as-you-go",
                  "credit_balance": 42.5,
                  "monthly_budget": 100,
                  "currency": "EUR"
                }
                """;
        BalanceSnapshot snap = MistralProvider.parseSubscription(
                ProviderId.MISTRAL, "a1", "mm", MAPPER.readTree(body)).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(42.5, snap.primaryAmount(), 1e-9);
        assertEquals("EUR", snap.primaryCurrency());
        assertEquals("账户 Credits", snap.primaryLabel());
        // 余额指标
        assertNotNull(snap.metricOf(ProviderCapability.BALANCE));
        // 月度预算指标（剩余比例 = 42.5/100 = 42.5%）
        assertNotNull(snap.metricOf(ProviderCapability.SPEND_LIMIT));
        assertEquals(42.5, snap.metricOf(ProviderCapability.SPEND_LIMIT).percent(), 1e-9);
        // 明细
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("套餐")
                && d.value().equals("Pay-as-you-go")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("月度预算")
                && d.value().equals("100.00 EUR")));
    }

    @Test
    void sumsDailyCostFromUsageArray() throws Exception {
        String body = """
                [
                  { "date": "2026-11-01", "total_cost": 1.25 },
                  { "date": "2026-11-02", "total_cost": 2.75 },
                  { "date": "2026-11-03", "total_cost": 0.50 }
                ]
                """;
        Double total = MistralProvider.sumDailyCost(MAPPER.readTree(body));
        assertEquals(4.5, total, 1e-9);
    }

    @Test
    void sumsDailyCostFromNestedObject() throws Exception {
        String body = """
                { "daily_cost": [
                    { "date": "2026-11-01", "total_cost": 3.0 },
                    { "date": "2026-11-02", "total_cost": 1.5 }
                ] }
                """;
        Double total = MistralProvider.sumDailyCost(MAPPER.readTree(body));
        assertEquals(4.5, total, 1e-9);
    }

    @Test
    void missingUsageReturnsNull() throws Exception {
        assertEquals(null, MistralProvider.sumDailyCost(MAPPER.readTree("{}")));
        assertEquals(null, MistralProvider.sumDailyCost(MAPPER.readTree("{\"daily_cost\":[]}")));
    }
}