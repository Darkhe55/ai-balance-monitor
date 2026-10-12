package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KimiCodingProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesFiveHourAndWeeklyWindows() throws Exception {
        String body = """
                {"limits": [{"duration": 300, "limit": "1000000", "used": "400000",
                             "remaining": "600000"}],
                 "usage": {"limit": "5000000", "used": "1200000", "remaining": "3800000"}}
                """;
        BalanceSnapshot snap = KimiCodingProvider.fromJson(
                "k1", "Kimi Coding", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(ProviderId.KIMI_CODING, snap.providerId());
        // 5 小时窗口：used/limit = 40% → 剩余 60%
        assertEquals(60.0, snap.primaryAmount(), 1e-9);
        assertEquals("%", snap.primaryCurrency());
        assertEquals("Coding 套餐额度", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("5 小时窗口") && l.value().contains("40%")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("周窗口") && l.value().contains("24%")));
    }

    @Test
    void numericAndRemainingFallback() throws Exception {
        String body = """
                {"limits": [{"duration": 300, "limit": 1000, "remaining": 250}],
                 "usage": {"limit": 4000, "used": 1000}}
                """;
        BalanceSnapshot snap = KimiCodingProvider.fromJson(
                "k2", "Kimi Coding", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // remaining=250 → used=750 → 75% → 剩余 25%
        assertEquals(25.0, snap.primaryAmount(), 1e-9);
        // 周窗口 1000/4000 = 25%
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("周窗口") && l.value().contains("25%")));
    }

    @Test
    void noWindowsReportsError() throws Exception {
        BalanceSnapshot snap = KimiCodingProvider.fromJson(
                "k3", "Kimi Coding", MAPPER.readTree("{\"code\": 401, \"msg\": \"auth fail\"}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
