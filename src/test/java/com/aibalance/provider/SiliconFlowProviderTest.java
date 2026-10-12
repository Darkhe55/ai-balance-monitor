package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiliconFlowProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesUserInfoPayload() throws Exception {
        String body = """
                {"id": "u_123", "name": "demo", "email": "u@example.com",
                 "balance": "1.2345", "total_balance": "10.00", "status": "ACTIVE",
                 "createdAt": "2025-01-01T00:00:00Z"}
                """;
        BalanceSnapshot snap = SiliconFlowProvider.fromJson(
                ProviderId.SILICONFLOW, "a1", "硅基", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(1.2345, snap.primaryAmount(), 1e-9);
        assertEquals("CNY", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("账户状态") && l.value().equals("ACTIVE")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("总额度")));
    }

    @Test
    void handlesMissingBalance() throws Exception {
        BalanceSnapshot snap = SiliconFlowProvider.fromJson(
                ProviderId.SILICONFLOW, "a1", "硅基", MAPPER.readTree("{\"status\": \"INACTIVE\"}"));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(null, snap.primaryAmount());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("账户状态") && l.value().equals("INACTIVE")));
    }
}
