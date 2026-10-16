package com.aibalance.service;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.provider.BalanceProvider;
import com.aibalance.provider.ProviderRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 余额监控服务：
 * - 并行刷新所有已启用账户（固定线程池，互不阻塞）；
 * - 单个账户完成后立即回调 UI 更新（先获取先显示）；
 * - 全部完成后统一回调（更新最后刷新时间等全局状态）；
 * - 可选定时自动刷新（ScheduledExecutorService，可随时调整间隔）；
 * - 每个账户独立快照，单点失败不影响其他账户。
 */
public final class BalanceMonitor {

    private static final int MAX_PARALLEL = 8;

    private final ProviderRegistry registry;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Map<String, BalanceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong lastRefreshAt = new AtomicLong();
    private final AtomicLong nextScheduledAt = new AtomicLong();

    private volatile List<AccountConfig> accounts = List.of();
    private volatile long refreshIntervalSeconds = 60;
    private volatile boolean refreshing;
    private java.util.concurrent.ScheduledFuture<?> scheduledTask;
    /** 单个快照更新后的 UI 回调（在工作线程触发，UI 需自行转回 FX 线程）。 */
    private volatile Consumer<BalanceSnapshot> snapshotUpdateListener;
    /** 全部刷新完成后的 UI 回调（在工作线程触发，UI 需自行转回 FX 线程）。 */
    private volatile Runnable refreshCompleteListener;

    public BalanceMonitor(ProviderRegistry registry) {
        this.registry = registry;
        this.workers = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
            Thread t = new Thread(r, "balance-worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "balance-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void setAccounts(List<AccountConfig> accounts) {
        this.accounts = List.copyOf(accounts);
        // 清理已删除账户的旧快照
        snapshots.keySet().removeIf(id -> accounts.stream().noneMatch(a -> a.getId().equals(id)));
    }

    public void setRefreshInterval(long seconds) {
        this.refreshIntervalSeconds = Math.max(0, seconds);
        reschedule();
    }

    public long refreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    /** 设置单个快照更新后的 UI 回调。 */
    public void setSnapshotUpdateListener(Consumer<BalanceSnapshot> listener) {
        this.snapshotUpdateListener = listener;
    }

    /** 设置全部刷新完成后的 UI 回调。 */
    public void setRefreshCompleteListener(Runnable listener) {
        this.refreshCompleteListener = listener;
    }

    /** 快照视图（不可变副本，UI 可安全遍历） */
    public Map<String, BalanceSnapshot> snapshotView() {
        return Map.copyOf(snapshots);
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    public long lastRefreshAt() {
        return lastRefreshAt.get();
    }

    public long nextScheduledAt() {
        return nextScheduledAt.get();
    }

    /**
     * 并行刷新全部账户。
     * 每个账户完成后立即触发 snapshotUpdateListener（先获取先显示）。
     * onDone 在所有账户完成后调用（在工作线程上执行，UI 需自行转回 FX 线程）。
     */
    public void refreshAll(Runnable onDone) {
        if (refreshing) {
            return;
        }
        refreshing = true;
        List<AccountConfig> targets = new ArrayList<>(accounts);
        if (targets.isEmpty()) {
            lastRefreshAt.set(System.currentTimeMillis());
            refreshing = false;
            if (refreshCompleteListener != null) {
                refreshCompleteListener.run();
            }
            onDone.run();
            return;
        }
        pending.set(targets.size());
        for (AccountConfig acc : targets) {
            workers.submit(() -> {
                try {
                    BalanceSnapshot snap = checkOne(acc);
                    snapshots.put(acc.getId(), snap);
                    // 单个完成立即回调 UI 更新
                    if (snapshotUpdateListener != null) {
                        snapshotUpdateListener.accept(snap);
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        lastRefreshAt.set(System.currentTimeMillis());
                        refreshing = false;
                        if (refreshCompleteListener != null) {
                            refreshCompleteListener.run();
                        }
                        onDone.run();
                    }
                }
            });
        }
    }

    /** 刷新单个账户（供卡片单独刷新）。 */
    public void refreshOne(AccountConfig acc, Runnable onDone) {
        workers.submit(() -> {
            try {
                BalanceSnapshot snap = checkOne(acc);
                snapshots.put(acc.getId(), snap);
                if (snapshotUpdateListener != null) {
                    snapshotUpdateListener.accept(snap);
                }
            } finally {
                onDone.run();
            }
        });
    }

    private BalanceSnapshot checkOne(AccountConfig acc) {
        BalanceProvider provider = registry.forId(acc.getProviderId());
        if (provider == null) {
            return BalanceSnapshot.of(acc.getProviderId(), acc.getId(), acc.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("未注册的 Provider: " + acc.getProviderId()).build();
        }
        return provider.check(acc);
    }

    private void reschedule() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        long interval = refreshIntervalSeconds;
        if (interval <= 0) {
            nextScheduledAt.set(0);
            return;
        }
        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            nextScheduledAt.set(System.currentTimeMillis() + interval * 1000);
            refreshAll(() -> {
            });
        }, interval, interval, TimeUnit.SECONDS);
        nextScheduledAt.set(System.currentTimeMillis() + interval * 1000);
    }

    /** 关闭线程池（应用退出时调用）。 */
    public void shutdown() {
        scheduler.shutdownNow();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 已启用账户中未配置 Key 的个数（供 UI 统计）。 */
    public int notConfiguredCount() {
        return (int) accounts.stream()
                .filter(a -> a.isEnabled() && !a.hasValidKey())
                .count();
    }
}
