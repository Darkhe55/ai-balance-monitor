package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStudioTokenPlanProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * 用阿里云官方 V3 签名机制文档给出的固定示例向量独立验证整个 ACS3 签名链路
     * （POST /、ImageId/RegionId 查询串、空请求体、固定 date/nonce）：
     * 官方文档公布 hashedCanonicalRequest = 7ea06492…e259、
     * Signature = 06563a9e…83c0，必须完全一致，证明实现符合官方协议而非自造格式。
     */
    @Test
    void signatureMatchesOfficialAlibabaCloudVector() {
        String ak = "YourAccessKeyId";
        String sk = "YourAccessKeySecret";
        String nonce = "3156853299f313e23d1673dc12e1703d";
        String date = "2023-10-26T10:22:32Z";
        ModelStudioTokenPlanProvider.SignedRequest req = ModelStudioTokenPlanProvider.signRequest(
                "POST", ak, sk, "ecs.cn-shanghai.aliyuncs.com", "2014-05-26", "/",
                "RunInstances",
                Map.of("ImageId", "win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd",
                        "RegionId", "cn-shanghai"),
                date, nonce);

        // 独立重算 canonical request
        String payloadHash = SignUtil.sha256Hex("");
        String canonicalHeaders = "host:ecs.cn-shanghai.aliyuncs.com\n"
                + "x-acs-action:RunInstances\n"
                + "x-acs-content-sha256:" + payloadHash + "\n"
                + "x-acs-date:" + date + "\n"
                + "x-acs-signature-nonce:" + nonce + "\n"
                + "x-acs-version:2014-05-26\n";
        String signedHeaders = "host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version";
        String canonicalRequest = "POST\n/\n"
                + "ImageId=win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd&RegionId=cn-shanghai\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String hashedCanonical = SignUtil.sha256Hex(canonicalRequest);
        // 官方文档公布的值
        assertEquals("7ea06492da5221eba5297e897ce16e55f964061054b7695beedaac1145b1e259", hashedCanonical);

        String stringToSign = "ACS3-HMAC-SHA256\n" + hashedCanonical;
        String expectSignature = SignUtil.toHex(SignUtil.hmacSha256(sk, stringToSign));
        // 官方文档公布的签名值
        assertEquals("06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0", expectSignature);

        String auth = req.headers.get("Authorization");
        String expectedAuth = "ACS3-HMAC-SHA256 Credential=YourAccessKeyId,"
                + "SignedHeaders=" + signedHeaders + ",Signature=" + expectSignature;
        assertEquals(expectedAuth, auth);
        // Secret 只参与 HMAC，不出现于任何请求头
        assertTrue(req.headers.values().stream().noneMatch(v -> v.contains("YourAccessKeySecret")));
    }

    @Test
    void getSignedRequestHasAcs3Headers() {
        ModelStudioTokenPlanProvider.SignedRequest req =
                ModelStudioTokenPlanProvider.buildSignedRequestAt(
                        "test-ak", "test-sk", "https://modelstudio.cn-beijing.aliyuncs.com",
                        "/tokenplan/subscription/shared-packages", "ListSubscriptionSharedPackages",
                        Map.of("PageNo", "1", "PageSize", "50"),
                        "2026-01-01T12:00:00Z", "3e3b2a9f0c7d4f8e9a1b2c3d4e5f6a7b");
        assertTrue(req.url.startsWith("https://modelstudio.cn-beijing.aliyuncs.com/tokenplan/subscription/shared-packages?"));
        assertEquals("ListSubscriptionSharedPackages", req.headers.get("x-acs-action"));
        assertEquals("2026-02-10", req.headers.get("x-acs-version"));
        assertEquals("2026-01-01T12:00:00Z", req.headers.get("x-acs-date"));
        assertEquals("3e3b2a9f0c7d4f8e9a1b2c3d4e5f6a7b", req.headers.get("x-acs-signature-nonce"));
        assertEquals(SignUtil.sha256Hex(""), req.headers.get("x-acs-content-sha256"));
        String auth = req.headers.get("Authorization");
        assertTrue(auth.startsWith("ACS3-HMAC-SHA256 Credential=test-ak,"));
        assertTrue(auth.contains("SignedHeaders=host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version"));
        String sig = auth.substring(auth.indexOf("Signature=") + 10);
        assertEquals(64, sig.length());
        // 签名随 nonce/date 变化（防重放，两次请求签名不同）
        ModelStudioTokenPlanProvider.SignedRequest req2 =
                ModelStudioTokenPlanProvider.buildSignedRequestAt(
                        "test-ak", "test-sk", "https://modelstudio.cn-beijing.aliyuncs.com",
                        "/tokenplan/subscription/shared-packages", "ListSubscriptionSharedPackages",
                        Map.of("PageNo", "1", "PageSize", "50"),
                        "2026-01-01T12:00:00Z", "other-nonce-0000");
        assertTrue(!req.headers.get("Authorization").equals(req2.headers.get("Authorization")));
    }

    /** 查询共享包明细（官方返回示例结构）：CREDITS 权益 → QUOTA 进度条（剩余 40/总额 100）。 */
    @Test
    void fromJsonParsesSharedPackages() throws Exception {
        String body = """
                {"Success": true, "Data": {
                   "Items": [
                     {"InstanceCode": "subs-1234567", "Status": "NORMAL",
                      "EquityList": [
                        {"EquityType": "CREDITS", "CycleInstanceId": "123456",
                         "CycleStartTime": 1775232000, "CycleEndTime": 1756310400,
                         "CycleTotalValue": 100, "CycleSurplusValue": 40, "CycleVersion": 1}
                      ]}
                   ], "Total": 1, "PageNo": 1, "PageSize": 10
                 }}
                """;
        BalanceSnapshot snap = ModelStudioTokenPlanProvider.fromJson(
                ProviderId.BAILIAN_TOKENPLAN, "b1", "百炼TokenPlan",
                List.of(MAPPER.readTree(body)));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(40.0, snap.primaryAmount(), 1e-9);
        assertEquals("Token Plan 剩余 Credits", snap.primaryLabel());
        assertEquals(40.0, snap.percent(), 1e-9);
        BalanceSnapshot.MetricBlock q = snap.metricOf(ProviderCapability.QUOTA);
        assertNotNull(q);
        assertEquals(40.0, q.amount(), 1e-9);
        assertEquals(40.0, q.percent(), 1e-9);
        assertTrue(q.label().startsWith("共享包 "));
        assertTrue(q.note().contains("总额 100 Credits"));
        // 周期/状态等明细挂在 QUOTA 区块内部
        assertTrue(q.details().stream().anyMatch(d -> d.label().equals("周期开始")
                && d.value().contains("2026")));
        assertTrue(q.details().stream().anyMatch(d -> d.label().equals("状态")
                && d.value().equals("有效")));
    }

    /** 查询订阅席位明细：多席位各自一条进度条，毫秒级时间戳正确格式化，成员/到期时间进明细。 */
    @Test
    void fromJsonParsesSeatDetails() throws Exception {
        String body = """
                {"Success": true, "Data": {
                   "Items": [
                     {"InstanceCode": "subs-1", "SeatId": "seat_0001",
                      "SpecType": "standard", "AssignedStatus": "ASSIGNED",
                      "AccountName": "张三", "AccountEmail": "zhangsan@example.com",
                      "StartTime": 1788328800000, "EndTime": 1790920800000, "Status": "NORMAL",
                      "EquityList": [
                        {"EquityType": "CREDITS", "CycleInstanceId": "c1",
                         "CycleStartTime": 1788328800000, "CycleEndTime": 1790920800000,
                         "CycleTotalValue": 25000, "CycleSurplusValue": 15000, "CycleVersion": 1}
                      ]},
                     {"InstanceCode": "subs-2", "SeatId": "seat_0002",
                      "SpecType": "pro", "AssignedStatus": "UNASSIGNED",
                      "StartTime": 1788328800000, "EndTime": 1790920800000, "Status": "LIMIT",
                      "EquityList": [
                        {"EquityType": "CREDITS", "CycleInstanceId": "c2",
                         "CycleStartTime": 1788328800000, "CycleEndTime": 1790920800000,
                         "CycleTotalValue": 45000, "CycleSurplusValue": 0, "CycleVersion": 1}
                      ]}
                   ], "Total": 2, "PageNo": 1, "PageSize": 10
                 }}
                """;
        BalanceSnapshot snap = ModelStudioTokenPlanProvider.fromJson(
                ProviderId.BAILIAN_TOKENPLAN, "b2", "百炼TokenPlan",
                List.of(MAPPER.readTree(body)));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // 主金额 = 两席位剩余之和
        assertEquals(15000.0, snap.primaryAmount(), 1e-9);
        assertEquals(15000.0 / 70000.0 * 100.0, snap.percent(), 1e-9);
        assertEquals(2, snap.metrics().size());
        BalanceSnapshot.MetricBlock m1 = snap.metrics().get(0);
        assertTrue(m1.label().startsWith("席位 标准 "));
        assertEquals(15000.0, m1.amount(), 1e-9);
        assertTrue(snap.metrics().stream().anyMatch(m -> m.amount() == 0.0 && m.percent() == 0.0));
        // 成员/席位类型/到期时间等明细挂在各自 QUOTA 区块内部
        assertTrue(snap.metrics().stream().anyMatch(m -> m.details().stream()
                .anyMatch(d -> d.label().equals("成员") && d.value().equals("张三"))));
        assertTrue(snap.metrics().stream().anyMatch(m -> m.details().stream()
                .anyMatch(d -> d.label().equals("席位类型") && d.value().equals("标准"))));
        assertTrue(snap.metrics().stream().anyMatch(m -> m.details().stream()
                .anyMatch(d -> d.label().equals("到期时间"))));
    }

    /** 无任何 CREDITS 权益 → 明确报错（未订阅 Token Plan）。 */
    @Test
    void noCreditsReportsError() throws Exception {
        String body = "{\"Success\": true, \"Data\": {\"Items\": [], \"Total\": 0}}";
        BalanceSnapshot snap = ModelStudioTokenPlanProvider.fromJson(
                ProviderId.BAILIAN_TOKENPLAN, "b3", "百炼TokenPlan",
                List.of(MAPPER.readTree(body)));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertTrue(snap.message().contains("未查询到 Token Plan Credits"));
    }

    /** 非 CREDITS 权益（SPN/资源包）不应产生进度条。 */
    @Test
    void nonCreditsEquityIgnored() throws Exception {
        String body = """
                {"Success": true, "Data": {
                   "Items": [
                     {"InstanceCode": "subs-9", "Status": "NORMAL",
                      "EquityList": [
                        {"EquityType": "SPN", "CycleTotalValue": 999, "CycleSurplusValue": 888}
                      ]}
                   ], "Total": 1
                 }}
                """;
        BalanceSnapshot snap = ModelStudioTokenPlanProvider.fromJson(
                ProviderId.BAILIAN_TOKENPLAN, "b4", "百炼TokenPlan",
                List.of(MAPPER.readTree(body)));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
