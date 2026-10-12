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
}
