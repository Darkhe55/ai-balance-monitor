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
    void resolveEndpointByRegion() {
        // 国内地域 → 中国站 BssOpenApi 端点
        assertEquals("https://business.aliyuncs.com", AliyunAccountProvider.resolveEndpoint("cn-beijing"));
        assertEquals("https://business.aliyuncs.com", AliyunAccountProvider.resolveEndpoint("cn-hangzhou"));
        // 未填 → 默认国内端点
        assertEquals("https://business.aliyuncs.com", AliyunAccountProvider.resolveEndpoint(null));
        assertEquals("https://business.aliyuncs.com", AliyunAccountProvider.resolveEndpoint(""));
        // 国际站地域 → 国际版端点
        assertEquals("https://business-intl.aliyuncs.com", AliyunAccountProvider.resolveEndpoint("ap-southeast-1"));
        assertEquals("https://business-intl.aliyuncs.com", AliyunAccountProvider.resolveEndpoint("us-west-1"));
        // 兼容旧配置：直接填完整 URL → 原样归一化
        assertEquals("https://business.aliyuncs.com", AliyunAccountProvider.resolveEndpoint("https://business.aliyuncs.com/"));
        assertEquals("https://custom.example.com", AliyunAccountProvider.resolveEndpoint("https://custom.example.com"));
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
