package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesGeneralRemainingPercent() throws Exception {
        String body = """
                {"current_general_remaining_percent": 68.5,
                 "current_video_usage_count": 3, "current_video_total_count": 10,
                 "end_time": "2026-10-10T16:00:00Z",
                 "weekly_end_time": "2026-10-15T16:00:00Z"}
                """;
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m1", "MiniMax 套餐", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(ProviderId.MINIMAX, snap.providerId());
        // 主显示：5 小时窗口剩余 = 68.5
        assertEquals(68.5, snap.primaryAmount(), 1e-9);
        assertEquals("%", snap.primaryCurrency());
        assertEquals("Token Plan 额度", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("5 小时窗口")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("周窗口")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("5 小时重置")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("周窗口重置")));
    }

    @Test
    void fallsBackToCountRatioWhenNoGeneralPercent() throws Exception {
        String body = """
                {"current_video_usage_count": 3, "current_video_total_count": 10,
                 "end_time": "2026-10-10T16:00:00Z"}
                """;
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m2", "MiniMax", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // 计数制：已用 30% → 剩余 70%
        assertEquals(70.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void stringNumbersAreAccepted() throws Exception {
        String body = """
                {"current_general_remaining_percent": "45",
                 "current_video_usage_count": "1", "current_video_total_count": "2"}
                """;
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m3", "MiniMax", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // remaining_percent=45 → 剩余 45%
        assertEquals(45.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void noWindowsReportsError() throws Exception {
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m4", "MiniMax", MAPPER.readTree("{\"base_resp\": {\"status_code\": 401}}"));
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void parsesOkBaseRespWithWindows() throws Exception {
        // 正常套餐响应携带 base_resp.status_code=0，仍能解析出窗口额度
        String body = """
                {"base_resp": {"status_code": 0, "status_msg": "success"},
                 "current_general_remaining_percent": 72.0,
                 "end_time": "2026-10-10T16:00:00Z"}
                """;
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m5", "MiniMax", MAPPER.readTree(body));
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals(72.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void payAsYouGoOkWithoutRatelimitHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200,
                java.util.Map.of(), "[]");
        AccountConfig cfg = new AccountConfig(ProviderId.MINIMAX, "按量", "sk-minimax", "https://api.minimaxi.com");
        BalanceSnapshot snap = MiniMaxProvider.payAsYouGoOk(cfg, resp);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals("按量 Key 有效", snap.primaryLabel());
        assertTrue(snap.message().contains("无公开 API"));
    }

    @Test
    void payAsYouGoOkReadsRatelimitHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200,
                java.util.Map.of("x-ratelimit-remaining-tokens", java.util.List.of("8000"),
                        "x-ratelimit-limit-tokens", java.util.List.of("10000")), "[]");
        AccountConfig cfg = new AccountConfig(ProviderId.MINIMAX, "按量", "sk-minimax", "https://api.minimaxi.com");
        BalanceSnapshot snap = MiniMaxProvider.payAsYouGoOk(cfg, resp);
        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        assertEquals("tokens", snap.primaryCurrency());
        assertEquals(8000.0, snap.primaryAmount(), 1e-9);
    }

    @Test
    void parsesModelRemainsArrayRealStructure() throws Exception {
        // 实测真实结构（2026-10，真实套餐 Key）：额度在 model_remains[] 数组中
        String body = """
                {"model_remains": [
                  {"start_time": 1791702000000, "end_time": 1791720000000, "remains_time": 3088574,
                   "current_interval_total_count": 0, "current_interval_usage_count": 0,
                   "model_name": "general",
                   "current_weekly_total_count": 0, "current_weekly_usage_count": 0,
                   "weekly_start_time": 1791129600000, "weekly_end_time": 1791734400000,
                   "weekly_remains_time": 17488574,
                   "current_interval_status": 1, "current_interval_remaining_percent": 100,
                   "current_weekly_status": 1, "current_weekly_remaining_percent": 1},
                  {"start_time": 1791648000000, "end_time": 1791734400000, "remains_time": 17488574,
                   "current_interval_total_count": 3, "current_interval_usage_count": 0,
                   "model_name": "video",
                   "current_weekly_total_count": 21, "current_weekly_usage_count": 0,
                   "weekly_start_time": 1791129600000, "weekly_end_time": 1791734400000,
                   "weekly_remains_time": 17488574,
                   "current_interval_status": 1, "current_interval_remaining_percent": 100,
                   "current_weekly_status": 1, "current_weekly_remaining_percent": 1}
                ]}
                """;
        BalanceSnapshot snap = MiniMaxProvider.fromJson(
                "m6", "MiniMax 套餐", MAPPER.readTree(body));

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // general 模型 5 小时窗口剩余 100%
        assertEquals(100.0, snap.primaryAmount(), 1e-9);
        assertEquals(100.0, snap.percent(), 1e-9);
        // 周窗口明细、模型明细均出现
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("周窗口")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().contains("模型 video")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("5 小时重置")));
    }

    @Test
    void bizRejectedDetectsBaseRespErrorCode() throws Exception {
        // 实测：套餐接口 HTTP 恒 200，业务错误在 base_resp.status_code（1004=鉴权失败）
        assertTrue(MiniMaxProvider.bizRejected(
                MAPPER.readTree("{\"base_resp\": {\"status_code\": 1004}}")));
        assertTrue(MiniMaxProvider.bizRejected(
                MAPPER.readTree("{\"base_resp\": {\"status_code\": 1004, \"status_msg\": \"login fail\"}}")));
        assertTrue(!MiniMaxProvider.bizRejected(
                MAPPER.readTree("{\"base_resp\": {\"status_code\": 0, \"status_msg\": \"success\"}}")));
        assertTrue(!MiniMaxProvider.bizRejected(
                MAPPER.readTree("{\"current_general_remaining_percent\": 68.5}")));
    }
}
