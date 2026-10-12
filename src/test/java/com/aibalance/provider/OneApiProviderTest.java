package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneApiProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void convertsQuotaToUsd() throws Exception {
        String body = """
                {"success": true, "message": "",
                 "data": {"id": 1, "username": "u", "quota": 2000000,
                          "usedQuota": 500000, "requestCount": 120}}
                """;
        BalanceSnapshot snap = OneApiProvider.fromJson(
                ProviderId.ONEAPI, "a1", "网关", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // 2000000 / 500000 = 4.0 USD
        assertEquals(4.0, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("已用配额") && l.value().equals("$ 1.00")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("累计请求") && l.value().equals("120 次")));
    }

    @Test
    void missingDataYieldsError() throws Exception {
        BalanceSnapshot snap = OneApiProvider.fromJson(
                ProviderId.ONEAPI, "a1", "网关", MAPPER.readTree("{\"success\": false}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
