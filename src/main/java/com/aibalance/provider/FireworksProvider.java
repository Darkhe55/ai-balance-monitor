package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fireworks AI Provider（海外模型平台：serverless / 专属部署 / 训练）。
 * <p>
 * Fireworks 为<b>按量后付费</b>模式，官方未提供账户余额接口（Quota Monitor 亦确认
 * "Costs only · Not a balance"）；因此本入口查询<b>本月费用 + 用量</b>（与 OpenAI Admin
 * Key 入口同模式），而非余额：
 * <ul>
 *   <li>费用：GET {base}/v1/accounts/{account_id}/billing/summary?startTime=本月1日&amp;endTime=明天0点</li>
 *   <li>用量：GET {base}/v1/accounts/{account_id}/billingUsage?startTime=…&amp;endTime=…</li>
 * </ul>
 * 认证 {@code Authorization: Bearer <API_KEY>}（目标账户上的普通 API Key 即可）。
 * 账户 ID 在「附加参数」填 {@code account_id=xxx}（获取方式：console.fireworks.ai → 账户，
 * 或 GET /v1/accounts?readMask=name,displayName 后取 name 中 accounts/ 后的部分）。
 * <p>
 * 响应字段（官方 OpenAPI 已核实）：
 * <pre>{@code
 * billing/summary: { "lineItems": [ {
 *   "category": "Text Completion", "groupingKey": "model", "groupingValue": "llama-...",
 *   "totalCost": { "currencyCode": "USD", "units": 1, "nanos": 250000000 }
 * } ] }
 * billingUsage:  { "serverlessCosts": [ { "modelName": "...", "promptTokens": "...",
 *   "completionTokens": "...", "cachedPromptTokens": "...", "costNanoUsd": 0 } ] }
 * }</pre>
 * {@code typeMoney} 的金额 = {@code units} + {@code nanos} / 1e9（nanos 可正可负）。
 */
