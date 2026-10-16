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

    /**
     * 最近一次 {@link #load()} 是否解析失败。为 true 时 {@link #save()} 会拒绝写回，
     * 防止"读取瞬时失败 → 返回空配置 → 后续任何一次保存把用户文件覆盖成空"造成数据静默丢失。
     */
    private volatile boolean unreadable;

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

    /** 本次启动是否读到了无法解析的配置；为 true 时配置处于只读保护状态。 */
    public boolean unreadable() {
        return unreadable;
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

    /**
     * 读取配置；文件不存在时返回默认配置。
     *
     * <p>解析失败时**绝不丢弃或移动原文件**：仅额外复制一份带时间戳的备份用于取证，
     * 原文件留在原地，并置 {@link #unreadable} 进入只读保护。备份名带时间戳是必需的——
     * 固定名字的 {@code config.corrupted.bak} 会在第二次故障时被覆盖掉，
     * 而那份可能正是用户仅存的数据副本。
     */
    public AppConfig load() {
        return withLock(this::doLoad);
    }

    private AppConfig doLoad() {
        AppConfig cfg = new AppConfig();
        if (!Files.exists(configFile)) {
            return cfg;
        }
        try {
            // 旧配置迁移：QODER 枚举拆分为 QODER_CN / QODER_INTL，旧配置中的 "QODER" 映射到国内站
            String json = Files.readString(configFile);
            json = json.replace("\"QODER\"", "\"QODER_CN\"");
            AppConfig loaded = mapper.readValue(json, AppConfig.class);
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
            unreadable = true;
            System.err.println("CONFIG_LOAD_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                Path backup = configFile.resolveSibling("config.corrupted-"
                        + java.time.LocalDateTime.now().toString().replaceAll("[:.]", "-") + ".bak");
                Files.copy(configFile, backup);
                System.err.println("CONFIG_LOAD_BACKUP: " + backup);
            } catch (IOException ignored) {
                // 备份失败不影响主流程，原文件本身始终未被移动或删除
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
        if (unreadable) {
            // 上一次 load() 没能解析出配置：此刻内存里的多半是默认空配置，
            // 写回去等于用空配置覆盖用户的账户与密钥。宁可本次不保存。
            throw new IllegalStateException("配置读取失败，已阻止写回以保护原文件："
                    + configFile + "；请修复或删除该文件后重启");
        }
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
                // 卡片展示条目同样是用户配置：漏拷会让每次保存都清空用户在
                // 「展示条目」对话框里的选择（曾经就是这样静默丢的）
                List<String> entries = acc.getDisplayEntries();
                copy.setDisplayEntries(entries == null ? null : new ArrayList<>(entries));
                protectedAccounts.add(copy);
            }
            AppConfig toWrite = new AppConfig();
            toWrite.setRefreshIntervalSeconds(cfg.getRefreshIntervalSeconds());
            toWrite.setTheme(cfg.getTheme());
            toWrite.setProxyMode(cfg.getProxyMode());
            toWrite.setAutoCollapseBall(cfg.isAutoCollapseBall());
            toWrite.setAccounts(protectedAccounts);

            // 临时文件名带进程号：固定名会让两个实例写到同一个 tmp，
            // 一个正在写、另一个把它 rename 走，留下半截 JSON。
            Path tmp = configFile.resolveSibling(
                    "config.json.tmp." + ProcessHandle.current().pid());
            try {
                mapper.writeValue(tmp.toFile(), toWrite);
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败: " + e.getMessage(), e);
        }
    }
}
