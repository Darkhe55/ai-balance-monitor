package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * Mistral 专用 Provider。
 * 官方账单接口（与 OpenUsage 等工具同源的内部账单端点，需 API Key 具备账单/Billing 权限）：
 * <ul>
 *   <li>GET {base}/v1/billing/subscription → 返回账户 Credits 余额、月度预算、套餐名称、币种（通常 EUR）。</li>
 *   <li>GET {base}/v1/billing/usage?start_date=YYYY-MM-01&amp;end_date=YYYY-MM-DD → 按天返回当月花费（total_cost）。</li>
 * </ul>
 * 官方 Admin API（/v1/admin/usage）仅 Enterprise 计划可用且不对外开放，故不采用。
 *
 * <p>响应结构（订阅）示例：
 * <pre>{@code
 * {
 *   "plan_name": "Pay-as-you-go",
 *   "credit_balance": 42.50,
 *   "monthly_budget": 100,
 *   "currency": "EUR"
 * }
 * }</pre>
 * 用量接口返回按天的聚合数组，每项含 {@code date} 与 {@code total_cost}。
 */
public final class MistralProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.mistral.ai";

    private final HttpSupport http;
    private final ProviderId pid;

    public MistralProvider(HttpSupport http) {
        this(http, ProviderId.MISTRAL);
    }

    public MistralProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需要配置 Base URL").build();
        }
        String auth = "Bearer " + config.getApiKey().strip();

        try {
            // 第一步：订阅/账单信息（Credits 余额、月度预算、套餐）
            HttpSupport.Response subResp = http.get(base + "/v1/billing/subscription",
                    Map.of("Authorization", auth, "Accept", "application/json"));
            if (subResp.status() == 401 || subResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无账单权限").build();
            }
            if (subResp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询账单失败: HTTP " + subResp.status()).build();
            }
            JsonNode sub = subResp.bodyAsJson();
            if (sub == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("账单响应格式异常").build();
            }
            String currency = JsonSupport.text(sub, "currency");
            if (currency == null || currency.isBlank()) {
                currency = "EUR";
            }
            BalanceSnapshot.Builder builder = parseSubscription(pid, config.getId(), config.getName(), sub);

            // 第二步：当月用量（失败不阻断余额展示）
            JsonNode usage = null;
            try {
                YearMonth ym = YearMonth.now();
                String start = ym.atDay(1).toString();
                String end = LocalDate.now().toString();
                HttpSupport.Response usageResp = http.get(base + "/v1/billing/usage"
                                + "?start_date=" + start + "&end_date=" + end,
                        Map.of("Authorization", auth, "Accept", "application/json"));
                if (usageResp.status() == 200) {
                    usage = usageResp.bodyAsJson();
                }
            } catch (Exception ignored) {
                // 用量接口可选，失败时仅展示订阅信息
            }
            if (usage != null) {
                appendUsage(builder, usage, currency);
            }
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

    /** 解析订阅/账单响应（包内可见，供单元测试）。 */
    static BalanceSnapshot.Builder parseSubscription(ProviderId pid, String accountId, String accountName,
                                                     JsonNode sub) {
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);

        Double credit = JsonSupport.number(sub, "credit_balance");
        Double budget = JsonSupport.number(sub, "monthly_budget");
        String plan = JsonSupport.text(sub, "plan_name");
        String currency = JsonSupport.text(sub, "currency");
        if (currency == null || currency.isBlank()) {
            currency = "EUR";
        }

        if (credit != null) {
            builder.label("账户 Credits").currency(currency).amount(credit);
            builder.metric(ProviderCapability.BALANCE, "剩余 Credits", credit, currency, null,
                    "Mistral 账户预付费 Credits 余额");
        } else {
            builder.label("账户 Credits").currency(currency).amount(null)
                    .message("账单响应缺少 credit_balance");
        }
        if (budget != null) {
            builder.detail("月度预算", String.format("%.2f %s", budget, currency));
            Double percent = (credit != null && budget > 0)
                    ? Math.max(0, Math.min(100, 100.0 * credit / budget)) : null;
            builder.metric(ProviderCapability.SPEND_LIMIT, "月度预算", budget, currency, percent,
                    "Mistral 月度花费上限");
        }
        if (plan != null && !plan.isBlank()) {
            builder.detail("套餐", plan);
        }
        builder.detail("币种", currency);
        return builder;
    }

    /** 合并当月用量（每日聚合 total_cost 求和；接口失败或缺失时忽略）。 */
    static void appendUsage(BalanceSnapshot.Builder builder, JsonNode usage, String currency) {
        Double monthlyCost = sumDailyCost(usage);
        if (monthlyCost == null) {
            return;
        }
        if (currency == null || currency.isBlank()) {
            currency = "EUR";
        }
        builder.detail("本月花费", String.format("%.2f %s", monthlyCost, currency));
        builder.metric(ProviderCapability.COST, "本月花费", monthlyCost, currency, null,
                "Mistral 当月累计花费（账单用量接口）");
    }

    /** 从 /v1/billing/usage 响应中求和当月花费：兼容数组根节点与 { "daily_cost": [...] } 等常见结构。 */
    static Double sumDailyCost(JsonNode usage) {
        if (usage == null) {
            return null;
        }
        JsonNode arr = usage.isArray() ? usage : usage.get("daily_cost");
        if (arr == null || !arr.isArray()) {
            // 兜底：某些版本返回 { "costs": [...] } 或 { "data": [...] }
            arr = usage.get("costs");
            if (arr == null || !arr.isArray()) {
                arr = usage.get("data");
            }
        }
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        double total = 0;
        boolean found = false;
        for (JsonNode day : arr) {
            Double cost = JsonSupport.number(day, "total_cost");
            if (cost == null) {
                cost = JsonSupport.number(day, "cost");
            }
            if (cost != null) {
                total += cost;
                found = true;
            }
        }
        return found ? total : null;
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
}