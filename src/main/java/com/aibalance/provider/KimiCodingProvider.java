package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Kimi Code（月之暗面）订阅套餐额度查询。
 * 接口：GET {base}/coding/v1/usages（Bearer 订阅 Key）
 *   - 国内默认：https://api.kimi.com ；海外：https://api.kimi.ai
 * 该接口未写入公开文档（社区多个项目实测确认：coding-usage-bar、
 * dsh-quota-panel、opencode-quota-usage、VS Code 插件、ai-usagebar）。
 * 响应结构：
 *   limits[]        → 5 小时（300 分钟）滚动速率窗口，limit/used/remaining（字符串或数字）
 *   顶层 usage      → 每周请求池，limit/used/remaining
 * 说明：订阅 Key 与按量付费 Key 不同；会员月度额度（Kimi 会员权益）为另一套登录态体系。
 */
public final class KimiCodingProvider implements BalanceProvider {

    /** 国内默认 API 根地址（Kimi Code） */
    private static final String DEFAULT_BASE_CN = "https://api.kimi.com";
    private static final String USAGES_PATH = "/coding/v1/usages";

    private final HttpSupport http;

    public KimiCodingProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("Base URL 无效").build();
        }
        try {
            HttpSupport.Response resp = http.get(base + USAGES_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("需要 Kimi Code 订阅 Key（按量付费 Key 不可用）").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return fromJson(config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析用量响应（可独立测试）。 */
    static BalanceSnapshot fromJson(String accountId, String accountName, JsonNode root) {
        if (root == null || !root.isObject()) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
        }
        // 5 小时窗口：limits[] 中 duration 接近 300 分钟或第一个可用窗口
        Window5h fiveHour = parseFiveHour(root);
        // 周窗口：顶层 usage
        Window weekly = parseUsage(root.get("usage"));

        if (fiveHour == null && weekly == null) {
            return BalanceSnapshot.of(ProviderId.KIMI_CODING, accountId, accountName,
                    BalanceSnapshot.Status.ERROR)
                    .message("未解析到套餐窗口（非 Kimi Code 订阅 Key？）").build();
        }

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(ProviderId.KIMI_CODING, accountId, accountName)
                .label("Coding 套餐额度").currency("%");
        if (fiveHour != null) {
            builder.amount(100.0 - fiveHour.usedPercent);
            builder.percent(100.0 - fiveHour.usedPercent);
            builder.message(String.format("5 小时窗口剩余 %.0f%%", 100.0 - fiveHour.usedPercent));
            builder.detail("5 小时窗口", fiveHour.text);
        }
        if (weekly != null) {
            builder.detail("周窗口", weekly.text);
        }
        builder.detail("计量口径", "Kimi Code 订阅额度（会员月度权益为另一套体系）");
        return builder.build();
    }

    private static Window5h parseFiveHour(JsonNode root) {
        JsonNode limits = root.get("limits");
        if (limits == null || !limits.isArray()) {
            return null;
        }
        JsonNode best = null;
        double bestDelta = Double.MAX_VALUE;
        for (JsonNode lim : limits) {
            Double duration = JsonSupport.number(lim, "duration");
            double delta = duration == null ? Double.MAX_VALUE : Math.abs(duration - 300.0);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = lim;
            }
        }
        if (best == null) {
            return null;
        }
        Double limit = JsonSupport.number(best, "limit");
        Double used = JsonSupport.number(best, "used");
        if (limit == null || limit <= 0 || used == null) {
            // 兼容 remaining 拼写
            Double remaining = JsonSupport.number(best, "remaining");
            if (limit != null && limit > 0 && remaining != null) {
                used = limit - remaining;
            } else {
                return null;
            }
        }
        double usedPercent = used / limit * 100.0;
        return new Window5h(usedPercent,
                String.format("已用 %s / %s · %.0f%%", trim(used), trim(limit), usedPercent));
    }

    private static Window parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return null;
        }
        Double limit = JsonSupport.number(usage, "limit");
        Double used = JsonSupport.number(usage, "used");
        if (limit == null || limit <= 0 || used == null) {
            Double remaining = JsonSupport.number(usage, "remaining");
            if (limit != null && limit > 0 && remaining != null) {
                used = limit - remaining;
            } else {
                return null;
            }
        }
        double usedPercent = used / limit * 100.0;
        return new Window(String.format("已用 %s / %s · %.0f%%", trim(used), trim(limit), usedPercent));
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_CN;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? DEFAULT_BASE_CN : s;
    }

    static final class Window5h {
        final double usedPercent;
        final String text;

        Window5h(double usedPercent, String text) {
            this.usedPercent = usedPercent;
            this.text = text;
        }
    }

    static final class Window {
        final String text;

        Window(String text) {
            this.text = text;
        }
    }
}
