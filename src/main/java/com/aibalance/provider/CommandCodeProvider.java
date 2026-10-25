package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * CommandCode 专用 Provider。
 * 官方 billing 接口（Bearer API Key 认证）：
 * <ul>
 *   <li>GET /alpha/whoami                          → 用户/组织信息（取 org.id）</li>
 *   <li>GET /alpha/billing/credits?orgId=…         → 信用额度 + 5 小时/每周用量窗口</li>
 *   <li>GET /alpha/billing/subscriptions?orgId=…   → 订阅信息（套餐、周期起止）</li>
 *   <li>GET /alpha/usage/summary?orgId=…&since=…   → 当前周期已用费用/次数（可选）</li>
 * </ul>
 */
public final class CommandCodeProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public CommandCodeProvider(HttpSupport http, ProviderId pid) {
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
            // 1) 取用户/组织信息（orgId 用于后续账单接口，非组织用户可缺省）
            String orgId = null;
            HttpSupport.Response whoami = http.get(base + "/alpha/whoami", headers(config));
            if (whoami.status() == 200 && whoami.bodyAsJson() != null) {
                JsonNode org = whoami.bodyAsJson().get("org");
                if (org != null && org.isObject()) {
                    orgId = JsonSupport.text(org, "id");
                }
            }

            // 2) 信用额度与用量窗口
            HttpSupport.Response creditsResp = http.get(withOrg(base + "/alpha/billing/credits", orgId), headers(config));
            if (creditsResp.status() == 401 || creditsResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效或无权限").build();
            }
            if (creditsResp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询 credits 失败: HTTP " + creditsResp.status()).build();
            }
            JsonNode credits = creditsResp.bodyAsJson();

            // 3) 订阅信息（失败不阻塞主展示）
            JsonNode subscriptions = null;
            try {
                HttpSupport.Response subResp = http.get(withOrg(base + "/alpha/billing/subscriptions", orgId), headers(config));
                if (subResp.status() == 200) {
                    subscriptions = subResp.bodyAsJson();
                }
            } catch (Exception ignored) {
                // 订阅接口异常时忽略，仅影响额外明细
            }

            // 4) 当前周期用量汇总（可选）
            JsonNode summary = null;
            String periodStart = subscriptionData(subscriptions) == null
                    ? null : JsonSupport.text(subscriptionData(subscriptions), "currentPeriodStart");
            if (periodStart != null && !periodStart.isBlank()) {
                try {
                    String summaryUrl = withOrg(base + "/alpha/usage/summary", orgId);
                    summaryUrl += summaryUrl.contains("?") ? "&" : "?";
                    summaryUrl += "since=" + encode(periodStart);
                    HttpSupport.Response sumResp = http.get(summaryUrl, headers(config));
                    if (sumResp.status() == 200) {
                        summary = sumResp.bodyAsJson();
                    }
                } catch (Exception ignored) {
                    // 用量汇总可选，失败不影响主展示
                }
            }