public final class FireworksProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.fireworks.ai";
    private static final DateTimeFormatter RFC3339_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final HttpSupport http;
    private final ProviderId pid;

    public FireworksProvider(HttpSupport http) {
        this(http, ProviderId.FIREWORKS);
    }

    public FireworksProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 Fireworks API Key").build();
        }
        String accountId = config.parseExtraParams().get("account_id");
        if (accountId == null || accountId.isBlank()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED)
                    .message("缺少账户 ID：请在「附加参数」填写 account_id=xxx（console.fireworks.ai 账户页查看）").build();
        }
        try {
            String base = normalize(config.getBaseUrl());
            LocalDate today = LocalDate.now();
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            LocalDate tomorrow = today.plusDays(1);
            String startTime = firstOfMonth + "T00:00:00Z";
            String endTime = tomorrow + "T00:00:00Z";
            String accountPath = accountId.strip();
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + config.getApiKey().strip(),
                    "Accept", "application/json");

            // 请求 1：本月费用账单汇总
            HttpSupport.Response summaryResp = http.get(base + "/v1/accounts/" + accountPath
                            + "/billing/summary?startTime=" + startTime + "&endTime=" + endTime,
                    headers);
            HttpSupport.logDiagnostic("fireworks-summary url=" + base + "/v1/accounts/***/billing/summary status="
                    + summaryResp.status() + " body=" + truncate(summaryResp.body()));
            if (summaryResp.status() == 401 || summaryResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Fireworks API Key 无效或无账户费用权限").build();
            }
            if (summaryResp.status() == 404) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("账户 ID 不存在或路径错误（HTTP 404，请核对 account_id）").build();
            }
            if (summaryResp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + summaryResp.status() + " " + DeepSeekProvider.firstError(summaryResp.body())).build();
            }
            JsonNode summaryRoot = summaryResp.bodyAsJson();
            if (summaryRoot == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("费用响应格式异常").build();
            }

            // 请求 2：本月用量（serverless tokens，汇总）
            long totalPrompt = 0;
            long totalCompletion = 0;
            long totalCached = 0;
            long totalCostNano = 0;
            try {
                HttpSupport.Response usageResp = http.get(base + "/v1/accounts/" + accountPath
                                + "/billingUsage?startTime=" + startTime + "&endTime=" + endTime,
                        headers);
                HttpSupport.logDiagnostic("fireworks-usage url=" + base + "/v1/accounts/***/billingUsage status="
                        + usageResp.status() + " body=" + truncate(usageResp.body()));
                if (usageResp.status() == 200) {
                    JsonNode usageRoot = usageResp.bodyAsJson();
                    if (usageRoot != null) {
                        JsonNode sc = usageRoot.get("serverlessCosts");
                        if (sc != null && sc.isArray()) {
                            for (JsonNode item : sc) {
                                totalPrompt += longVal(item, "promptTokens");
                                totalCompletion += longVal(item, "completionTokens");
                                totalCached += longVal(item, "cachedPromptTokens");
                                totalCostNano += nanoVal(item, "costNanoUsd");
                            }
                        }
                    }
                } else {
                    // 用量接口失败不影响费用展示，仅记录明细
                    // （继续，cost 已从 summary 拿到）
                }
            } catch (HttpSupport.HttpFailure ignored) {
                // 用量为辅助数据，费用已成功时忽略其网络失败
            }

            BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, config.getId(), config.getName());
            mergeSummary(builder, summaryRoot);
            if (totalPrompt + totalCompletion > 0) {
                builder.metric(ProviderCapability.USAGE, "本月 Tokens",
                        (double) (totalPrompt + totalCompletion), "tokens", null,
                        String.format("输入 %,d · 输出 %,d · 缓存输入 %,d", totalPrompt, totalCompletion, totalCached));
            } else if (totalCostNano > 0) {
                builder.detail("计量口径", "billingUsage 未返回 token 明细");
            }
            builder.detail("计量口径", "官方账户 API：billing/summary + billingUsage（本月，UTC）");
            builder.message("Fireworks 为后付费，展示本月费用与用量（无余额）");
            return builder.build();
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

    /** 解析 billing/summary 响应为 COST 区块 + 明细（可独立测试）。 */
    static void mergeSummary(BalanceSnapshot.Builder builder, JsonNode root) {
        double total = 0;
        String currency = "USD";
        Map<String, Double> byCategory = new LinkedHashMap<>();
        JsonNode lineItems = root.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                Double cost = moneyValue(item.get("totalCost"));
                if (cost == null) {
                    continue;
                }
                JsonNode money = item.get("totalCost");
                String cur = JsonSupport.text(money, "currencyCode");
                if (cur != null && !cur.isBlank()) {
                    currency = cur;
                }
                total += cost;
                String category = JsonSupport.text(item, "category");
                if (category == null || category.isBlank()) {
                    category = "其他";
                }
                byCategory.merge(category, cost, Double::sum);
            }
        }
        if (total == 0 && byCategory.isEmpty()) {
            builder.metric(ProviderCapability.COST, "本月费用", 0.0, currency, null,
                    "billing/summary 返回空（本周期无消耗）");
            return;
        }
        builder.metric(ProviderCapability.COST, "本月费用", total, currency, null,
                "Fireworks 账户本月账单费用（官方 billing/summary）");
        String displayCurrency = currency;
        byCategory.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(6)
                .forEach(e -> builder.detail("类别 " + e.getKey(),
                        String.format("%.4f %s", e.getValue(), displayCurrency)));
    }

    /** 解析 typeMoney：units + nanos / 1e9；字段缺失/非法返回 null。 */
    static Double moneyValue(JsonNode money) {
        if (money == null || !money.isObject()) {
            return null;
        }
        Double units = JsonSupport.number(money, "units");
        JsonNode nanosNode = money.get("nanos");
        long nanos = 0;
        if (nanosNode != null && nanosNode.isNumber()) {
            nanos = nanosNode.longValue();
        }
        if (units == null) {
            return null;
        }
        return units + nanos / 1_000_000_000.0;
    }

    private static long longVal(JsonNode node, String field) {
        Double v = JsonSupport.number(node, field);
        return v == null ? 0 : v.longValue();
    }

    private static long nanoVal(JsonNode node, String field) {
        Double v = JsonSupport.number(node, field);
        return v == null ? 0 : v.longValue();
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? DEFAULT_BASE : s;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}