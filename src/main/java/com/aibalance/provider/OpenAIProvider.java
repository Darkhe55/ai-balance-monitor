package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 双入口：
 * <ul>
 *   <li>OPENAI（API Key，模型调用专用）：GET /v1/models 校验 Key 并读取速率配额头，
 *       明确标注该入口 Key 无法查询费用/用量，需要 Admin Key。</li>
 *   <li>OPENAI_ADMIN（Admin Key，组织管理员）：GET /v1/organization/costs（多维费用，
 *       group_by=project_id+line_item）与 GET /v1/organization/usage/completions
 *       （用量，group_by=model）两个请求合并显示。</li>
 * </ul>
 * 官方组织 API（platform.openai.com/docs/api-reference/usage 已核实）仅公开 costs 与
 * usage 两类端点；未公开 spend limit 查询端点，故不声明 SPEND_LIMIT 能力（如实标注）。
 */
public final class OpenAIProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.openai.com";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final long DAY_SECONDS = 86_400L;

    private final HttpSupport http;

    public OpenAIProvider(HttpSupport http) {
        this.http = http;
    }

    /** Base URL 归一化：去尾斜杠，空值回退官方默认。 */
    static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? DEFAULT_BASE : s;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(config.getProviderId(), config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (config.getProviderId() == ProviderId.OPENAI_ADMIN) {
            return checkAdmin(config, base);
        }
        return checkApiKey(config, base);
    }

    /** Admin Key 入口：costs + usage/completions 两个请求合并。 */
    private BalanceSnapshot checkAdmin(AccountConfig config, String base) {
        ProviderId pid = ProviderId.OPENAI_ADMIN;
        String key = config.getApiKey().strip();
        long now = java.time.Instant.now().getEpochSecond();
        long start = now - 30 * DAY_SECONDS;
        try {
            // 请求 1：多维费用（按项目 + line item 分组）
            HttpSupport.Response costResp = http.get(base + "/v1/organization/costs"
                            + "?start_time=" + start + "&end_time=" + now + "&bucket_width=1d&limit=31"
                            + "&group_by=project_id&group_by=line_item",
                    Map.of("Authorization", "Bearer " + key, "Accept", "application/json"));
            HttpSupport.logDiagnostic("openai-costs url=" + base + "/v1/organization/costs status="
                    + costResp.status() + " body=" + truncate(costResp.body()));
            if (costResp.status() == 401 || costResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Admin Key 无效或无组织费用/用量权限").build();
            }
            if (costResp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + costResp.status() + " " + DeepSeekProvider.firstError(costResp.body())).build();
            }
            JsonNode costRoot = costResp.bodyAsJson();
            if (costRoot == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("费用响应格式异常").build();
            }

            // 请求 2：completions 用量（按模型分组）
            HttpSupport.Response usageResp = http.get(base + "/v1/organization/usage/completions"
                            + "?start_time=" + start + "&end_time=" + now + "&bucket_width=1d&limit=31"
                            + "&group_by=model",
                    Map.of("Authorization", "Bearer " + key, "Accept", "application/json"));
            HttpSupport.logDiagnostic("openai-usage url=" + base + "/v1/organization/usage/completions status="
                    + usageResp.status() + " body=" + truncate(usageResp.body()));
            if (usageResp.status() == 401 || usageResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Admin Key 无用量查询权限（401）").build();
            }
            JsonNode usageRoot = usageResp.status() == 200 ? usageResp.bodyAsJson() : null;

            BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName());
            mergeCosts(b, costRoot);
            if (usageRoot != null) {
                mergeUsage(b, usageRoot);
            } else if (usageResp.status() != 200) {
                b.detail("用量接口", "HTTP " + usageResp.status() + " " + DeepSeekProvider.firstError(usageResp.body()));
            }
            if (b.metricOf(ProviderCapability.COST) == null && b.metricOf(ProviderCapability.USAGE) == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("未解析到费用/用量数据").build();
            }
            b.detail("计量口径", "官方组织 API：/v1/organization/costs + /v1/organization/usage/completions（近 30 天，按日）");
            b.message("Admin Key 组织费用与用量（近 30 天）");
            return b.build();
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

    /** 合并 costs 响应为 COST 区块 + 明细（按 line_item / project 分解）。 */
    static void mergeCosts(BalanceSnapshot.Builder b, JsonNode root) {
        double total = 0;
        java.util.Map<String, Double> byLine = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> byProject = new java.util.LinkedHashMap<>();
        String currency = null;
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode bucket : data) {
                JsonNode results = bucket.get("results");
                if (results == null || !results.isArray()) {
                    continue;
                }
                for (JsonNode r : results) {
                    JsonNode amount = r.get("amount");
                    if (amount == null) {
                        continue;
                    }
                    Double value = JsonSupport.number(amount, "value");
                    if (value == null) {
                        continue;
                    }
                    if (currency == null) {
                        currency = JsonSupport.text(amount, "currency");
                    }
                    total += value;
                    String lineItem = JsonSupport.text(r, "line_item");
                    if (lineItem != null) {
                        byLine.merge(lineItem, value, Double::sum);
                    }
                    String projectId = JsonSupport.text(r, "project_id");
                    if (projectId != null) {
                        byProject.merge(projectId, value, Double::sum);
                    }
                }
            }
        }
        if (currency == null) {
            currency = "USD";
        }
        if (total == 0 && byLine.isEmpty() && byProject.isEmpty()) {
            b.metric(ProviderCapability.COST, "近 30 天费用", 0.0, currency, null,
                    "官方组织 costs 接口返回空（本周期无消耗）");
            return;
        }
        b.metric(ProviderCapability.COST, "近 30 天费用", total, currency, null, null);
        var topLines = byLine.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(6).toList();
        for (var e : topLines) {
            b.detail("费用项 " + e.getKey(), String.format("%.4f %s", e.getValue(), currency));
        }
        if (byProject.size() > 1) {
            var topProjects = byProject.entrySet().stream()
                    .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                    .limit(4).toList();
            for (var e : topProjects) {
                b.detail("项目 " + e.getKey(), String.format("%.4f %s", e.getValue(), currency));
            }
        }
    }

    /** 合并 usage/completions 响应为 USAGE 区块 + 模型明细。 */
    static void mergeUsage(BalanceSnapshot.Builder b, JsonNode root) {
        long totalInput = 0;
        long totalOutput = 0;
        long totalCached = 0;
        long totalRequests = 0;
        java.util.Map<String, long[]> byModel = new java.util.LinkedHashMap<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode bucket : data) {
                JsonNode results = bucket.get("results");
                if (results == null || !results.isArray()) {
                    continue;
                }
                for (JsonNode r : results) {
                    long input = longVal(r, "input_tokens");
                    long output = longVal(r, "output_tokens");
                    long cached = longVal(r, "input_cached_tokens");
                    long reqs = longVal(r, "num_model_requests");
                    totalInput += input;
                    totalOutput += output;
                    totalCached += cached;
                    totalRequests += reqs;
                    String model = JsonSupport.text(r, "model");
                    if (model != null) {
                        long[] agg = byModel.computeIfAbsent(model, k -> new long[3]);
                        agg[0] += input;
                        agg[1] += output;
                        agg[2] += reqs;
                    }
                }
            }
        }
        if (totalInput + totalOutput + totalRequests == 0 && byModel.isEmpty()) {
            b.metric(ProviderCapability.USAGE, "近 30 天用量", 0.0, "tokens", null,
                    "官方 usage 接口返回空（本周期无调用）");
            return;
        }
        b.metric(ProviderCapability.USAGE, "近 30 天 Tokens", (double) (totalInput + totalOutput), "tokens", null,
                String.format("输入 %,d · 输出 %,d · 缓存输入 %,d · 请求 %,d 次",
                        totalInput, totalOutput, totalCached, totalRequests));
        byModel.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0] + y.getValue()[1], x.getValue()[0] + x.getValue()[1]))
                .limit(6)
                .forEach(e -> b.detail("模型 " + e.getKey(),
                        String.format("输入 %,d · 输出 %,d · %,d 次请求", e.getValue()[0], e.getValue()[1], e.getValue()[2])));
    }

    private static long longVal(JsonNode node, String field) {
        Double v = JsonSupport.number(node, field);
        return v == null ? 0 : v.longValue();
    }

    /** API Key 入口：模型调用专用，仅校验 + 速率配额，明确标注无法查费用/用量。 */
    private BalanceSnapshot checkApiKey(AccountConfig config, String base) {
        ProviderId pid = ProviderId.OPENAI;
        try {
            HttpSupport.Response resp = http.get(base + "/v1/models",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }

            Long tokensRemaining = longHeader(resp, "x-ratelimit-remaining-tokens");
            Long tokensLimit = longHeader(resp, "x-ratelimit-limit-tokens");
            Long requestsRemaining = longHeader(resp, "x-ratelimit-remaining-requests");
            Long requestsLimit = longHeader(resp, "x-ratelimit-limit-requests");

            BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName());
            if (tokensRemaining != null && tokensLimit != null) {
                b.label("令牌配额剩余").currency("tokens").amount(tokensRemaining.doubleValue());
                b.percent(tokensLimit > 0 ? 100.0 * tokensRemaining / tokensLimit : null);
                b.detail("配额上限", String.format("%,d tokens / 分钟", tokensLimit));
            } else {
                b.label("账户状态");
                b.message("未返回速率配额信息");
            }
            if (requestsRemaining != null) {
                b.detail("请求配额剩余", requestsLimit != null
                        ? String.format("%,d / %,d 次/分钟", requestsRemaining, requestsLimit)
                        : String.format("%,d 次/分钟", requestsRemaining));
            }
            b.detail("入口说明", "该 Key 仅用于模型调用，无法查询费用/用量；请在选型弹窗选择「OpenAI（Admin Key）」入口");
            return b.build();
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

    /** 诊断日志截断（不记录 API Key）。 */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }

    static Long longHeader(HttpSupport.Response resp, String name) {
        String v = resp.firstHeader(name);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}