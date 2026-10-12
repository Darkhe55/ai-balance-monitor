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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MiniMax 双计费形态监控（同一厂商含按量付费与 Token Plan 订阅套餐两种计费，Key 相互独立）。
 * <ul>
 *   <li>订阅套餐（Token Plan 订阅 Key）：GET {base}/v1/token_plan/remains。
 *       国内默认 https://api.minimaxi.com，国际 https://api.minimax.io。
 *       <b>实测：该接口 HTTP 状态码恒为 200，业务错误在响应体 base_resp.status_code
 *       （1004 = 鉴权失败，即 Key 不是有效的 Token Plan 订阅 Key）。</b></li>
 *   <li>按量付费（普通 API Key）：GET {base}/v1/models（OpenAI 兼容）校验 Key 有效性。
 *       <b>实测：假 Key 返回 HTTP 401（空响应体），有效 Key 返回 200。</b>
 *       钱包余额无公开 API（需控制台「账户管理 &gt; 余额」查看），如实标注。</li>
 *   <li>自动识别：先调套餐接口；base_resp.status_code 非 0 时自动降级为按量付费校验。</li>
 * </ul>
 * 响应结构（套餐，参考 coding-usage-bar 等项目的解析）：
 *   每个模型返回两种信号之一：
 *     - 计数制：current_&lt;model&gt;_usage_count / current_&lt;model&gt;_total_count（如 video）
 *     - 积分制：current_&lt;model&gt;_remaining_percent（0-100，general 模型）
 *   另有 end_time（5 小时窗口重置）、weekly_end_time（周窗口重置）。
 */
public final class MiniMaxProvider implements BalanceProvider {

    private static final String DEFAULT_BASE_CN = "https://www.minimaxi.com";
    private static final String DEFAULT_BASE_INTL = "https://www.minimax.io";
    private static final String REMAINS_PATH = "/v1/token_plan/remains";

    private static final DateTimeFormatter RESET_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final HttpSupport http;

