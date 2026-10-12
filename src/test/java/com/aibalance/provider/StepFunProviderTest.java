package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepFunProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesOfficialAccountsPayload() throws Exception {
        String body = """
                {
                  "object": "account",
                  "type": "prepaid",
                  "balance": 13.57,
                  "total_cash_balance": 100.00,
                  "total_voucher_balance": 26.00
                }
                """;
        BalanceSnapshot snap = StepFunProvider.fromJson(
                ProviderId.STEPFUN, "a1", "主账户", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(13.57, snap.primaryAmount(), 1e-9);
        assertEquals("CNY", snap.primaryCurrency());
        assertEquals("账户余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("累计充值") && l.value().equals("100.00 CNY")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("剩余赠送") && l.value().equals("26.00 CNY")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("账户类型") && l.value().equals("预付费")));
    }

    @Test
    void handlesPostpaidType() throws Exception {
        String body = """
                {"object": "account", "type": "postpaid", "balance": 0.0,
                 "total_cash_balance": 0.0, "total_voucher_balance": 0.0}
                """;
        BalanceSnapshot snap = StepFunProvider.fromJson(
                ProviderId.STEPFUN, "a1", "主账户", MAPPER.readTree(body));
        assertEquals(0.0, snap.primaryAmount(), 1e-9);
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("账户类型") && l.value().equals("后付费")));
    }

    @Test
    void handlesMissingFields() throws Exception {
        BalanceSnapshot snap = StepFunProvider.fromJson(
                ProviderId.STEPFUN, "a1", "主账户", MAPPER.readTree("{\"object\":\"account\"}"));
        assertNull(snap.primaryAmount());
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
    }
}
