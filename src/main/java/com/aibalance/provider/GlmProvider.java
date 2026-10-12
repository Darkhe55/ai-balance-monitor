package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.BillingMode;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智谱 GLM 双计费形态监控（同一厂商含按量付费与 Coding Plan 订阅套餐两种计费，Key 相互独立）。
 * <ul>
 *   <li>订阅套餐（Coding Plan 订阅 Key）：GET {base}/api/monitor/usage/quota/limit。
 *       国内 https://open.bigmodel.cn，国际 https://api.z.ai。普通按量 Key 调用会返回未授权。</li>
 *   <li>按量付费（普通 API Key）：GET {base}/api/paas/v4/balance 查询账户余额。
 *       <b>已实测端点存在（无/假 Key 返回 HTTP 401）。</b>响应结构为 balance 数组
 *       （每项含 total/available/used/model，为社区实现共认格式）。</li>
 *   <li>自动识别：先调套餐接口，未授权/非套餐错误时降级为按量余额查询。</li>
 * </ul>
 * 套餐响应结构（参考 CodexBar zai.js / opencode-glm-quota）：
 *   data.planName → 套餐档位（Lite/Pro…）
 *   data.limits[] → 额度窗口，type 为 TOKENS_LIMIT / CREDIT_LIMIT（5 小时、周窗口）
 *                   或 TIME_LIMIT（MCP），含 percentage / usage / currentValue /
 *                   remaining / nextResetTime（epoch ms）/ duration。
 */
public final class GlmProvider implements BalanceProvider {

    /** 国内默认 API 根地址（Coding Plan 套餐 Key） */
    private static final String DEFAULT_BASE_CN = "https://open.bigmodel.cn";
    /** 国际 z.ai 根地址（可经 Base URL 显式填写） */
    private static final String BASE_GLOBAL = "https://api.z.ai";
    private static final String QUOTA_PATH = "/api/monitor/usage/quota/limit";
    private static final String BALANCE_PATH = "/api/paas/v4/balance";

    private static final DateTimeFormatter RESET_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final HttpSupport http;

