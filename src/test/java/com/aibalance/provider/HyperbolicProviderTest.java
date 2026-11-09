package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HyperbolicProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesDocumentedBalanceResponse() throws Exception {
        String json = """
                {"balanceCents":12345,"maxOverdraftCents":5000}
                """;
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = HyperbolicProvider.fromJson(
                ProviderId.HYPERBOLIC, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(123.45, snap.primaryAmount(), 1e-9); // 12345 / 100 = $123.45
        assertEquals("可用余额", snap.primaryLabel());
        assertEquals("USD", snap.primaryCurrency());
    }

    @Test
    void centsToUsdConverts() {
        assertEquals(0.0, HyperbolicProvider.centsToUsd(0.0), 1e-9);
        assertEquals(1.0, HyperbolicProvider.centsToUsd(100.0), 1e-9);
        assertEquals(50.0, HyperbolicProvider.centsToUsd(5000.0), 1e-9);
        assertNull(HyperbolicProvider.centsToUsd(null));
    }

    @Test
    void missingBalanceCentsYieldsNullAmount() throws Exception {
        String json = "{\"maxOverdraftCents\":5000}";
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = HyperbolicProvider.fromJson(
                ProviderId.HYPERBOLIC, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNull(snap.primaryAmount());
    }
}