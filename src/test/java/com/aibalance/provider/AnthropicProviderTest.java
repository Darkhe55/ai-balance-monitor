package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderTest {

    @Test
    void parsesQuotaFromHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200,
                Map.of("anthropic-ratelimit-tokens-limit", List.of("200000"),
                        "anthropic-ratelimit-tokens-remaining", List.of("180000"),
                        "anthropic-ratelimit-requests-limit", List.of("1000"),
                        "anthropic-ratelimit-requests-remaining", List.of("900")),
                "{\"data\": []}");

        BalanceSnapshot snap = AnthropicProvider.fromResponse(
                ProviderId.ANTHROPIC, "a1", "Claude", resp);

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(180000.0, snap.primaryAmount(), 1e-9);
        assertEquals("tokens", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("配额上限") && l.value().contains("200,000")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("请求配额剩余") && l.value().contains("900")));
    }

    @Test
    void handlesMissingHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200, Map.of(), "{}");
        BalanceSnapshot snap = AnthropicProvider.fromResponse(
                ProviderId.ANTHROPIC, "a1", "Claude", resp);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals("账户状态", snap.primaryLabel());
    }
}
