package com.aibalance.ui;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountCardFilterMetricsTest {

    private static BalanceSnapshot.MetricBlock block(ProviderCapability cap, String label) {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.MINIMAX, "v", "t");
        b.metric(cap, label, 10.0, "tokens", 50.0, null);
        return b.build().metrics().get(0);
    }

    @Test
    void noConfigShowsAll() {
        List<BalanceSnapshot.MetricBlock> metrics = List.of(
                block(ProviderCapability.QUOTA, "窗口 A"),
                block(ProviderCapability.QUOTA, "窗口 B"),
                block(ProviderCapability.USAGE, "本周用量"));
        assertEquals(3, AccountCard.filterMetrics(metrics, null).size());
        assertEquals(3, AccountCard.filterMetrics(metrics, List.of()).size());
    }

    @Test
    void configuredKeysFilterAndKeepOrder() {
        List<BalanceSnapshot.MetricBlock> metrics = List.of(
                block(ProviderCapability.QUOTA, "窗口 A"),
                block(ProviderCapability.QUOTA, "窗口 B"),
                block(ProviderCapability.USAGE, "本周用量"));
        // 只显示 窗口 B 与 本周用量，且 本周用量 在前（用户排序）
        List<BalanceSnapshot.MetricBlock> out = AccountCard.filterMetrics(metrics,
                List.of("USAGE|本周用量", "QUOTA|窗口 B"));
        assertEquals(2, out.size());
        assertEquals("本周用量", out.get(0).label());
        assertEquals("窗口 B", out.get(1).label());
    }

    @Test
    void staleConfigFallsBackToAll() {
        List<BalanceSnapshot.MetricBlock> metrics = List.of(
                block(ProviderCapability.QUOTA, "窗口 A"));
        // 配置的 key 全部失效（接口结构变化）→ 回退显示全部，不丢数据
        List<BalanceSnapshot.MetricBlock> out = AccountCard.filterMetrics(metrics,
                List.of("QUOTA|已不存在的窗口", "USAGE|旧用量"));
        assertEquals(1, out.size());
        assertEquals("窗口 A", out.get(0).label());
    }

    @Test
    void emptyMetricsYieldsEmpty() {
        assertTrue(AccountCard.filterMetrics(null, List.of("QUOTA|x")).isEmpty());
        assertTrue(AccountCard.filterMetrics(List.of(), List.of("QUOTA|x")).isEmpty());
    }

    @Test
    void detailsFilteredByConfiguredKeys() {
        List<BalanceSnapshot.DetailLine> details = List.of(
                new BalanceSnapshot.DetailLine("模型 qwen-plus", "请求 120 次/分钟"),
                new BalanceSnapshot.DetailLine("模型 deepseek-chat", "请求 60 次/分钟"),
                new BalanceSnapshot.DetailLine("口径", "各模型请求/用量限流"));
        // 未配置 → 全部
        assertEquals(3, AccountCard.filterDetails(details, null).size());
        // 配置 → 只显示勾选明细并按用户顺序
        List<BalanceSnapshot.DetailLine> out = AccountCard.filterDetails(details,
                List.of("detail|口径", "detail|模型 deepseek-chat"));
        assertEquals(2, out.size());
        assertEquals("口径", out.get(0).label());
        assertEquals("模型 deepseek-chat", out.get(1).label());
        // 配置完全脱节 → 回退全部
        assertEquals(3, AccountCard.filterDetails(details, List.of("detail|不存在的模型")).size());
    }
}
