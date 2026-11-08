package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToApisProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesBalance() throws Exception {
        String body = """
                {"credits_per_usd": 200, "remain_balance": 10.5, "remain_credits": 2100,
                 "success": true, "unlimited_quota": false, "used_balance": 2.5, "used_credits": 500}
                """;
        BalanceSnapshot snap = ToApisProvider.parseBalance(
                ProviderId.TOAPIS, "a1", "yy", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(10.5, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        assertEquals("剩余余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("剩余 Credits")
                && d.value().equals("2100")));
        // 已用积分也要解析展示
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("已用 Credits")
                && d.value().equals("500")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("已用余额")
                && d.value().equals("$2.50")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("换算比例")
                && d.value().equals("1 USD = 200 Credits")));
    }

    @Test
    void unlimitedQuotaHidesNegativeBalance() throws Exception {
        String body = """
                {"credits_per_usd": 200, "remain_balance": -1, "remain_credits": 0,
                 "success": true, "unlimited_quota": true, "used_balance": 0, "used_credits": 0}
                """;
        BalanceSnapshot snap = ToApisProvider.parseBalance(
                ProviderId.TOAPIS, "a1", "yy", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertNull(snap.primaryAmount());
        assertEquals("不限量套餐", snap.message());
        // 不展示负余额指标与 0 余量
        assertTrue(snap.details().stream().noneMatch(d -> d.label().equals("剩余 Credits")));
        assertTrue(snap.metrics().isEmpty());
    }

    @Test
    void businessFailureReturnsErrorWithMessage() throws Exception {
        // 官方：业务失败时 HTTP 仍为 200，用 success=false + message 表达
        String body = """
                {"success": false, "message": "获取令牌信息失败: record not found"}
                """;
        BalanceSnapshot snap = ToApisProvider.parseBalance(
                ProviderId.TOAPIS, "a1", "yy", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
        assertEquals("获取令牌信息失败: record not found", snap.message());
    }

    @Test
    void appendsUserAccountBalance() throws Exception {
        BalanceSnapshot.Builder builder = ToApisProvider.parseBuilder(
                ProviderId.TOAPIS, "a1", "yy",
                MAPPER.readTree("{\"success\":true,\"remain_balance\":10.5,\"used_balance\":2.5,"
                        + "\"remain_credits\":2100,\"credits_per_usd\":200,\"unlimited_quota\":false}"));
        String user = """
                {"success": true, "remain_balance": 88.8, "used_balance": 11.2,
                 "remain_credits": 17760, "credits_per_usd": 200, "unlimited_quota": false}
                """;
        ToApisProvider.appendUserBalance(builder, MAPPER.readTree(user));
        BalanceSnapshot snap = builder.build();
        assertNotNull(snap.metricOf(com.aibalance.model.ProviderCapability.QUOTA));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("账户已用")
                && d.value().equals("$11.20")));
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("账户剩余 Credits")
                && d.value().equals("17760")));
    }

    @Test
    void unlimitedUserBalanceIsAnnotated() throws Exception {
        BalanceSnapshot.Builder builder = ToApisProvider.parseBuilder(
                ProviderId.TOAPIS, "a1", "yy",
                MAPPER.readTree("{\"success\":true,\"remain_balance\":1,\"used_balance\":0,"
                        + "\"remain_credits\":200,\"credits_per_usd\":200,\"unlimited_quota\":false}"));
        ToApisProvider.appendUserBalance(builder, MAPPER.readTree(
                "{\"success\":true,\"remain_balance\":-1,\"unlimited_quota\":true}"));
        BalanceSnapshot snap = builder.build();
        assertTrue(snap.details().stream().anyMatch(d -> d.label().equals("账户余额")
                && d.value().equals("不限量")));
    }
}