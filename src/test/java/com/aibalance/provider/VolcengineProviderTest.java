package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolcengineProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void signedUrlAndAuthHeaders() {
        VolcengineProvider.Signing s = VolcengineProvider.buildSignedUrl(
                "test-ak", "test-sk", "https://billing.volcengineapi.com");
        assertTrue(s.url.startsWith("https://billing.volcengineapi.com/?"));
        assertTrue(s.url.contains("Action=QueryBalanceAcct"));
        assertTrue(s.url.contains("Version=2022-01-01"));
        // HMAC-SHA256 认证头格式
        assertTrue(s.authorization.startsWith("HMAC-SHA256 Credential=test-ak/"));
        assertTrue(s.authorization.contains("/cn-beijing/billing/request"));
        assertTrue(s.authorization.contains("SignedHeaders=content-type;host;x-content-sha256;x-date"));
        assertTrue(s.authorization.contains("Signature="));
        // X-Date 格式 yyyyMMdd'T'HHmmss'Z'
        assertTrue(s.xDate.matches("\\d{8}T\\d{6}Z"));
        // 签名长度 = 64 hex
        String sig = s.authorization.substring(s.authorization.indexOf("Signature=") + 10);
        assertEquals(64, sig.length());
    }

    @Test
    void fromJsonParsesBalance() throws Exception {
        String body = """
                {"ResponseMetadata":{"RequestId":"r1"},
                 "Result":{"AccountID":1001,"AvailableBalance":123.45,"CashBalance":100.0,
                           "ArrearsBalance":0.0,"FreezeAmount":5.0,"CreditLimit":50.0,
                           "Currency":"CNY"}}
                """;
        BalanceSnapshot snap = VolcengineProvider.fromJson(
                ProviderId.VOLCENGINE, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(123.45, snap.primaryAmount(), 1e-9);
        BalanceSnapshot.MetricBlock bal = snap.metricOf(ProviderCapability.BALANCE);
        assertNotNull(bal);
        boolean hasArrears = snap.details().stream().anyMatch(d -> d.label().contains("欠费"));
        assertTrue(hasArrears);
    }

    @Test
    void fromJsonMapsError() throws Exception {
        String body = """
                {"ResponseMetadata":{"Error":{"Code":"InvalidAction","Message":"bad"}}}
                """;
        BalanceSnapshot snap = VolcengineProvider.fromJson(
                ProviderId.VOLCENGINE, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertTrue(snap.message().contains("InvalidAction"));
    }
}
