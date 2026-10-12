package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlmProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesTokenAndWeeklyWindows() throws Exception {
        String body = """
                {"data": {
                  "planName": "Pro",
                  "limits": [
                    {"type": "TOKENS_LIMIT", "duration": 300, "percentage": 40.5,
                     "usage": 16200000, "currentValue": 40000000, "remaining": 23800000,
                     "nextResetTime": 1760000000000},
                    {"type": "TOKENS_LIMIT", "duration": 10080, "percentage": 52.0,
                     "usage": 20800000, "currentValue": 40000000, "remaining": 19200000,
                     "nextResetTime": 1760400000000},
                    {"type": "TIME_LIMIT", "duration": 43200, "percentage": 12.3,
                     "usage": 123, "currentValue": 1000, "remaining": 877}
                  ]
                }}
                """;
        BalanceSnapshot snap = GlmProvider.fromJson(
                "a1", "GLM 套餐", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(ProviderId.GLM, snap.providerId());
        // 主显示：5 小时窗口剩余 = 100 - 40.5
        assertEquals(59.5, snap.primaryAmount(), 1e-9);
        assertEquals("%", snap.primaryCurrency());
        assertEquals("Coding Plan 额度", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("套餐档位") && l.value().equals("Pro")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("5 小时窗口")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("周窗口")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("MCP")));
    }

    @Test
    void handlesCreditLimitAndStringNumbers() throws Exception {
        String body = """
                {"data": {
                  "plan": "Lite",
                  "limits": [
                    {"type": "CREDIT_LIMIT", "duration": "300",
                     "percentage": "10", "remaining": "1800", "limit": "2000"}
                  ]
                }}
                """;
        BalanceSnapshot snap = GlmProvider.fromJson(
                "a2", "GLM Lite", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(90.0, snap.primaryAmount(), 1e-9);
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("套餐档位") && l.value().equals("Lite")));
        assertTrue(snap.details().stream().anyMatch(l -> l.value().contains("剩余 1800")));
    }

    @Test
    void emptyLimitsReportsError() throws Exception {
        BalanceSnapshot snap = GlmProvider.fromJson(
                "a3", "GLM", MAPPER.readTree("{\"data\": {\"planName\": \"Pro\", \"limits\": []}}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void missingDataReportsError() throws Exception {
        BalanceSnapshot snap = GlmProvider.fromJson(
                "a4", "GLM", MAPPER.readTree("{\"code\": 401, \"msg\": \"auth fail\"}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void parsesPayAsYouGoBalanceArray() throws Exception {
        String body = """
                {"balance": [
                  {"total": 100.0, "available": 80.0, "used": 20.0, "model": "glm-4-plus"},
                  {"total": 50.0, "available": 30.0, "used": 20.0, "model": "glm-4-flash"}
                ]}
                """;
        BalanceSnapshot snap = GlmProvider.balanceFromJson(
                "a5", "GLM 按量", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(ProviderId.GLM, snap.providerId());
        // 聚合可用 = 80 + 30
        assertEquals(110.0, snap.primaryAmount(), 1e-9);
        assertEquals("CNY", snap.primaryCurrency());
        assertEquals("可用余额", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("模型余额") && l.value().contains("glm-4-plus")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("总额") && l.value().contains("150")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("已用")));
    }

    @Test
    void balanceArrayWithStringNumbers() throws Exception {
        String body = """
                {"balance": [
                  {"total": "100", "available": "60", "used": "40", "model": "glm-4"}
                ]}
                """;
        BalanceSnapshot snap = GlmProvider.balanceFromJson(
                "a6", "GLM 按量", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(60.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void missingBalanceArrayReportsError() throws Exception {
        BalanceSnapshot snap = GlmProvider.balanceFromJson(
                "a7", "GLM 按量", MAPPER.readTree("{\"error\": {\"code\": 401}}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }
}
