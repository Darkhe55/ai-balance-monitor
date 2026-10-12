package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.BillingMode;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnisoundProviderTest {

    @Test
    void keyValidWithoutQuotaHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200, Map.of(), "{}");
        BalanceSnapshot snap = UnisoundProvider.fromOk(ProviderId.UNISOUND, "a1", "主账户", resp, BillingMode.PAY_AS_YOU_GO);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals("账户状态", snap.primaryLabel());
        assertTrue(snap.message().contains("按量付费 Key 有效"));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("计费形态") && l.value().contains("按量付费")));
    }

    @Test
    void subscriptionModeLabelsTokenPlan() {
        HttpSupport.Response resp = new HttpSupport.Response(200, Map.of(), "{}");
        BalanceSnapshot snap = UnisoundProvider.fromOk(ProviderId.UNISOUND, "a1", "套餐账户", resp, BillingMode.SUBSCRIPTION);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertTrue(snap.message().contains("Token Plan 套餐 Key 有效"));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("计费形态") && l.value().contains("tp-")));
    }

    @Test
    void readsRateLimitHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200, Map.of(
                "x-ratelimit-remaining-tokens", List.of("500000"),
                "x-ratelimit-limit-tokens", List.of("600000")), "{}");
        BalanceSnapshot snap = UnisoundProvider.fromOk(ProviderId.UNISOUND, "a1", "主账户", resp, BillingMode.PAY_AS_YOU_GO);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(500000.0, snap.primaryAmount(), 1e-9);
        assertEquals("tokens", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("配额上限") && l.value().contains("600,000")));
    }

    @Test
    void normalizeDefaultBase() {
        assertEquals("https://maas-api.unisound.com/v1", UnisoundProvider.normalize(null));
        assertEquals("https://maas-api.unisound.com/v1", UnisoundProvider.normalize("https://maas-api.unisound.com/v1/"));
    }
}
