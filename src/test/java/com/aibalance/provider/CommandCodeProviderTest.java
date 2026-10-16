package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCodeProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesCreditsAndWindowLimits() throws Exception {
        String credits = """
                {
                  "credits": {
                    "monthlyCredits": 10.0,
                    "purchasedCredits": 5.0,
                    "freeCredits": 5.0,
                    "planId": "individual-go-v1"
                  },
                  "windowLimits": {
                    "limited": false,
                    "fiveHour": { "used": 0.0, "cap": 100.0, "resetAt": 0, "exceeded": false },
                    "weekly":  { "used": 0.0, "cap": 200.0, "resetAt": 0, "exceeded": false }
                  }
                }
                """;
        String subscriptions = """
                {"data": {"currentPeriodEnd": "2026-11-16T09:35:48.000Z", "planId": "individual-go-v1"}}
                """;
        BalanceSnapshot snap = CommandCodeProvider.parseBilling(
                ProviderId.COMMANDCODE, "a1", "cc",
                MAPPER.readTree(credits), MAPPER.readTree(subscriptions), null);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(10.0, snap.primaryAmount(), 1e-9);
        assertNotNull(snap.metricOf(ProviderCapability.QUOTA));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("已购 Credits")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("5小时窗口")
                && d.value().equals("剩余 100.0%")));
        // resetAt=0 时不显示重置时间
        assertTrue(snap.details().stream().noneMatch(d -> d.label().equals("5小时窗口重置")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("周期结束")));
    }

    @Test
    void missingCreditsStillBuildsOkSnapshot() throws Exception {
        BalanceSnapshot snap = CommandCodeProvider.parseBilling(
                ProviderId.COMMANDCODE, "a1", "cc", MAPPER.readTree("{}"), null, null);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(null, snap.primaryAmount());
    }

    @Test
    void parsesUsageSummary() throws Exception {
        String credits = """
                {"credits": {"monthlyCredits": 10.0}}
                """;
        String summary = """
                {"totalCost": 1.25, "totalCount": 42}
                """;
        BalanceSnapshot snap = CommandCodeProvider.parseBilling(
                ProviderId.COMMANDCODE, "a1", "cc", MAPPER.readTree(credits), null, MAPPER.readTree(summary));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("本期已用金额")
                && d.value().equals("$1.25")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("本期调用次数")
                && d.value().equals("42 次")));
    }
}