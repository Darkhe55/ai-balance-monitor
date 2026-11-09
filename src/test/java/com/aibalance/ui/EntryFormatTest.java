package com.aibalance.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryFormatTest {

    @Test
    void parseRemainPercentFindsFirst() {
        assertEquals(76.6, EntryFormat.parseRemainPercent("剩余 76.6%"), 1e-9);
        assertEquals(1.0, EntryFormat.parseRemainPercent("已用 99% · 剩余 1%"), 1e-9);
        assertEquals(100.0, EntryFormat.parseRemainPercent("5h 剩余 100% · 周剩余 1%"), 1e-9);
    }

    @Test
    void parseRemainPercentReturnsNullWhenAbsent() {
        assertNull(EntryFormat.parseRemainPercent("10-11 20:00"));
        assertNull(EntryFormat.parseRemainPercent(""));
        assertNull(EntryFormat.parseRemainPercent(null));
    }

    @Test
    void canRenderBarMatchesRemainPercent() {
        assertTrue(EntryFormat.canRenderBar("剩余 76.6%"));
        assertFalse(EntryFormat.canRenderBar("2026-10-13 08:00"));
    }

    @Test
    void detailWantsBarRespectsModes() {
        assertTrue(EntryFormat.detailWantsBar(Map.of("detail|每周窗口", "BAR"), "detail|每周窗口", "剩余 88%"));
        assertFalse(EntryFormat.detailWantsBar(Map.of("detail|每周窗口", "TEXT"), "detail|每周窗口", "剩余 88%"));
        // 未配置默认文字（兼容旧行为）
        assertFalse(EntryFormat.detailWantsBar(null, "detail|每周窗口", "剩余 88%"));
        // 值不可作进度条时即使配置 BAR 也不渲染
        assertFalse(EntryFormat.detailWantsBar(Map.of("detail|x", "BAR"), "detail|x", "10-11 20:00"));
    }
}