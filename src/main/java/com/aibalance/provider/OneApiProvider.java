package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * OneAPI / NewAPI 兼容网关：尝试多个标准端点
 * - /api/user/self (标准 OneAPI)
 * - /v1/dashboard/billing/usage (NewAPI)
 * - /v1/dashboard/billing/credit_grants (OpenAI 兼容)
 */
public class OneApiProvider implements BalanceProvider {

    /** OneAPI 标准换算：quota = 金额(USD) * 500000 */
    private static final double QUOTA_PER_USD = 500_000.0;

    private final HttpSupport http;
    private final ProviderId pid;

    public OneApiProvider(HttpSupport http) {
        this(http, ProviderId.ONEAPI);
    }

    public OneApiProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需要配置网关 Base URL").build();
        }
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + config.getApiKey().strip(),
                "Accept", "application/json");

        // 按顺序尝试多个端点
        String[] endpoints = {
                "/api/user/self",
                "/v1/dashboard/billing/usage",
                "/v1/dashboard/billing/credit_grants",
                "/api/user/info"
        };

        BalanceSnapshot.Builder lastError = null;

        for (String endpoint : endpoints) {
            try {
                HttpSupport.Response resp = http.get(base + endpoint, headers);

                if (resp.status() == 401 || resp.status() == 403) {
                    return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.UNAUTHORIZED)
                            .message("Token 无效或无权限").build();
                }
                if (resp.status() == 404) {
                    continue; // 这个端点不存在，试下一个
                }
                if (resp.status() != 200) {
                    lastError = BalanceSnapshot.of(pid, config.getId(), config.getName(),
                                    BalanceSnapshot.Status.ERROR)
                            .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body()));
                    continue;
                }

                JsonNode root = resp.bodyAsJson();
                if (root == null) {
                    continue;
                }

                // 尝试解析这个端点的响应
                BalanceSnapshot snapshot = tryParseResponse(pid, config, root, endpoint);
                if (snapshot != null) {
                    return snapshot;
                }
            } catch (HttpSupport.HttpFailure e) {
                lastError = BalanceSnapshot.of(pid, config.getId(), config.getName(),
                                e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                        .message(e.getMessage());
            } catch (Exception ignored) {
            }
        }

        if (lastError != null) {
            return lastError.build();
        }
        return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                BalanceSnapshot.Status.ERROR).message("所有端点均未返回有效余额数据").build();
    }

    /** 尝试解析不同端点的响应格式 */
    private BalanceSnapshot tryParseResponse(ProviderId pid, AccountConfig config, JsonNode root, String endpoint) {
        // 格式1: OneAPI 标准 /api/user/self
        JsonNode data = root.get("data");
        if (data != null && data.isObject()) {
            Double quota = JsonSupport.number(data, "quota");
            Double usedQuota = JsonSupport.number(data, "usedQuota");
            if (quota != null) {
                BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName())
                        .label("剩余配额").currency("USD")
                        .amount(quota / QUOTA_PER_USD);
                if (usedQuota != null && quota + usedQuota > 0) {
                    b.percent(100.0 * quota / (quota + usedQuota));
                }
                b.detail("已用配额", String.format("$%.2f", usedQuota == null ? 0 : usedQuota / QUOTA_PER_USD));
                return b.build();
            }
        }

        // 格式2: NewAPI /v1/dashboard/billing/usage
        Double totalUsage = JsonSupport.number(root, "total_usage");
        Double totalGrant = JsonSupport.number(root, "total_grant");
        if (totalUsage != null || totalGrant != null) {
            BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName())
                    .label("剩余额度").currency("USD");
            if (totalGrant != null && totalUsage != null) {
                double remaining = (totalGrant - totalUsage) / 100.0;
                b.amount(remaining);
                if (totalGrant > 0) {
                    b.percent(100.0 * (totalGrant - totalUsage) / totalGrant);
                }
                b.detail("已用额度", String.format("$%.2f", totalUsage / 100.0));
            }
            return b.build();
        }

        // 格式3: OpenAI 兼容 credit_grants
        JsonNode grants = root.get("grants");
        if (grants != null && grants.isArray() && grants.size() > 0) {
            BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName())
                    .label("剩余额度").currency("USD");
            double totalRemaining = 0;
            for (JsonNode grant : grants) {
                Double remaining = JsonSupport.number(grant, "remaining_amount");
                if (remaining != null) totalRemaining += remaining;
            }
            b.amount(totalRemaining);
            return b.build();
        }

        return null;
    }

    /** 解析 OneAPI 用户信息响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应缺少 data 字段（非 OneAPI 网关？）").build();
        }
        Double quota = JsonSupport.number(data, "quota");
        Double usedQuota = JsonSupport.number(data, "usedQuota");
        Double requestCount = JsonSupport.number(data, "requestCount");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("剩余配额").currency("USD")
                .amount(quota == null ? null : quota / QUOTA_PER_USD);
        if (quota != null) {
            if (usedQuota != null && quota + usedQuota > 0) {
                b.percent(100.0 * quota / (quota + usedQuota));
            }
            b.detail("配额原始值", String.format("%,.0f", quota));
        } else {
            b.message("未返回 quota 字段");
        }
        if (usedQuota != null) {
            b.detail("已用配额", String.format("$ %.2f", usedQuota / QUOTA_PER_USD));
        }
        if (requestCount != null) {
            b.detail("累计请求", String.format("%,.0f 次", requestCount));
        }
        b.detail("换算说明", "按 1 USD = 500000 配额估算");
        return b.build();
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.strip();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
