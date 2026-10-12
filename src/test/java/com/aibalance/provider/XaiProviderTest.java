package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaiProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void fromJsonParsesOfficialTotalVal() throws Exception {
        // 官方文档结构：total.val = USD 美分字符串
        String body = """
                {"changes":[
                   {"teamId":"t1","changeOrigin":"PURCHASE","topupStatus":"SUCCEEDED",
                    "amount":{"val":"-1000"},"invoiceNumber":"062-446-653-166",
                    "createTime":"2025-02-24T15:28:02.308840Z"},
                   {"teamId":"t1","changeOrigin":"PURCHASE","topupStatus":"SUCCEEDED",
                    "amount":{"val":"-500"},"createTime":"2025-02-25T09:00:00Z"}
                 ],
                 "total":{"val":"1000"}}
                """;
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // 1000 美分 = 10.00 USD
        assertEquals(10.00, snap.primaryAmount(), 1e-9);
        BalanceSnapshot.MetricBlock bal = snap.metricOf(ProviderCapability.BALANCE);
        assertNotNull(bal);
        assertEquals("USD", bal.currency());
        // 明细：余额（美分） + 最多 3 条变动
        boolean hasCents = snap.details().stream().anyMatch(d -> d.label().contains("美分"));
        boolean hasChange = snap.details().stream().anyMatch(d -> d.label().startsWith("变动"));
        assertTrue(hasCents && hasChange);
        long changeCount = snap.details().stream().filter(d -> d.label().startsWith("变动")).count();
        assertEquals(2, changeCount);
    }

    @Test
    void fromJsonParsesNegativeTotal() throws Exception {
        // 负余额 = 欠费（示例文档数据）
        String body = "{\"changes\":[],\"total\":{\"val\":\"-1000\"}}";
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(-10.00, snap.primaryAmount(), 1e-9);
    }

    @Test
    void fromJsonParsesPrepaidBalanceCredits() throws Exception {
        String body = """
                {"object":"prepaid_balance","team_id":"xai_team_1",
                 "prepaid_balance":{"available_credits":42.5,"used_credits":7.5,
                                    "credits":50.0,"currency":"USD"}}
                """;
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(42.5, snap.primaryAmount(), 1e-9);
    }

    @Test
    void fromJsonParsesFlatBalance() throws Exception {
        String body = "{\"balance\":120.25}";
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(120.25, snap.primaryAmount(), 1e-9);
    }

    @Test
    void fromJsonRejectsUnknownShape() throws Exception {
        String body = "{\"foo\":\"bar\"}";
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertTrue(snap.message().contains("无法识别"));
    }

    @Test
    void fromJsonMapsError() throws Exception {
        String body = "{\"error\":{\"message\":\"invalid key\"}}";
        BalanceSnapshot snap = XaiProvider.fromJson(
                ProviderId.XAI, "a", "n", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertTrue(snap.message().contains("invalid key"));
    }
}
