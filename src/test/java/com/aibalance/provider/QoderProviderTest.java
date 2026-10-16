package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QoderProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parseUserStatusParsesCredits() throws Exception {
        String body = """
                {
                  "loggedIn": true,
                  "totalCredits": 1500,
                  "totalCharged": 500,
                  "currentPlan": {"name": "Pro"}
                }
                """;
        BalanceSnapshot snap = QoderProvider.parseUserStatus(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(1500.0, snap.primaryAmount(), 1e-9);
        assertNotNull(snap.metricOf(com.aibalance.model.ProviderCapability.QUOTA));
    }

    @Test
    void parseUserStatusWrapsDataNode() throws Exception {
        String body = """
                {"data": {"totalCredits": 800, "totalCharged": 200}}
                """;
        BalanceSnapshot snap = QoderProvider.parseUserStatus(
                ProviderId.QODER_INTL, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(800.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void parseUserStatusMissingCreditsReturnsError() throws Exception {
        String body = """
                {"loggedIn": true}
                """;
        BalanceSnapshot snap = QoderProvider.parseUserStatus(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void parseTeamsUsageParsesQuota() throws Exception {
        String body = """
                {
                  "remainingCredits": 150.5,
                  "totalCredits": 500.0
                }
                """;
        BalanceSnapshot snap = QoderProvider.parseTeamsUsage(
                ProviderId.QODER_CN, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(150.5, snap.primaryAmount(), 1e-9);
    }
}
