package com.aibalance.service;

import com.aibalance.model.AccountConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置存储：JSON 持久化到 {user.home}/.ai-balance-monitor/config.json。
 * 写入采用临时文件 + 原子移动；读写均通过独立锁文件做进程间互斥，
 * 避免多实例并发读写导致配置文件损坏（曾导致账户数据丢失）。
 */
public final class ConfigStore {

    /** 全局配置模型（与 JSON 文件一一对应） */
    public static final class AppConfig {
        private int refreshIntervalSeconds = 60;
        private String theme = "dark";
        private String proxyMode = HttpSupport.MODE_SYSTEM;
        private boolean autoCollapseBall = true;
        private List<AccountConfig> accounts = new ArrayList<>();

        public AppConfig() {
        }

        public int getRefreshIntervalSeconds() {
            return refreshIntervalSeconds;
        }

        public void setRefreshIntervalSeconds(int refreshIntervalSeconds) {
            this.refreshIntervalSeconds = refreshIntervalSeconds;
        }

        /** 界面主题："dark" 或 "light"；旧配置缺省时按 "dark" 处理。 */
        public String getTheme() {
            return "light".equals(theme) ? "light" : "dark";
        }

        public void setTheme(String theme) {
            this.theme = "light".equals(theme) ? "light" : "dark";
        }

        /** 网络模式："system" 跟随系统代理 / "direct" 直连。 */
        public String getProxyMode() {
            return HttpSupport.MODE_SYSTEM.equals(proxyMode) ? HttpSupport.MODE_SYSTEM : HttpSupport.MODE_DIRECT;
        }

        public void setProxyMode(String proxyMode) {
            this.proxyMode = HttpSupport.MODE_SYSTEM.equals(proxyMode)
                    ? HttpSupport.MODE_SYSTEM : HttpSupport.MODE_DIRECT;
        }

        /** 悬浮球缩略面板自动收回：鼠标离开面板超过 3 秒自动收起为悬浮球。 */
        public boolean isAutoCollapseBall() {
            return autoCollapseBall;
        }

        public void setAutoCollapseBall(boolean autoCollapseBall) {
            this.autoCollapseBall = autoCollapseBall;
        }

        public List<AccountConfig> getAccounts() {
            return accounts;
        }

        public void setAccounts(List<AccountConfig> accounts) {
            this.accounts = accounts;
        }
    }

    private final Path configFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public ConfigStore() {
        this(Path.of(System.getProperty("user.home"), ".ai-balance-monitor", "config.json"));
    }

    ConfigStore(Path configFile) {
        this.configFile = configFile;
        this.lockFile = configFile.resolveSibling("config.json.lock");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path configFile() {
        return configFile;
    }

    /** 在进程间锁内执行读写操作（跨进程互斥，防止多实例并发写坏配置）。 */
    private <T> T withLock(java.util.function.Supplier<T> op) {
        try {
            Files.createDirectories(lockFile.getParent());
            FileChannel ch = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.lock();
            try {
                return op.get();
            } finally {
                try {
                    lock.release();
                } catch (IOException ignored) {
                    // 锁释放失败不影响结果
                }
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // 通道关闭失败不影响结果
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("配置锁操作失败: " + e.getMessage(), e);
        }
    }

    /** 读取配置；文件不存在或损坏时返回默认配置并尽力备份损坏文件。 */
    public AppConfig load() {
        return withLock(this::doLoad);
    }

    private AppConfig doLoad() {
        AppConfig cfg = new AppConfig();
        if (!Files.exists(configFile)) {
            return cfg;
        }
        try {
            AppConfig loaded = mapper.readValue(configFile.toFile(), AppConfig.class);
            // 解密 API Key：旧明文原样保留，dpapi: 密文解密为明文供内存使用；
            // 解密失败（跨用户/跨机器/损坏）置空，避免把密文当 Key 使用。
            for (AccountConfig acc : loaded.getAccounts()) {
                String key = acc.getApiKey();
                if (key != null && key.startsWith("dpapi:")) {
                    String plain = KeyProtector.unprotect(key);
                    acc.setApiKey(plain == null ? "" : plain);
                }
                String secret = acc.getSecret();
                if (secret != null && secret.startsWith("dpapi:")) {
                    String plain = KeyProtector.unprotect(secret);
                    acc.setSecret(plain == null ? "" : plain);
                }
            }
            return loaded;
        } catch (IOException e) {
            try {
                Path backup = configFile.resolveSibling("config.corrupted.bak");
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 备份失败不影响主流程
            }
            return cfg;
        }
    }

    /** 原子写入配置。API Key 使用 DPAPI 加密后再落盘，旧明文自动迁移。 */
    public void save(AppConfig cfg) {
        withLock(() -> {
            doSave(cfg);
            return null;
        });
    }

    private void doSave(AppConfig cfg) {
        try {
            Path dir = configFile.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            // 深拷贝账户列表并加密 Key，避免改写调用方持有的账户对象
            List<AccountConfig> protectedAccounts = new ArrayList<>();
            for (AccountConfig acc : cfg.getAccounts()) {
                AccountConfig copy = new AccountConfig();
                copy.setId(acc.getId());
                copy.setProviderId(acc.getProviderId());
                copy.setName(acc.getName());
                copy.setApiKey(KeyProtector.protect(acc.getApiKey()));
                copy.setSecret(acc.getSecret() == null || acc.getSecret().isBlank()
                        ? null : KeyProtector.protect(acc.getSecret()));
                copy.setBaseUrl(acc.getBaseUrl());
                copy.setBillingMode(acc.getBillingMode());
                copy.setEnabled(acc.isEnabled());
                copy.setExtraParams(acc.getExtraParams());
                protectedAccounts.add(copy);
            }
            AppConfig toWrite = new AppConfig();
            toWrite.setRefreshIntervalSeconds(cfg.getRefreshIntervalSeconds());
            toWrite.setTheme(cfg.getTheme());
            toWrite.setProxyMode(cfg.getProxyMode());
            toWrite.setAutoCollapseBall(cfg.isAutoCollapseBall());
            toWrite.setAccounts(protectedAccounts);

            Path tmp = configFile.resolveSibling("config.json.tmp");
            mapper.writeValue(tmp.toFile(), toWrite);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败: " + e.getMessage(), e);
        }
    }
}
