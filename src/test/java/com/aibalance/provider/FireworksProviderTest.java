package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FireworksProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesDocumentedBillingSummary() throws Exception {
        String json = """
                {"lineItems":[
                  {"category":"Text Completion","groupingKey":"model","groupingValue":"llama-3.3-70b-instruct",
                   "totalCost":{"currencyCode":"USD","units":1,"nanos":250000000}},
                  {"category":"Text Completion","groupingKey":"model","groupingValue":"qwen2.5-72b",
                   "totalCost":{"currencyCode":"USD","units":0,"nanos":750000000}}
                ]}
                """;
        var root = MAPPER.readTree(json);
        var builder = BalanceSnapshot.ok(ProviderId.FIREWORKS, "a", "n");
        FireworksProvider.mergeSummary(builder, root);
        BalanceSnapshot snap = builder.build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // $1.25 + $0.75 = $2.00
        assertEquals(2.0, snap.metricOf(ProviderCapability.COST).amount(), 1e-9);
        assertEquals("本月费用", snap.metricOf(ProviderCapability.COST).label());
        assertEquals("USD", snap.metricOf(ProviderCapability.COST).currency());
    }

    @Test
    void emptySummaryYieldsZeroCost() throws Exception {
        var root = MAPPER.readTree("{}");
        var builder = BalanceSnapshot.ok(ProviderId.FIREWORKS, "a", "n");
        FireworksProvider.mergeSummary(builder, root);
        BalanceSnapshot snap = builder.build();
        assertEquals(0.0, snap.metricOf(ProviderCapability.COST).amount(), 1e-9);
    }

    @Test
    void moneyValueCombinesUnitsAndNanos() throws Exception {
        var money = MAPPER.readTree("{\"currencyCode\":\"USD\",\"units\":1,\"nanos\":250000000}");
        assertEquals(1.25, FireworksProvider.moneyValue(money), 1e-9);
        var zero = MAPPER.readTree("{\"currencyCode\":\"USD\",\"units\":0,\"nanos\":-500000000}");
        assertEquals(-0.5, FireworksProvider.moneyValue(zero), 1e-9);
        assertNull(FireworksProvider.moneyValue(MAPPER.readTree("null")));
        assertNull(FireworksProvider.moneyValue(MAPPER.readTree("{}")));
    }
}