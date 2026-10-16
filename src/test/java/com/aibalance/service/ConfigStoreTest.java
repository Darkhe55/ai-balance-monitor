package com.aibalance.service;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.ProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void corruptedFileIsPreservedAndBlocksWrites() throws Exception {
        Path f = tempDir.resolve("config.json");
        String broken = "{broken json!!";
        Files.writeString(f, broken);
        ConfigStore store = new ConfigStore(f);

        ConfigStore.AppConfig cfg = store.load();

        // 读到默认空配置，但必须进入只读保护状态
        assertTrue(cfg.getAccounts().isEmpty());
        assertTrue(store.unreadable());

        // 关键：原文件既没被移动也没被改写——这正是曾经丢账户的那一步
        assertTrue(Files.exists(f), "损坏的配置文件必须留在原地，不能被移走");
        assertEquals(broken, Files.readString(f));

        // 且已留一份带时间戳的备份供取证
        try (var files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.corrupted-")
                    && p.getFileName().toString().endsWith(".bak")));
        }

        // 只读保护期间写回必须失败，且不能碰到原文件
        ConfigStore.AppConfig empty = new ConfigStore.AppConfig();
        assertThrows(IllegalStateException.class, () -> store.save(empty));
        assertEquals(broken, Files.readString(f));
    }

    @Test
    void secondCorruptionDoesNotOverwritePreviousBackup() throws Exception {
        Path f = tempDir.resolve("config.json");
        Files.writeString(f, "{first broken!!");
        new ConfigStore(f).load();
        String firstBackup = singleBackupName();

        // 第二次故障（修复前再次失败）不得毁掉上一次的备份
        Files.writeString(f, "{second broken!!");
        new ConfigStore(f).load();

        try (var files = Files.list(tempDir)) {
            long backups = files.filter(p -> p.getFileName().toString().startsWith("config.corrupted-")
                    && p.getFileName().toString().endsWith(".bak")).count();
            assertEquals(2, backups, "两次故障应留下两份独立备份");
        }
        assertTrue(Files.exists(tempDir.resolve(firstBackup)), "第一次的备份必须仍然存在");
        assertEquals("{second broken!!", Files.readString(f));
    }

    private String singleBackupName() throws Exception {
        try (var files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("config.corrupted-"))
                    .map(p -> p.getFileName().toString())
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Test
    void displayEntriesSurviveSaveRoundTrip() {
        Path f = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(f);

        AccountConfig acc = new AccountConfig(ProviderId.DEEPSEEK, "主账户", "sk-test", null);
        acc.setDisplayEntries(java.util.List.of("QUOTA|模型 gpt-4o 配额", "BALANCE|账户余额"));
        ConfigStore.AppConfig cfg = new ConfigStore.AppConfig();
        cfg.getAccounts().add(acc);
        store.save(cfg);

        // 曾经每次保存都会漏拷 displayEntries，用户在「展示条目」里的选择被静默清空
        AccountConfig back = store.load().getAccounts().get(0);
        assertEquals(java.util.List.of("QUOTA|模型 gpt-4o 配额", "BALANCE|账户余额"),
                back.getDisplayEntries());
    }

    @Test
    void saveLeavesNoTempFileBehind() throws Exception {
        Path f = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(f);
        store.save(new ConfigStore.AppConfig());

        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("config.json.tmp")),
                    "原子写入的临时文件不应残留");
        }
    }
}
