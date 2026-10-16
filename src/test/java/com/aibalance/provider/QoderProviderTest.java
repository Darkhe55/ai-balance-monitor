package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QoderProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parseUsageReadsUserQuota() throws Exception {
        String body = """
                {
                  "userQuota": {"total": 5000, "used": 1200, "remaining": 3800,
                                 "percentage": 24, "unit": "credits", "available": true},
                  "addOnQuota": {"total": 100, "used": 20, "remaining": 80, "unit": "credits"},
                  "totalUsagePercentage": 24,
                  "isQuotaExceeded": false
                }
                """;
        BalanceSnapshot snap = QoderProvider.parseUsage(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body), null, false);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(3800.0, snap.primaryAmount(), 1e-9);
        assertEquals(76.0, snap.percent(), 1e-9);
        assertNotNull(snap.metricOf(ProviderCapability.QUOTA));
        assertEquals(true, snap.details().stream().anyMatch(l -> l.label().equals("赠额 Credits")));
    }

    @Test
    void parseUsagePrefersOrgResourcePackageInOrgMode() throws Exception {
        String body = """
                {
                  "userQuota": {"total": 100, "used": 50, "remaining": 50, "unit": "credits"},
                  "orgResourcePackage": {"total": 2000, "used": 500, "remaining": 1500, "unit": "credits"}
                }
                """;
        BalanceSnapshot snap = QoderProvider.parseUsage(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body), null, true);
        assertEquals(1500.0, snap.primaryAmount(), 1e-9);
        assertEquals(75.0, snap.percent(), 1e-9);
    }

    @Test
    void parseUsageMissingQuotaKeepsNullAmount() throws Exception {
        String body = """
                {"userQuota": {}, "totalUsagePercentage": 0}
                """;
        BalanceSnapshot snap = QoderProvider.parseUsage(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body), null, false);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNull(snap.primaryAmount());
    }

    @Test
    void parseUsageReadsPlan() throws Exception {
        String usage = """
                {"userQuota": {"total": 5000, "used": 0, "remaining": 5000, "unit": "credits"}}
                """;
        String plan = """
                {"userType": "personal", "planTierName": "Pro", "endDate": "2026-11-01T00:00:00Z"}
                """;
        BalanceSnapshot snap = QoderProvider.parseUsage(
                ProviderId.QODER_INTL, "a", "n", MAPPER.readTree(usage), MAPPER.readTree(plan), false);
        assertEquals("Pro", snap.details().stream()
                .filter(l -> l.label().equals("当前套餐")).findFirst().orElseThrow().value());
    }
}