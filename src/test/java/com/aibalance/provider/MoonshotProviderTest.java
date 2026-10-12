package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoonshotProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesBalancePayload() throws Exception {
        String body = """
                {
                  "code": 0,
                  "data": {
                    "available_balance": "10.00",
                    "voucher_balance": "5.00",
                    "cash_balance": "5.00",
                    "temp_voucher_balance": "0",
                    "temp_cash_balance": "0",
                    "currency": "CNY"
                  }
                }
                """;
        BalanceSnapshot snap = MoonshotProvider.fromJson(
                ProviderId.MOONSHOT, "a1", "Kimi", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(10.00, snap.primaryAmount(), 1e-9);
        assertEquals("CNY", snap.primaryCurrency());
        assertEquals("可用余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("现金余额")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("代金券")));
    }

    @Test
    void hidesZeroVoucher() throws Exception {
        String body = """
                {"code": 0, "data": {"available_balance": 3.5, "voucher_balance": 0,
                 "cash_balance": 3.5, "currency": "CNY"}}
                """;
        BalanceSnapshot snap = MoonshotProvider.fromJson(
                ProviderId.MOONSHOT, "a1", "Kimi", MAPPER.readTree(body));
        assertTrue(snap.details().stream().noneMatch(l -> l.label().equals("代金券")));
    }

    @Test
    void missingDataYieldsError() throws Exception {
        BalanceSnapshot snap = MoonshotProvider.fromJson(
                ProviderId.MOONSHOT, "a1", "Kimi", MAPPER.readTree("{\"code\": 500}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
