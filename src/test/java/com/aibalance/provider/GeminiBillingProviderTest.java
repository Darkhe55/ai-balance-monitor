package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiBillingProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void moneyValueCombinesUnitsAndNanos() {
        String json = """
                {"specifiedAmount":{"currencyCode":"USD","units":"500","nanos":500000000},
                 "lastPeriodCost":{"currencyCode":"USD","units":"120","nanos":0}}
                """;
        try {
            var node = MAPPER.readTree(json);
            assertEquals(500.5, GeminiBillingProvider.moneyValue(node.get("specifiedAmount")), 1e-9);
            assertEquals(120.0, GeminiBillingProvider.moneyValue(node.get("lastPeriodCost")), 1e-9);
            assertNull(GeminiBillingProvider.moneyValue(MAPPER.readTree("{}")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fromJsonListsBudgetsWithSpecifiedAndLastPeriod() throws Exception {
        String body = """
                {"budgets":[
                  {"name":"billingAccounts/abc/budgets/1","displayName":"月度预算",
                   "amount":{"specifiedAmount":{"currencyCode":"USD","units":"500","nanos":0},
                             "lastPeriodCost":{"currencyCode":"USD","units":"120","nanos":0}}},
                  {"name":"billingAccounts/abc/budgets/2","displayName":"项目 A 预算",
                   "amount":{"specifiedAmount":{"currencyCode":"USD","units":"200","nanos":0},
                             "lastPeriodCost":{"currencyCode":"USD","units":"50","nanos":0}}}
                ]}
                """;
        BalanceSnapshot snap = GeminiBillingProvider.fromJson(
                ProviderId.GEMINI_BILLING, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(700.0, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        BalanceSnapshot.MetricBlock cost = snap.metricOf(ProviderCapability.COST);
        assertNotNull(cost);
        assertEquals(700.0, cost.amount(), 1e-9);
        boolean hasBudget = snap.details().stream().anyMatch(d -> d.label().equals("月度预算"));
        boolean hasProject = snap.details().stream().anyMatch(d -> d.label().equals("项目 A 预算"));
        assertEquals(true, hasBudget);
        assertEquals(true, hasProject);
    }

    @Test
    void fromJsonNoBudgetsReportsZero() throws Exception {
        BalanceSnapshot snap = GeminiBillingProvider.fromJson(
                ProviderId.GEMINI_BILLING, "a", "n", MAPPER.readTree("{\"budgets\": []}"));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(0.0, snap.primaryAmount(), 1e-9);
        assertEquals("预算数量", snap.primaryLabel());
    }
}