            return buildSnapshot(config, credits, subscriptions, summary);
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

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode credits,
                                          JsonNode subscriptions, JsonNode summary) {
        return parseBilling(pid, config.getId(), config.getName(), credits, subscriptions, summary);
    }

    /** 解析 billing 响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseBilling(ProviderId pid, String accountId, String accountName,
                                        JsonNode credits, JsonNode subscriptions, JsonNode summary) {
        JsonNode creditsData = credits != null && credits.has("credits") && credits.get("credits").isObject()
                ? credits.get("credits") : null;

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);

        // 主展示：月度剩余 Credits（美元）
        Double monthlyCredits = creditsData == null ? null : JsonSupport.number(creditsData, "monthlyCredits");
        if (monthlyCredits != null) {
            builder.label("月度剩余 Credits").currency("USD").amount(monthlyCredits);
            builder.metric(ProviderCapability.QUOTA, "月度剩余 Credits", monthlyCredits, "USD", null,
                    "CommandCode 信用额度");
        } else {
            builder.label("信用额度").currency("USD").amount(null);
        }

        // 信用构成
        if (creditsData != null) {
            Double purchased = JsonSupport.number(creditsData, "purchasedCredits");
            Double free = JsonSupport.number(creditsData, "freeCredits");
            String planId = JsonSupport.text(creditsData, "planId");
            if (purchased != null) {
                builder.detail("已购 Credits", String.format("$%.2f", purchased));
            }
            if (free != null) {
                builder.detail("赠送 Credits", String.format("$%.2f", free));
            }
            if (planId != null && !planId.isBlank()) {
                builder.detail("套餐", planId);
            }
        }

        // 用量窗口（5 小时 / 每周）
        JsonNode windowLimits = credits == null ? null : credits.get("windowLimits");
        if (windowLimits != null && windowLimits.isObject()) {
            addWindow(builder, windowLimits, "fiveHour", "5小时窗口");
            addWindow(builder, windowLimits, "weekly", "每周窗口");
        }

        // 订阅信息
        JsonNode subData = subscriptionData(subscriptions);
        if (subData != null && subData.isObject()) {
            String periodEnd = JsonSupport.text(subData, "currentPeriodEnd");
            String planId = JsonSupport.text(subData, "planId");
            if (planId != null && !planId.isBlank()) {
                builder.detail("订阅套餐", planId);
            }
            if (periodEnd != null && !periodEnd.isBlank()) {
                builder.detail("周期结束", periodEnd);
            }
        }

        // 当前周期用量汇总
        if (summary != null && summary.isObject()) {
            Double totalCost = JsonSupport.number(summary, "totalCost");
            Double totalCount = JsonSupport.number(summary, "totalCount");
            if (totalCost != null) {
                builder.detail("本期已用金额", String.format("$%.2f", totalCost));
            }
            if (totalCount != null) {
                builder.detail("本期调用次数", String.format("%.0f 次", totalCount));
            }
        }

        return builder.build();
    }

    /** 解析单个用量窗口：cap/used/resetAt → 「剩余 X%」+ 重置时间。 */
    private static void addWindow(BalanceSnapshot.Builder builder, JsonNode windowLimits, String key, String label) {
        JsonNode w = windowLimits.get(key);
        if (w == null || !w.isObject()) {
            return;
        }
        Double used = JsonSupport.number(w, "used");
        Double cap = JsonSupport.number(w, "cap");
        if (used == null || cap == null || cap <= 0) {
            return;
        }
        double remainingPercent = Math.max(0, Math.min(100, (cap - used) / cap * 100.0));
        builder.detail(label, String.format("剩余 %.1f%%", remainingPercent));
        String resetAt = JsonSupport.text(w, "resetAt");
        // 部分订阅未开始窗口时 resetAt 可能为 0/空，此时不展示重置时间
        if (resetAt != null && !resetAt.isBlank() && !"0".equals(resetAt.trim())) {
            builder.detail(label + "重置", resetAt);
        }
        builder.metric(ProviderCapability.QUOTA, label + "额度", remainingPercent, "%", remainingPercent,
                "CommandCode 用量窗口");
    }

    private static JsonNode subscriptionData(JsonNode subscriptions) {
        if (subscriptions == null) {
            return null;
        }
        JsonNode data = subscriptions.get("data");
        return data != null && data.isObject() ? data : subscriptions;
    }

    private static Map<String, String> headers(AccountConfig config) {
        return Map.of(
                "Authorization", "Bearer " + config.getApiKey().strip(),
                "Accept", "application/json",
                "User-Agent", "cli",
                "x-cli-environment", "production");
    }

    private static String withOrg(String endpoint, String orgId) {
        return orgId == null || orgId.isBlank() ? endpoint : endpoint + "?orgId=" + encode(orgId);
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
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