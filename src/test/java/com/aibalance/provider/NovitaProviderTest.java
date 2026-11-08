package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NovitaProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesDocumentedBalanceResponse() throws Exception {
        String json = """
                {"availableBalance":"1000000","cashBalance":"800000","creditLimit":"200000",
                 "pendingCharges":"0","outstandingInvoices":"0"}
                """;
        var node = MAPPER.readTree(json);
        BalanceSnapshot snap = NovitaProvider.fromJson(
                ProviderId.NOVITA, "a", "n", node).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(100.0, snap.primaryAmount(), 1e-9); // 1000000 / 10000 = $100.00
        assertEquals("可用余额", snap.primaryLabel());
        assertEquals("USD", snap.primaryCurrency());
    }

    @Test
    void usdValueConvertsTenThousandths() throws Exception {
        // 10000 = $1.00
        assertEquals(1.0, NovitaProvider.usdValue(MAPPER.readTree("\"10000\"")), 1e-9);
        assertEquals(0.0, NovitaProvider.usdValue(MAPPER.readTree("\"0\"")), 1e-9);
        assertEquals(123.4567, NovitaProvider.usdValue(MAPPER.readTree("\"1234567\"")), 1e-9);
    }

    @Test
    void usdValueHandlesNullOrNonNumeric() throws Exception {
        assertNull(NovitaProvider.usdValue(null));
        assertNull(NovitaProvider.usdValue(MAPPER.readTree("null")));
        assertNull(NovitaProvider.usdValue(MAPPER.readTree("\"abc\"")));
    }
}