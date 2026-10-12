package com.aibalance.ui;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainViewQuotaExtractTest {

    /** MiniMax 套餐真实明细结构（5 小时窗口/周窗口/模型行 + 各自重置行）。 */
    private static BalanceSnapshot miniMaxSnapshot() {
        return BalanceSnapshot.ok(ProviderId.MINIMAX, "m1", "mmx")
                .label("Token Plan 额度").currency("%")
                .amount(100.0).percent(100.0)
                .message("5 小时窗口剩余 100%")
                .detail("5 小时窗口", "已用 0% · 剩余 100%")
                .detail("5 小时重置", "10-11 20:00")
                .detail("周窗口", "已用 99% · 剩余 1%")
                .detail("周窗口重置", "10-12 00:00")
                .detail("模型 general", "5h 剩余 100% · 周剩余 1%")
                .detail("计量口径", "Token Plan 订阅额度（现金余额需控制台查看）")
                .build();
    }

    @Test
    void extractsAllQuotaWindows() {
        List<FloatingBallView.QuotaRow> qs = MainView.extractQuotaRows(miniMaxSnapshot());
        assertEquals(3, qs.size(), "应提取 5小时窗口/周窗口/模型 三个配额行");
    }

    @Test
    void fiveHourWindowPercentAndReset() {
        List<FloatingBallView.QuotaRow> qs = MainView.extractQuotaRows(miniMaxSnapshot());
        FloatingBallView.QuotaRow five = qs.get(0);
        assertEquals("5 小时窗口", five.label());
        assertEquals(100.0, five.percent(), 1e-9);
        assertEquals("10-11 20:00", five.resetTime());
    }

    @Test
    void weeklyWindowPercentAndReset() {
        List<FloatingBallView.QuotaRow> qs = MainView.extractQuotaRows(miniMaxSnapshot());
        FloatingBallView.QuotaRow weekly = qs.get(1);
        assertEquals("周窗口", weekly.label());
        assertEquals(1.0, weekly.percent(), 1e-9);
        assertEquals("10-12 00:00", weekly.resetTime());
    }

    @Test
    void modelRowPercentWithoutReset() {
        List<FloatingBallView.QuotaRow> qs = MainView.extractQuotaRows(miniMaxSnapshot());
        FloatingBallView.QuotaRow model = qs.get(2);
        assertTrue(model.label().startsWith("模型"));
        assertEquals(100.0, model.percent(), 1e-9);
        assertNull(model.resetTime());
    }

    @Test
    void epochMillisResetIsFormatted() {
        BalanceSnapshot s = BalanceSnapshot.ok(ProviderId.MINIMAX, "m2", "mmx")
                .label("Token Plan 额度").currency("%")
                .amount(100.0).percent(100.0)
                .message("ok")
                .detail("5 小时窗口", "已用 0% · 剩余 100%")
                .detail("5 小时重置", "1791720000000")
                .build();
        List<FloatingBallView.QuotaRow> qs = MainView.extractQuotaRows(s);
        assertEquals(1, qs.size());
        assertEquals("20:00", qs.get(0).resetTime());
    }

    @Test
    void nullSnapshotYieldsEmpty() {
        assertTrue(MainView.extractQuotaRows(null).isEmpty());
    }
}
