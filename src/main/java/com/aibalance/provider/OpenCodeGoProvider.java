package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * OpenCode Go 套餐专用 Provider。
 * 官方用量端点：GET {base}/zen/go/v1/usage
 * 返回订阅套餐各时间窗口（5 小时/每周/每月）的已用百分比与重置时间。
 *
 * <p>响应结构（{@code usage} 下的每个窗口为一个对象）：
 * <pre>{@code
 * {
 *   "usage": {
 *     "rolling": { "status": "ok", "percent": 23.4, "resetsAt": "2026-…" },
 *     "weekly":  { "status": "ok", "percent": 12.0, "resetsAt": "2026-…" },
 *     "monthly": { "status": "ok", "percent": 8.5,  "resetsAt": "2026-…" }
 *   }
 * }
 * }</pre>
 * 其中 {@code percent} 为「已用百分比」（0-100），剩余比例 = 100 - percent。
 */
public final class OpenCodeGoProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public OpenCodeGoProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需要配置 Base URL").build();
        }
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }

        try {
            HttpSupport.Response resp = http.get(base + "/zen/go/v1/usage",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                String detail = DeepSeekProvider.firstError(resp.body());
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message(detail.isBlank() ? "API Key 无效或无权限" : detail).build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询失败: HTTP " + resp.status()).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null || !root.has("usage") || !root.get("usage").isObject()) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应缺少 usage 数据").build();
            }
            return buildSnapshot(config, root.get("usage"));
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode usage) {
        return parseUsage(pid, config.getId(), config.getName(), usage);
    }

    /** 解析套餐用量响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseUsage(ProviderId pid, String accountId, String accountName, JsonNode usage) {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);

        // 依次读取官方窗口：5 小时滚动 / 每周 / 每月
        Window rolling = parseWindow(usage, "rolling");
        Window weekly = parseWindow(usage, "weekly");
        Window monthly = parseWindow(usage, "monthly");
        Window primary = rolling != null ? rolling : (weekly != null ? weekly : monthly);

        if (primary == null) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应中未找到有效套餐窗口").build();
        }

        // 主展示：以最近窗口的剩余比例作为套餐剩余
        b.label("套餐剩余").currency("%").amount(primary.remainingPercent());
        b.percent(primary.remainingPercent());
        b.metric(ProviderCapability.QUOTA, "套餐剩余", primary.remainingPercent(), "%",
                primary.remainingPercent(), "OpenCode Go " + primary.displayName);

        // 各窗口明细：label 含「窗口」、值含「剩余 X%」，供主卡片与悬浮球面板统一提取
        addWindowDetail(b, rolling, "5小时窗口");
        addWindowDetail(b, weekly, "每周窗口");
        addWindowDetail(b, monthly, "每月窗口");

        return b.build();
    }

    /** 解析单个窗口：percent 为已用百分比（0-100），剩余 = 100 - percent。 */
    private static Window parseWindow(JsonNode usage, String name) {
        JsonNode w = usage == null ? null : usage.get(name);
        if (w == null || !w.isObject()) {
            return null;
        }
        Double usedPercent = JsonSupport.number(w, "percent");
        if (usedPercent == null) {
            return null;
        }
        String resetsAt = JsonSupport.text(w, "resetsAt");
        String displayName = switch (name) {
            case "rolling" -> "5 小时滚动窗口";
            case "weekly" -> "每周窗口";
            case "monthly" -> "每月窗口";
            default -> name;
        };
        return new Window(displayName, Math.max(0, Math.min(100, usedPercent)),
                resetsAt == null ? null : resetsAt);
    }

    private static void addWindowDetail(BalanceSnapshot.Builder b, Window w, String label) {
        if (w == null) {
            return;
        }
        b.detail(label, String.format("剩余 %.1f%%", w.remainingPercent()));
        if (w.resetsAt != null && !w.resetsAt.isBlank()) {
            b.detail(label + "重置", w.resetsAt);
        }
    }

    /** 一个套餐窗口的解析结果。 */
    private static final class Window {
        final String displayName;
        final double usedPercent;
        final String resetsAt;

        Window(String displayName, double usedPercent, String resetsAt) {
            this.displayName = displayName;
            this.usedPercent = usedPercent;
            this.resetsAt = resetsAt;
        }

        double remainingPercent() {
            return 100.0 - usedPercent;
        }
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String s = baseUrl.strip();
        if (s.isEmpty()) {
            return null;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}