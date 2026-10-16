package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * CommandCode 专用 Provider。
 * 官方 billing 接口：
 *   GET {base}/alpha/billing/credits        → 信用额度
 *   GET {base}/alpha/billing/subscriptions → 订阅信息
 */
public class CommandCodeProvider implements BalanceProvider {

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
            HttpSupport.Response creditsResp = http.get(base + "/alpha/billing/credits",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
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

            JsonNode subscriptions = null;
            try {
                HttpSupport.Response subResp = http.get(base + "/alpha/billing/subscriptions",
                        Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                                "Accept", "application/json"));
                if (subResp.status() == 200) {
                    subscriptions = subResp.bodyAsJson();
                }
            } catch (Exception ignored) {
            }

            return buildSnapshot(config, credits, subscriptions);
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

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode credits, JsonNode subscriptions) {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName());

        String rawJson = credits == null ? "null" : credits.toString();
        if (rawJson.length() > 300) rawJson = rawJson.substring(0, 300) + "...";
        b.detail("原始响应", rawJson);

        if (credits != null) {
            Double monthlyCredits = JsonSupport.number(credits, "monthly_credits");
            Double monthlyUsed = JsonSupport.number(credits, "monthly_used");

            if (monthlyCredits == null && credits.has("data")) {
                JsonNode data = credits.get("data");
                monthlyCredits = JsonSupport.number(data, "monthly_credits");
                monthlyUsed = JsonSupport.number(data, "monthly_used");
            }

            if (monthlyCredits != null) {
                b.label("剩余 Credits").currency("Credits")
                        .amount(monthlyCredits);
                if (monthlyUsed != null && monthlyCredits + monthlyUsed > 0) {
                    b.percent(100.0 * monthlyCredits / (monthlyCredits + monthlyUsed));
                }
                b.detail("月度已用", String.format("%,.0f", monthlyUsed == null ? 0 : monthlyUsed));
            }

            Double window5hLimit = JsonSupport.number(credits, "five_hour_limit");
            Double window5hUsed = JsonSupport.number(credits, "five_hour_used");
            if (window5hLimit != null) {
                b.detail("5小时窗口", String.format("%,.0f / %,.0f",
                        window5hUsed == null ? 0 : window5hUsed, window5hLimit));
            }

            Double weeklyLimit = JsonSupport.number(credits, "weekly_limit");
            Double weeklyUsed = JsonSupport.number(credits, "weekly_used");
            if (weeklyLimit != null) {
                b.detail("每周窗口", String.format("%,.0f / %,.0f",
                        weeklyUsed == null ? 0 : weeklyUsed, weeklyLimit));
            }
        }

        if (subscriptions != null) {
            JsonNode data = subscriptions.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode sub = data.get(0);
                String planId = text(sub, "plan_id");
                String status = text(sub, "status");
                String currentPeriodEnd = text(sub, "current_period_end");
                if (planId != null) b.detail("套餐", planId);
                if (status != null) b.detail("订阅状态", status);
                if (currentPeriodEnd != null) b.detail("计费周期结束", currentPeriodEnd);
            }
        }

        return b.build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.strip();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
