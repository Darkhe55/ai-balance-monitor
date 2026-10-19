package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelScopeProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesMagicubeBalance() throws Exception {
        String body = """
                {"success":true,"data":{"total_balance":281,"available_balance":281,"frozen_amount":0}}
                """;
        BalanceSnapshot snap = ModelScopeProvider.fromJson("a1", "魔搭", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(281.0, snap.primaryAmount(), 1e-9);
        assertEquals("魔粒", snap.primaryCurrency());
        assertEquals("魔粒余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("可用魔粒")
                && d.value().equals("281")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("已用魔粒")
                && d.value().equals("0")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("冻结魔粒")
                && d.value().equals("0")));
        assertTrue(snap.capabilities().contains(ProviderCapability.BALANCE));
    }

    @Test
    void parsesUsedAndFrozen() throws Exception {
        String body = """
                {"success":true,"data":{"total_balance":100,"available_balance":80,"frozen_amount":5}}
                """;
        BalanceSnapshot snap = ModelScopeProvider.fromJson("a1", "魔搭", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(100.0, snap.primaryAmount(), 1e-9);
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("可用魔粒")
                && d.value().equals("80")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("已用魔粒")
                && d.value().equals("20")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("冻结魔粒")
                && d.value().equals("5")));
    }

    @Test
    void missingTotalBalanceIsError() throws Exception {
        String body = """
                {"success":true,"data":{"available_balance":1,"frozen_amount":0}}
                """;
        BalanceSnapshot snap = ModelScopeProvider.fromJson("a1", "魔搭", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertNotNull(snap.message());
        assertTrue(snap.message().contains("total_balance"));
    }
}