package com.aibalance.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountConfigTest {

    @Test
    void displayEntriesRoundTrip() {
        AccountConfig acc = new AccountConfig(ProviderId.DEEPSEEK, "主账户", "sk-test", null);
        assertNull(acc.getDisplayEntries());
        List<String> keys = List.of("QUOTA|窗口 1 套餐剩余", "USAGE|本周已用 tokens");
        acc.setDisplayEntries(keys);
        assertEquals(keys, acc.getDisplayEntries());
    }

    @Test
    void emptyConfigFallsBackToNull() {
        AccountConfig acc = new AccountConfig(ProviderId.GLM, "GLM", "sk-test", null);
        acc.setDisplayEntries(List.of());
        assertEquals(0, acc.getDisplayEntries().size());
        acc.setDisplayEntries(null);
        assertNull(acc.getDisplayEntries());
    }

    @Test
    void entryModesRoundTrip() {
        AccountConfig acc = new AccountConfig(ProviderId.OPENCODE_GO, "OpenCode", "sk-test", null);
        assertNull(acc.getEntryModes());
        assertNull(acc.entryMode("QUOTA|套餐剩余"));
        java.util.Map<String, String> modes = new java.util.LinkedHashMap<>();
        modes.put("detail|每周窗口", "BAR");
        modes.put("detail|每月窗口", "TEXT");
        acc.setEntryModes(modes);
        assertEquals(modes, acc.getEntryModes());
        assertEquals("BAR", acc.entryMode("detail|每周窗口"));
        assertEquals("TEXT", acc.entryMode("detail|每月窗口"));
        assertNull(acc.entryMode("detail|不存在"));
    }
}
