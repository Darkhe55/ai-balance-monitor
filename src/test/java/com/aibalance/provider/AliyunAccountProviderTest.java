package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunAccountProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void resolveEndpointByEntryAndLegacyRegion() {
        // 留空 → 各入口的官方默认端点
        assertEquals("https://business.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint(null, ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://business.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://business.ap-southeast-1.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("", ProviderId.BAILIAN_ACCOUNT_INTL));
        // 填完整 URL → 归一化后原样使用（含自定义 / 代理地址）
        assertEquals("https://business.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("https://business.aliyuncs.com/", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://custom.example.com", AliyunAccountProvider.resolveEndpoint(
                "https://custom.example.com", ProviderId.BAILIAN_ACCOUNT_INTL));
        // 兼容旧配置：字段里存的是地域名 → cn- 走中国站，其余走国际站
        assertEquals("https://business.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("cn-beijing", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://business.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("cn-hangzhou", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://business.ap-southeast-1.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("ap-southeast-1", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("https://business.ap-southeast-1.aliyuncs.com",
                AliyunAccountProvider.resolveEndpoint("us-west-1", ProviderId.BAILIAN_ACCOUNT));
    }

    @Test
    void resolveRegionByEntryAndLegacyValue() {
        // SDK profile 地域：URL / 留空 → 入口默认地域
        assertEquals("cn-hangzhou", AliyunAccountProvider.resolveRegion("", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("cn-hangzhou", AliyunAccountProvider.resolveRegion(null, ProviderId.BAILIAN_ACCOUNT));
        assertEquals("cn-hangzhou",
                AliyunAccountProvider.resolveRegion("https://business.aliyuncs.com", ProviderId.BAILIAN_ACCOUNT));
        assertEquals("ap-southeast-1", AliyunAccountProvider.resolveRegion("", ProviderId.BAILIAN_ACCOUNT_INTL));
        // 旧配置里的地域名原样保留
        assertEquals("cn-beijing", AliyunAccountProvider.resolveRegion("cn-beijing", ProviderId.BAILIAN_ACCOUNT));
    }

    @Test
    void looksInternationalDetectsLegacyConfigs() {
        // 空 / 中国站 → 否
        assertEquals(false, AliyunAccountProvider.looksInternational(null));
        assertEquals(false, AliyunAccountProvider.looksInternational(""));
        assertEquals(false, AliyunAccountProvider.looksInternational("cn-beijing"));
        assertEquals(false, AliyunAccountProvider.looksInternational("https://business.aliyuncs.com"));
        // 国际站地域 / 旧 business-intl 域名 / 国际站端点 → 是
        assertEquals(true, AliyunAccountProvider.looksInternational("ap-southeast-1"));
        assertEquals(true, AliyunAccountProvider.looksInternational("us-west-1"));
        assertEquals(true, AliyunAccountProvider.looksInternational("https://business-intl.aliyuncs.com"));
        assertEquals(true, AliyunAccountProvider.looksInternational("https://business.ap-southeast-1.aliyuncs.com"));
    }

    @Test
    void fromBalanceJsonParsesBalance() throws Exception {
        // 官方实测结构：字段为字符串数字，信控字段名 CreditAmount
        String body = """
                {"RequestId":"r1","Success":true,"Code":"Success",
                 "Data":{"AvailableAmount":"1000.19","AvailableCashAmount":"0.00",
                         "CreditAmount":"1000.00","Currency":"CNY"}}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.BAILIAN_ACCOUNT, "a", "n");
        BalanceSnapshot snap = AliyunAccountProvider.fromBalanceJson(b, MAPPER.readTree(body)).build();
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(1000.19, snap.primaryAmount(), 1e-9);
        BalanceSnapshot.MetricBlock bal = snap.metricOf(ProviderCapability.BALANCE);
        assertNotNull(bal);
        assertEquals("CNY", bal.currency());
        boolean hasCash = snap.details().stream().anyMatch(d -> d.label().contains("现金"));
        boolean hasCredit = snap.details().stream().anyMatch(d -> d.label().contains("信控"));
        assertTrue(hasCash && hasCredit);
    }

    @Test
    void fromBalanceJsonErrorReturnsNull() throws Exception {
        String body = """
                {"Success":false,"Code":"NoPermission","Message":"no permission"}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.BAILIAN_ACCOUNT, "a", "n");
        assertEquals(null, AliyunAccountProvider.fromBalanceJson(b, MAPPER.readTree(body)));
    }

    @Test
    void fromBillJsonMergesCostMetricAndDetails() throws Exception {
        String body = """
                {"RequestId":"r1","Success":true,"Code":"Success",
                 "Data":{
                   "BillingCycle":"2026-10","TotalCount":2,
                   "Items":{"Item":[
                     {"ProductName":"百炼","PretaxAmount":12.5,"CashAmount":12.5,"PrepaidAmount":0},
                     {"ProductName":"对象存储 OSS","PretaxAmount":3.2,"CashAmount":3.2,"PrepaidAmount":0},
                     {"ProductName":"代金券抵扣","PretaxAmount":0,"CashAmount":0,"PrepaidAmount":5.0}
                   ]}
                 }}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.BAILIAN_ACCOUNT, "a", "n");
        AliyunAccountProvider.fromBillJson(b, MAPPER.readTree(body));
        BalanceSnapshot snap = b.build();
        BalanceSnapshot.MetricBlock cost = snap.metricOf(ProviderCapability.COST);
        assertNotNull(cost);
        assertEquals(15.7, cost.amount(), 1e-9);
        boolean hasBailian = snap.details().stream().anyMatch(d -> d.label().startsWith("产品 百炼"));
        assertTrue(hasBailian);
    }

    @Test
    void mergedSnapshotCarriesBalanceAndCost() throws Exception {
        String bal = """
                {"Success":true,"Data":{"AvailableAmount":"520.00","AvailableCashAmount":"120.00",
                        "CreditAmount":"400.00","Currency":"CNY"}}
                """;
        String bill = """
                {"Success":true,"Data":{"BillingCycle":"2026-10","Items":{"Item":[
                        {"ProductName":"百炼","PretaxAmount":6.6,"CashAmount":6.6,"PrepaidAmount":0}]}}}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.BAILIAN_ACCOUNT, "a", "n");
        AliyunAccountProvider.fromBalanceJson(b, MAPPER.readTree(bal));
        AliyunAccountProvider.fromBillJson(b, MAPPER.readTree(bill));
        BalanceSnapshot snap = b.build();
        assertEquals(520.0, snap.primaryAmount(), 1e-9);
        assertEquals(6.6, snap.metricOf(ProviderCapability.COST).amount(), 1e-9);
        assertTrue(snap.details().stream().anyMatch(d -> d.label().contains("信控")));
    }
}
