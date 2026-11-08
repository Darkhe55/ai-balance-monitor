package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FalProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesDocumentedBillingResponse() throws Exception {
        String json = """
                {"username":"my-team","credits":{"current_balance":24.5,"currency":"USD"}}
                """;
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = FalProvider.fromJson(
                ProviderId.FAL, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(24.5, snap.primaryAmount(), 1e-9);
        assertEquals("可用余额", snap.primaryLabel());
        assertEquals("USD", snap.primaryCurrency());
    }

    @Test
    void currencyDefaultsToUsdWhenMissing() throws Exception {
        String json = "{\"username\":\"my-team\"}";
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = FalProvider.fromJson(
                ProviderId.FAL, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals("USD", snap.primaryCurrency());
        assertNull(snap.primaryAmount());
    }

    @Test
    void nonUsdCurrencyIsPreserved() throws Exception {
        String json = "{\"username\":\"my-team\",\"credits\":{\"current_balance\":12,\"currency\":\"EUR\"}}";
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = FalProvider.fromJson(
                ProviderId.FAL, "a", "n", node).build();
        assertEquals(12.0, snap.primaryAmount(), 1e-9);
        assertEquals("EUR", snap.primaryCurrency());
    }
}