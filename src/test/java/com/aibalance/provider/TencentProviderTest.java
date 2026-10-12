package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TencentProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void signedRequestHasTc3Headers() {
        TencentProvider.SignedRequest req = TencentProvider.buildSignedRequest(
                "test-sid", "test-skey", "https://billing.tencentcloudapi.com");
        assertTrue(req.url.startsWith("https://billing.tencentcloudapi.com/"));
        assertEquals("{}", req.body);
        assertEquals("DescribeAccountBalance", req.headers.get("X-TC-Action"));
        assertEquals("2018-07-09", req.headers.get("X-TC-Version"));
        assertTrue(req.headers.get("X-TC-Timestamp").matches("\\d{10}"));
        assertEquals("application/json; charset=utf-8", req.headers.get("Content-Type"));
        String auth = req.headers.get("Authorization");
        assertTrue(auth.startsWith("TC3-HMAC-SHA256 Credential=test-sid/"));
        // Credential Scope 服务名必须是 billing（腾讯云计费服务），并含 tc3_request 后缀
        assertTrue(auth.contains("/billing/tc3_request"));
        assertTrue(auth.contains("SignedHeaders=content-type;host"));
        String sig = auth.substring(auth.indexOf("Signature=") + 10);
        assertEquals(64, sig.length());
    }

    /**
     * 用固定时间戳独立重算整个 TC3 签名流程，证明签名链路（而非仅结构）正确：
     * canonical request（POST / 空 query、content-type;host、对 "{}" 的哈希）、
     * 时间戳/UTC 日期同源、服务名 billing、SecretKey 仅参与 HMAC 且不出现于请求头。
     */
    @Test
    void signatureMatchesIndependentRecalculation() {
        long ts = 1791850000L;
        String secretId = "AKIDtest-only";
        String secretKey = "skey-abc-def-123";
        TencentProvider.SignedRequest req = TencentProvider.buildSignedRequestAt(
                secretId, secretKey, "https://billing.tencentcloudapi.com", ts);

        // 独立重算
        String date = java.time.Instant.ofEpochSecond(ts)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String host = "billing.tencentcloudapi.com";
        String body = "{}";
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + SignUtil.sha256Hex(body);
        String scope = date + "/billing/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + ts + "\n" + scope + "\n"
                + SignUtil.sha256Hex(canonicalRequest);
        byte[] kDate = SignUtil.hmacSha256("TC3" + secretKey, date);
        byte[] kService = SignUtil.hmacSha256(kDate, "billing");
        byte[] kSigning = SignUtil.hmacSha256(kService, "tc3_request");
        String expectSignature = SignUtil.toHex(SignUtil.hmacSha256(kSigning, stringToSign));

        String auth = req.headers.get("Authorization");
        String expectedAuth = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + expectSignature;
        assertEquals(expectedAuth, auth);
        // 时间戳头与签名 Credential 日期同源（UTC 日期由同一 timestamp 派生）
        assertTrue(auth.contains("/" + date + "/billing/tc3_request"));
        assertEquals(String.valueOf(ts), req.headers.get("X-TC-Timestamp"));
        // SecretKey 不出现在任何请求头
        assertTrue(req.headers.values().stream().noneMatch(v -> v.contains(secretKey)));
    }

    @Test
    void fromJsonParsesBalanceFenToYuan() throws Exception {
        String body = """
                {"Response":{"Balance":12345,"CashAccountBalance":10000,
                             "CreditAmount":10000000000,"FreezeAmount":0,
                             "OweAmount":0,"RequestId":"r1"}}
                """;
        BalanceSnapshot snap = TencentProvider.fromJson(
                ProviderId.TENCENT, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(123.45, snap.primaryAmount(), 1e-9);
        BalanceSnapshot.MetricBlock bal = snap.metricOf(ProviderCapability.BALANCE);
        assertNotNull(bal);
        assertEquals("CNY", bal.currency());
        // 明细：现金余额 / 信用额度 / 冻结 / 欠费
        boolean hasCash = snap.details().stream().anyMatch(d -> d.label().contains("现金"));
        boolean hasCredit = snap.details().stream().anyMatch(d -> d.label().contains("信用额度"));
        boolean hasFreeze = snap.details().stream().anyMatch(d -> d.label().contains("冻结"));
        boolean hasOwe = snap.details().stream().anyMatch(d -> d.label().contains("欠费"));
        assertTrue(hasCash && hasCredit && hasFreeze && hasOwe);
    }

    @Test
    void fromJsonMapsError() throws Exception {
        String body = """
                {"Response":{"Error":{"Code":"AuthFailure.SignatureFailure","Message":"sig bad"}}}
                """;
        BalanceSnapshot snap = TencentProvider.fromJson(
                ProviderId.TENCENT, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertTrue(snap.message().contains("AuthFailure"));
    }
}