    public GlmProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("Base URL 无效").build();
        }
        return switch (config.effectiveBillingMode()) {
            case SUBSCRIPTION -> checkSubscription(config, base);
            case PAY_AS_YOU_GO -> checkPayAsYouGo(config, base);
            case AUTO -> probe(config, base);
        };
    }

    /** 订阅套餐模式：GET /api/monitor/usage/quota/limit（Coding Plan 订阅 Key）。 */
    private BalanceSnapshot checkSubscription(AccountConfig config, String base) {
        try {
            HttpSupport.Response resp = http.get(base + QUOTA_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("glm-subscription url=" + base + QUOTA_PATH + " status=" + resp.status()
                    + " body=" + truncate(resp.body()));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("该 Key 不是有效的 Coding Plan 订阅 Key（可能是按量付费 Key 或 Key 无效；"
                                + "订阅 Key 与按量 Key 相互独立，不能混用）").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            // 业务错误体（HTTP 200）：该 Key 无 Coding Plan 订阅
            if (root.get("data") == null && root.get("success") != null
                    && !root.get("success").asBoolean(false)) {
                String msg = JsonSupport.text(root, "msg");
                if (msg != null && msg.contains("coding plan")) {
                    return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                            .message("该 Key 无 Coding Plan 订阅（不是套餐 Key），请在编辑账户中将计费形式改为「按量付费」"
                                    + "；官方按量余额接口已下线（HTTP 404），暂无 API 可查余额，请在官网「财务总览」查看").build();
                }
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("套餐接口业务错误: " + msg).build();
            }
            return fromJson(config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 按量付费模式：GET /api/paas/v4/balance 查询账户余额（普通 API Key）。 */
    private BalanceSnapshot checkPayAsYouGo(AccountConfig config, String base) {
        try {
            HttpSupport.Response resp = http.get(base + BALANCE_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("glm-balance url=" + base + BALANCE_PATH + " status=" + resp.status()
                    + " body=" + truncate(resp.body()));
            // 官方按量余额端点对真实 Key 返回 404（已下线）→ 探测替代端点
            if (resp.status() == 404) {
                return probeAlternate(config, base);
            }
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("按量付费 API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return balanceFromJson(config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 探测替代端点（按量 Key 下官方 balance 已下线时尝试套餐配额/国际端点）。 */
    private BalanceSnapshot probeAlternate(AccountConfig config, String base) {
        String key = config.getApiKey().strip();
        String[] candidates = {
                base + QUOTA_PATH,
                BASE_GLOBAL + QUOTA_PATH,
                BASE_GLOBAL + "/api/usage"
        };
        for (String url : candidates) {
            try {
                HttpSupport.Response r = http.get(url,
                        Map.of("Authorization", "Bearer " + key, "Accept", "application/json"));
                HttpSupport.logDiagnostic("glm-alt url=" + url + " status=" + r.status()
                        + " body=" + truncate(r.body()));
                if (r.status() == 200) {
                    JsonNode root = r.bodyAsJson();
                    if (root != null && root.get("data") != null) {
                        return fromJson(config.getId(), config.getName(), root);
                    }
                    // 业务错误体（HTTP 200）：如 {"code":500,"msg":"当前用户不存在coding plan"}
                    if (root != null && root.get("success") != null && !root.get("success").asBoolean(false)) {
                        String msg = JsonSupport.text(root, "msg");
                        if (msg != null && msg.contains("coding plan")) {
                            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                                    BalanceSnapshot.Status.ERROR)
                                    .message("该 Key 无 Coding Plan 订阅；智谱官方按量余额接口已下线（HTTP 404），"
                                            + "暂无 API 可查按量余额，请在官网「财务总览」查看").build();
                        }
                    }
                }
            } catch (Exception ignore) {
                HttpSupport.logDiagnostic("glm-alt url=" + url + " ex=" + ignore.getClass().getSimpleName());
            }
        }
        return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                BalanceSnapshot.Status.ERROR)
                .message("智谱官方按量余额接口已下线（HTTP 404），替代端点均不可用；请在官网「财务总览」查看余额").build();
    }

    /** 自动识别：先套餐接口；未授权/非套餐错误时降级为按量余额查询。 */
    private BalanceSnapshot probe(AccountConfig config, String base) {
        try {
            HttpSupport.Response resp = http.get(base + QUOTA_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 200) {
                JsonNode root = resp.bodyAsJson();
                if (root != null && root.get("data") != null) {
                    return fromJson(config.getId(), config.getName(), root);
                }
            }
            // 套餐接口拒绝（非 Coding Plan 套餐 Key）→ 降级为按量余额查询
            return checkPayAsYouGo(config, base);
        } catch (HttpSupport.HttpFailure e) {
            // 网络问题（非 Key 类型问题）不降级，如实返回
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.GLM, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析按量付费余额响应：balance 数组，聚合可用/总额（可独立测试）。 */
    static BalanceSnapshot balanceFromJson(String accountId, String accountName, JsonNode root) {
        if (root == null || !root.isObject()) {
            return BalanceSnapshot.of(ProviderId.GLM, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
        }
        JsonNode arr = root.get("balance");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return BalanceSnapshot.of(ProviderId.GLM, accountId, accountName,
                    BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 balance 数组（非按量付费 Key？）").build();
        }
        double totalAvailable = 0;
        double totalTotal = 0;
        boolean any = false;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.GLM, accountId, accountName)
                .label("可用余额").currency("CNY");
        for (JsonNode item : arr) {
            Double available = JsonSupport.number(item, "available");
            Double total = JsonSupport.number(item, "total");
            if (available != null) {
                totalAvailable += available;
                any = true;
            }
            if (total != null) {
                totalTotal += total;
            }
            String model = JsonSupport.text(item, "model");
            if (available != null && model != null) {
                b.detail("模型余额", model + " 可用 " + formatMoney(available)
                        + (total != null ? " / 总额 " + formatMoney(total) : ""));
            }
        }
        if (!any) {
            return BalanceSnapshot.of(ProviderId.GLM, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("未解析到余额字段（available）").build();
        }
        b.amount(totalAvailable);
        b.message(String.format("按量账户可用 %.2f 元", totalAvailable));
        if (totalTotal > 0) {
            b.detail("总额", formatMoney(totalTotal));
            b.detail("已用", formatMoney(Math.max(0, totalTotal - totalAvailable)));
        }
        b.detail("计量口径", "按量付费账户余额（普通 API Key）");
        return b.build();
    }

    private static String formatMoney(double v) {
        return String.format("%,.2f 元", v);
    }

    /** 解析配额响应（可独立测试）。 */
    static BalanceSnapshot fromJson(String accountId, String accountName, JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            return BalanceSnapshot.of(ProviderId.GLM, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应缺少 data 字段").build();
        }
        String plan = JsonSupport.text(data, "planName");
        if (plan == null || plan.isBlank()) {
            plan = JsonSupport.text(data, "plan");
        }

        List<QuotaWindow> windows = new ArrayList<>();
        JsonNode limits = data.get("limits");
        if (limits != null && limits.isArray()) {
            for (JsonNode lim : limits) {
                QuotaWindow w = parseWindow(lim);
                if (w != null) {
                    windows.add(w);
                }
            }
        }
        windows.sort((a, b) -> Double.compare(
                a.durationMinutes == null ? 1e18 : a.durationMinutes,
                b.durationMinutes == null ? 1e18 : b.durationMinutes));

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.GLM, accountId, accountName)
                .label("Coding Plan 额度").currency("%");
        if (plan != null && !plan.isBlank()) {
            b.detail("套餐档位", plan);
        }
        if (windows.isEmpty()) {
            return BalanceSnapshot.of(ProviderId.GLM, accountId, accountName,
                    BalanceSnapshot.Status.ERROR)
                    .message("未解析到额度窗口（非 Coding Plan 套餐 Key？）").build();
        }

        QuotaWindow primary = windows.get(0);
        b.amount(100.0 - primary.percentage);
        b.percent(100.0 - primary.percentage);
        b.message(String.format("5 小时窗口剩余 %.0f%%", 100.0 - primary.percentage));
        for (QuotaWindow w : windows) {
            b.detail(windowLabel(w), windowText(w));
        }
        b.detail("计量口径", "积分(credits) 或 tokens，以接口返回为准");
        return b.build();
    }

    private static QuotaWindow parseWindow(JsonNode lim) {
        String type = JsonSupport.text(lim, "type");
        if (type == null) {
            return null;
        }
        Double percentage = JsonSupport.number(lim, "percentage");
        Double durationMinutes = parseDurationMinutes(lim);
        Double remaining = JsonSupport.number(lim, "remaining");
        Double currentValue = JsonSupport.number(lim, "currentValue");
        Double usage = JsonSupport.number(lim, "usage");
        Long nextReset = longValue(lim, "nextResetTime");

        String windowKind;
        if ("TIME_LIMIT".equals(type)) {
            windowKind = "MCP";
        } else if (durationMinutes == null) {
            windowKind = "窗口";
        } else {
            windowKind = durationMinutes <= 360 ? "5 小时窗口" : "周窗口";
        }
        if (percentage == null && usage != null && currentValue != null && currentValue > 0) {
            percentage = usage / currentValue * 100.0;
        }
        if (percentage == null && remaining != null) {
            Double limit = JsonSupport.number(lim, "limit");
            if (limit != null && limit > 0) {
                percentage = (limit - remaining) / limit * 100.0;
            }
        }
        if (percentage == null) {
            percentage = 0.0;
        }
        return new QuotaWindow(windowKind, percentage, remaining, durationMinutes, nextReset);
    }

    private static Double parseDurationMinutes(JsonNode lim) {
        Double number = JsonSupport.number(lim, "duration");
        if (number != null) {
            return number;
        }
        Double durationInMins = JsonSupport.number(lim, "durationInMins");
        if (durationInMins != null) {
            return durationInMins;
        }
        String unit = JsonSupport.text(lim, "durationUnit");
        Double value = JsonSupport.number(lim, "durationValue");
        if (unit != null && value != null) {
            return switch (unit.toLowerCase()) {
                case "minute", "minutes", "min", "mins" -> value;
                case "hour", "hours", "h" -> value * 60;
                case "day", "days", "d" -> value * 1440;
                case "week", "weeks", "w" -> value * 10080;
                default -> null;
            };
        }
        return null;
    }

    private static Long longValue(JsonNode node, String field) {
        Double v = JsonSupport.number(node, field);
        return v == null ? null : v.longValue();
    }

    private static String windowLabel(QuotaWindow w) {
        return w.nextReset == null ? w.kind : w.kind + "（重置 " + RESET_FMT.format(Instant.ofEpochMilli(w.nextReset)) + "）";
    }

    private static String windowText(QuotaWindow w) {
        if (w.remaining != null) {
            return String.format("剩余 %.2f · 已用 %.0f%%", w.remaining, w.percentage);
        }
        return String.format("已用 %.0f%% · 剩余 %.0f%%", w.percentage, 100.0 - w.percentage);
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_CN;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return DEFAULT_BASE_CN;
        }
        return s;
    }

    /** 诊断日志截断（不记录 API Key；URL 本身不含敏感信息）。 */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }

    /** 单个额度窗口（解析结果，供测试断言） */
    static final class QuotaWindow {
        final String kind;
        final double percentage;
        final Double remaining;
        final Double durationMinutes;
        final Long nextReset;

        QuotaWindow(String kind, double percentage, Double remaining, Double durationMinutes, Long nextReset) {
            this.kind = kind;
            this.percentage = percentage;
            this.remaining = remaining;
            this.durationMinutes = durationMinutes;
            this.nextReset = nextReset;
        }
    }
}
