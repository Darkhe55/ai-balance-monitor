package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeGoProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesOfficialUsageWindows() throws Exception {
        String body = """
                {
                  "usage": {
                    "rolling": { "status": "ok", "percent": 23.4, "resetsAt": "2026-10-13T08:00:00.000Z" },
                    "weekly":  { "status": "ok", "percent": 12.0, "resetsAt": "2026-10-19T08:00:00.000Z" },
                    "monthly": { "status": "ok", "percent": 8.5,  "resetsAt": "2026-11-01T08:00:00.000Z" }
                  }
                }
                """;
        BalanceSnapshot snap = OpenCodeGoProvider.parseUsage(
                ProviderId.OPENCODE_GO, "a1", "Go", MAPPER.readTree(body).get("usage"));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // 主展示 = 最近窗口（rolling）剩余比例 100 - 23.4
        assertEquals(76.6, snap.primaryAmount(), 1e-9);
        assertEquals(76.6, snap.percent(), 1e-9);
        assertNotNull(snap.metricOf(ProviderCapability.QUOTA));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("5小时窗口")
                && d.value().equals("剩余 76.6%")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("每周窗口")
                && d.value().equals("剩余 88.0%")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("每月窗口")
                && d.value().equals("剩余 91.5%")));
        // 重置时间明细
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("5小时窗口重置")));
    }

    @Test
    void missingUsageReturnsError() throws Exception {
        BalanceSnapshot snap = OpenCodeGoProvider.parseUsage(
                ProviderId.OPENCODE_GO, "a1", "Go", MAPPER.readTree("{}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void emptyUsageReturnsError() throws Exception {
        BalanceSnapshot snap = OpenCodeGoProvider.parseUsage(
                ProviderId.OPENCODE_GO, "a1", "Go", MAPPER.readTree("{\"usage\":{}}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}