package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VastProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesDocumentedUserResponse() throws Exception {
        String json = """
                {"id":123456,"email":"you@example.com","credit":25.00}
                """;
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = VastProvider.fromJson(
                ProviderId.VAST, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(25.0, snap.primaryAmount(), 1e-9);
        assertEquals("可用余额", snap.primaryLabel());
        assertEquals("USD", snap.primaryCurrency());
    }

    @Test
    void fallsBackToBalanceFieldWhenCreditMissing() throws Exception {
        String json = """
                {"id":123456,"email":"you@example.com","balance":42.5}
                """;
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = VastProvider.fromJson(
                ProviderId.VAST, "a", "n", node).build();
        assertEquals(42.5, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
    }

    @Test
    void missingBalanceYieldsNullAmount() throws Exception {
        String json = "{\"id\":123456,\"email\":\"you@example.com\"}";
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = VastProvider.fromJson(
                ProviderId.VAST, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNull(snap.primaryAmount());
    }
}