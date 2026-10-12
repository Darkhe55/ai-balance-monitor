package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void signedRequestHasBceAuth() {
        BaiduProvider.SignedRequest req = BaiduProvider.buildSignedRequest(
                "test-ak", "test-sk", "https://billing.baidubce.com");
        assertEquals("https://billing.baidubce.com/v1/finance/cash/balance", req.url);
        assertEquals("{}", req.body);
        String auth = req.headers.get("Authorization");
        assertTrue(auth.startsWith("bce-auth-v1/test-ak/"));
        assertTrue(auth.contains("/1800/host;x-bce-date/"));
        String sig = auth.substring(auth.lastIndexOf('/') + 1);
        assertEquals(64, sig.length());
        // x-bce-date 头存在且 UTC 格式
        String date = req.headers.get("x-bce-date");
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
    }

    @Test
    void fromJsonParsesCashBalance() throws Exception {
        String body = "{\"cashBalance\":88.66}";
        BalanceSnapshot snap = BaiduProvider.fromJson(
                ProviderId.BAIDU, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(88.66, snap.primaryAmount(), 1e-9);
        BalanceSnapshot.MetricBlock bal = snap.metricOf(ProviderCapability.BALANCE);
        assertNotNull(bal);
        assertEquals("CNY", bal.currency());
    }

    @Test
    void fromJsonMissingFieldIsError() throws Exception {
        String body = "{\"message\":\"no data\"}";
        BalanceSnapshot snap = BaiduProvider.fromJson(
                ProviderId.BAIDU, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