    public MiniMaxProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = config.getProviderId() == null
                ? ProviderId.MINIMAX : config.getProviderId();
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("Base URL 无效").build();
        }
        return switch (config.effectiveBillingMode()) {
            case SUBSCRIPTION -> checkSubscription(config, base, pid);
            case PAY_AS_YOU_GO -> checkPayAsYouGo(config, base, pid);
            case AUTO -> probe(config, base, pid);
        };
    }

    /** 订阅套餐模式：GET /v1/token_plan/remains，识别 base_resp.status_code。 */
    private BalanceSnapshot checkSubscription(AccountConfig config, String base, ProviderId pid) {
        try {
            HttpSupport.Response resp = http.get(base + REMAINS_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("minimax-subscription url=" + base + REMAINS_PATH
                    + " status=" + resp.status() + " body=" + truncate(resp.body(), 900));
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            // 实测：业务错误在 base_resp.status_code（HTTP 恒 200），1004 = 鉴权失败
            int code = baseRespCode(root);
            if (code != 0) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("该 Key 不是有效的 Token Plan 订阅 Key（可能是按量付费 Key 或 Key 无效；"
                                + "订阅 Key 与按量 Key 相互独立，不能混用）").build();
            }
            return fromJson(pid, config.getId(), config.getName(), root);
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

    /** 按量付费模式：GET /v1/models 校验 Key 有效性（钱包余额无公开 API）。 */
    private BalanceSnapshot checkPayAsYouGo(AccountConfig config, String base, ProviderId pid) {
        String modelsPath = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        try {
            HttpSupport.Response resp = http.get(modelsPath,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("minimax-payg url=" + modelsPath
                    + " status=" + resp.status() + " body=" + truncate(resp.body(), 300));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("按量付费 API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            return payAsYouGoOk(config, resp, pid);
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

    /** 自动识别：先套餐接口；业务拒绝（base_resp 非 0）或套餐响应无法解析时降级为按量校验。 */
    private BalanceSnapshot probe(AccountConfig config, String base, ProviderId pid) {
        try {
            HttpSupport.Response resp = http.get(base + REMAINS_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("minimax-auto url=" + base + REMAINS_PATH
                    + " status=" + resp.status() + " body=" + truncate(resp.body(), 900));
            if (resp.status() == 200) {
                JsonNode root = resp.bodyAsJson();
                if (root != null && !bizRejected(root)) {
                    BalanceSnapshot sub = fromJson(pid, config.getId(), config.getName(), root);
                    if (sub.status() == BalanceSnapshot.Status.OK) {
                        return sub;
                    }
                    // 套餐接口通过鉴权但未解析到额度字段：可能为按量付费 Key，降级按量校验
                    BalanceSnapshot alt = checkPayAsYouGo(config, base, pid);
                    if (alt.status() == BalanceSnapshot.Status.OK) {
                        return alt;
                    }
                    return sub;
                }
            }
            // 套餐接口业务拒绝（非套餐 Key）→ 降级为按量付费校验
            return checkPayAsYouGo(config, base, pid);
        } catch (HttpSupport.HttpFailure e) {
            // 网络问题（非 Key 类型问题）不降级，如实返回
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 按量付费校验成功：Key 有效 + 尽力读取速率配额（网关返回时展示）。 */
    static BalanceSnapshot payAsYouGoOk(AccountConfig config, HttpSupport.Response resp) {
        return payAsYouGoOk(config, resp, config.getProviderId() == null
                ? ProviderId.MINIMAX : config.getProviderId());
    }

    static BalanceSnapshot payAsYouGoOk(AccountConfig config, HttpSupport.Response resp, ProviderId pid) {
        Long tokensRemaining = OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-tokens");
        Long tokensLimit = OpenAIProvider.longHeader(resp, "x-ratelimit-limit-tokens");
        Long requestsRemaining = OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-requests");
        Long requestsLimit = OpenAIProvider.longHeader(resp, "x-ratelimit-limit-requests");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName())
                .label("按量 Key 有效");
        if (tokensRemaining != null && tokensLimit != null) {
            b.currency("tokens").amount(tokensRemaining.doubleValue());
            b.detail("配额上限", String.format("%,d tokens / 分钟", tokensLimit));
        } else {
            b.message("钱包余额无公开 API，请到 MiniMax 控制台「账户管理 > 余额」查看");
        }
        if (requestsRemaining != null) {
            b.detail("请求配额剩余", requestsLimit != null
                    ? String.format("%,d / %,d 次/分钟", requestsRemaining, requestsLimit)
                    : String.format("%,d 次/分钟", requestsRemaining));
        }
        return b.build();
    }

    /** 读取 base_resp.status_code；无该字段时按 0（成功）处理。 */
    private static int baseRespCode(JsonNode root) {
        if (root == null) {
            return 0;
        }
        JsonNode baseResp = root.get("base_resp");
        if (baseResp == null || !baseResp.isObject()) {
            return 0;
        }
        Double code = JsonSupport.number(baseResp, "status_code");
        return code == null ? 0 : code.intValue();
    }

    /** 套餐接口是否业务拒绝（base_resp.status_code 非 0），供自动识别与测试使用。 */
    static boolean bizRejected(JsonNode root) {
        return baseRespCode(root) != 0;
    }

    /**
     * 解析 Token Plan 剩余额度响应（可独立测试；默认按中国站 MINIMAX 归属）。
     */
    static BalanceSnapshot fromJson(String accountId, String accountName, JsonNode root) {
        return fromJson(ProviderId.MINIMAX, accountId, accountName, root);
    }

    /**
     * 解析 Token Plan 剩余额度响应（可独立测试；支持中国站/国际站 ProviderId）。
     * 实测响应结构（2026-10，真实套餐 Key）：额度数据在 {@code model_remains[]} 数组中，
     * 每个元素含 model_name / current_interval_remaining_percent /
     * current_weekly_remaining_percent / current_interval_usage_count / _total_count /
     * end_time / weekly_end_time（epoch 毫秒）等；旧接口的顶层字段仍兼容。
     */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        if (root == null || !root.isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
        }

        List<ModelRemain> models = parseModelRemains(root);

        // 5 小时窗口：general 模型优先，其次任一有 interval 剩余百分比的模型
        Double fiveHourRemaining = null;
        if (!models.isEmpty()) {
            ModelRemain general = models.stream()
                    .filter(m -> "general".equals(m.modelName))
                    .findFirst().orElse(models.get(0));
            fiveHourRemaining = general.intervalPercent;
        }
        if (fiveHourRemaining == null) {
            fiveHourRemaining = remainingPercent(root, "general");
        }
        Double used5h = null;
        if (fiveHourRemaining != null) {
            used5h = 100.0 - fiveHourRemaining;
        }
        if (used5h == null) {
            used5h = countRatio(root);
        }

        // 周窗口：general 模型的 weekly 剩余百分比优先，其次最紧窗口
        Double weeklyRemaining = null;
        if (!models.isEmpty()) {
            ModelRemain general = models.stream()
                    .filter(m -> "general".equals(m.modelName))
                    .findFirst().orElse(models.get(0));
            weeklyRemaining = general.weeklyPercent;
        }
        Double weeklyUsed = null;
        if (weeklyRemaining != null) {
            weeklyUsed = 100.0 - weeklyRemaining;
        } else {
            Double top = remainingPercent(root, "weekly");
            if (top != null) {
                weeklyUsed = 100.0 - top;
            } else {
                Double tightest = tightestRemaining(root);
                if (tightest != null) {
                    weeklyUsed = 100.0 - tightest;
                }
            }
        }

        String endTime = topText(root, "end_time");
        String weeklyEnd = topText(root, "weekly_end_time");
        if (endTime == null && !models.isEmpty()) {
            endTime = epochMillis(models.get(0).endTime);
        }
        if (weeklyEnd == null && !models.isEmpty()) {
            weeklyEnd = epochMillis(models.get(0).weeklyEndTime);
        }

        if (used5h == null && weeklyUsed == null) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR)
                    .message("未解析到套餐窗口（非 Token Plan 订阅 Key？）").build();
        }

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("Token Plan 额度").currency("%");
        if (used5h != null) {
            b.amount(100.0 - used5h);
            b.percent(100.0 - used5h);
            b.message(String.format("5 小时窗口剩余 %.0f%%", 100.0 - used5h));
            b.detail("5 小时窗口", String.format("已用 %.0f%% · 剩余 %.0f%%", used5h, 100.0 - used5h));
            if (endTime != null && !endTime.isBlank()) {
                b.detail("5 小时重置", formatReset(endTime));
            }
        }
        if (weeklyUsed != null) {
            b.detail("周窗口", String.format("已用 %.0f%% · 剩余 %.0f%%", weeklyUsed, 100.0 - weeklyUsed));
            if (weeklyEnd != null && !weeklyEnd.isBlank()) {
                b.detail("周窗口重置", formatReset(weeklyEnd));
            }
        }
        // 各模型额度明细（model_remains[] 元素）
        if (!models.isEmpty()) {
            for (ModelRemain m : models) {
                if (m.intervalPercent != null || m.weeklyPercent != null) {
                    b.detail("模型 " + m.modelName,
                            String.format("5h 剩余 %.0f%% · 周剩余 %.0f%%",
                                    m.intervalPercent == null ? -1 : m.intervalPercent,
                                    m.weeklyPercent == null ? -1 : m.weeklyPercent));
                }
            }
        }
        b.detail("计量口径", "Token Plan 订阅额度（现金余额需控制台查看）");
        return b.build();
    }

    /** model_remains[] 数组元素（实测真实结构）。 */
    private static final class ModelRemain {
        final String modelName;
        final Double intervalPercent;
        final Double weeklyPercent;
        final Long endTime;
        final Long weeklyEndTime;

        ModelRemain(String modelName, Double intervalPercent, Double weeklyPercent,
                    Long endTime, Long weeklyEndTime) {
            this.modelName = modelName;
            this.intervalPercent = intervalPercent;
            this.weeklyPercent = weeklyPercent;
            this.endTime = endTime;
            this.weeklyEndTime = weeklyEndTime;
        }
    }

    private static List<ModelRemain> parseModelRemains(JsonNode root) {
        List<ModelRemain> out = new java.util.ArrayList<>();
        JsonNode arr = root.get("model_remains");
        if (arr != null && arr.isArray()) {
            for (JsonNode m : arr) {
                if (m == null || !m.isObject()) {
                    continue;
                }
                String name = JsonSupport.text(m, "model_name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                Double interval = JsonSupport.number(m, "current_interval_remaining_percent");
                Double weekly = JsonSupport.number(m, "current_weekly_remaining_percent");
                Long end = JsonSupport.longNumber(m, "end_time");
                Long weeklyEnd = JsonSupport.longNumber(m, "weekly_end_time");
                out.add(new ModelRemain(name, interval, weekly, end, weeklyEnd));
            }
        }
        return out;
    }

    /** 顶层文本字段（缺失时返回 null）。 */
    private static String topText(JsonNode root, String field) {
        return JsonSupport.text(root, field);
    }

    private static String epochMillis(Long v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 找 current_&lt;name&gt;_remaining_percent 字段（name 优先指定值，其次任意模型）。 */
    private static Double remainingPercent(JsonNode root, String preferred) {
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (!key.startsWith("current_") || !key.endsWith("_remaining_percent")) {
                continue;
            }
            if (preferred != null && !key.equals("current_" + preferred + "_remaining_percent")) {
                continue;
            }
            Double v = JsonSupport.number(root, key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 所有 remaining_percent 中的最小值（用于近似"最紧"的窗口）。 */
    private static Double tightestRemaining(JsonNode root) {
        Double min = null;
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (!key.startsWith("current_") || !key.endsWith("_remaining_percent")) {
                continue;
            }
            Double v = JsonSupport.number(root, key);
            if (v != null && (min == null || v < min)) {
                min = v;
            }
        }
        return min;
    }

    /** 计数制窗口：current_*_usage_count / current_*_total_count 的已用百分比。 */
    private static Double countRatio(JsonNode root) {
        Double used = null;
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (!key.startsWith("current_") || !key.endsWith("_usage_count")) {
                continue;
            }
            String totalKey = key.substring(0, key.length() - "_usage_count".length()) + "_total_count";
            Double total = JsonSupport.number(root, totalKey);
            if (total != null && total > 0) {
                Double usage = JsonSupport.number(root, key);
                if (usage != null) {
                    used = usage / total * 100.0;
                    break;
                }
            }
        }
        return used;
    }

    private static String formatReset(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            if (raw.matches("\\d{12,13}")) {
                // 实测：end_time/weekly_end_time 为 epoch 毫秒
                return RESET_FMT.format(Instant.ofEpochMilli(Long.parseLong(raw)));
            }
            return RESET_FMT.format(Instant.parse(raw));
        } catch (Exception ignored) {
            return raw;
        }
    }

    /** 截断响应体用于诊断日志（不记录 Key，仅排障用）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').strip();
        return t.length() <= max ? t : t.substring(0, max) + "…(" + t.length() + "chars)";
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
}
