package com.aibalance.service;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.ProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsConfig() {
        ConfigStore store = new ConfigStore(tempDir.resolve("config.json"));

        ConfigStore.AppConfig cfg = new ConfigStore.AppConfig();
        cfg.setRefreshIntervalSeconds(30);
        AccountConfig acc = new AccountConfig(ProviderId.DEEPSEEK, "主账户", "sk-test", null);
        cfg.getAccounts().add(acc);
        store.save(cfg);

        ConfigStore.AppConfig loaded = store.load();
        assertEquals(30, loaded.getRefreshIntervalSeconds());
        assertEquals(1, loaded.getAccounts().size());
        AccountConfig back = loaded.getAccounts().get(0);
        assertEquals(acc.getId(), back.getId());
        assertEquals(ProviderId.DEEPSEEK, back.getProviderId());
        assertEquals("sk-test", back.getApiKey());
        assertTrue(back.isEnabled());
        assertTrue(Files.exists(tempDir.resolve("config.json")));
    }

    @Test
    void returnsDefaultsWhenMissing() {
        ConfigStore store = new ConfigStore(tempDir.resolve("nope.json"));
        ConfigStore.AppConfig cfg = store.load();
        assertEquals(60, cfg.getRefreshIntervalSeconds());
        assertTrue(cfg.getAccounts().isEmpty());
    }

    @Test
    void persistsAndDefaultsTheme() {
        ConfigStore store = new ConfigStore(tempDir.resolve("config.json"));

        // 默认暗色
        assertEquals("dark", store.load().getTheme());

        ConfigStore.AppConfig cfg = new ConfigStore.AppConfig();
        cfg.setTheme("light");
        store.save(cfg);

        assertEquals("light", store.load().getTheme());

        // 非法值回退暗色
        ConfigStore.AppConfig bad = new ConfigStore.AppConfig();
        bad.setTheme("blue");
        store.save(bad);
        assertEquals("dark", store.load().getTheme());
    }

    @Test
    void persistsBillingModeAndFallsBackToDefaults() {
        ConfigStore store = new ConfigStore(tempDir.resolve("config.json"));

        // 双计费厂商：未设置时默认自动识别
        AccountConfig minimax = new AccountConfig(ProviderId.MINIMAX, "MiniMax", "sk-1", null);
        assertEquals(com.aibalance.model.BillingMode.AUTO, minimax.effectiveBillingMode());

        // 单一计费厂商：未设置时默认其唯一方式
        AccountConfig deepseek = new AccountConfig(ProviderId.DEEPSEEK, "DeepSeek", "sk-2", null);
        assertEquals(com.aibalance.model.BillingMode.PAY_AS_YOU_GO, deepseek.effectiveBillingMode());

        // 显式保存套餐后读回
        ConfigStore.AppConfig cfg = new ConfigStore.AppConfig();
        minimax.setBillingMode(com.aibalance.model.BillingMode.SUBSCRIPTION.name());
        cfg.getAccounts().add(minimax);
        store.save(cfg);

        AccountConfig back = store.load().getAccounts().get(0);
        assertEquals(com.aibalance.model.BillingMode.SUBSCRIPTION.name(), back.getBillingMode());
        assertEquals(com.aibalance.model.BillingMode.SUBSCRIPTION, back.effectiveBillingMode());

        // 非法值回退默认
        back.setBillingMode("BOGUS");
        assertEquals(com.aibalance.model.BillingMode.AUTO, back.effectiveBillingMode());
    }

    @Test
    void recoversFromCorruptedFile() throws Exception {
        Path f = tempDir.resolve("config.json");
        Files.writeString(f, "{broken json!!");
        ConfigStore store = new ConfigStore(f);
        ConfigStore.AppConfig cfg = store.load();
        assertTrue(cfg.getAccounts().isEmpty());
        assertTrue(Files.exists(tempDir.resolve("config.corrupted.bak")));
    }
}
