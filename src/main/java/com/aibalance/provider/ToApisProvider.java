package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * ToAPIs 专用 Provider。
 * 官方账户接口：GET {base}/v1/balance
 * 返回当前 API 令牌的美元余额与 Credits 余量：
 * <pre>{@code
 * {
 *   "success": true,
 *   "remain_balance": 10.50,
 *   "used_balance": 2.50,
 *   "remain_credits": 10500,
 *   "used_credits": 2500,
 *   "credits_per_usd": 1000,
 *   "unlimited_quota": false
 * }
 * }</pre>
 */
public final class ToApisProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public ToApisProvider(HttpSupport http, ProviderId pid) {
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

        try {
            HttpSupport.Response resp = http.get(base + "/v1/balance",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询余额失败: HTTP " + resp.status()).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return buildSnapshot(config, root);
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

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode root) {
        return parseBalance(pid, config.getId(), config.getName(), root);
    }

    /** 解析余额响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseBalance(ProviderId pid, String accountId, String accountName, JsonNode root) {
        boolean unlimited = JsonSupport.bool(root, "unlimited_quota", false);
        Double remainBalance = JsonSupport.number(root, "remain_balance");
        Double usedBalance = JsonSupport.number(root, "used_balance");
        Double remainCredits = JsonSupport.number(root, "remain_credits");
        Double creditsPerUsd = JsonSupport.number(root, "credits_per_usd");

        // 不限量或数值为负（-1 表示无限）时，不展示具体余额，避免误导
        boolean hasBalance = remainBalance != null && remainBalance >= 0;
        boolean hasCredits = remainCredits != null && remainCredits > 0;

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);

        if (unlimited) {
            b.label("剩余额度").currency("").amount(null).message("不限量套餐");
        } else if (hasBalance) {
            b.label("剩余余额").currency("USD").amount(remainBalance);
        } else {
            b.label("剩余额度").currency("").amount(null);
        }

        if (!unlimited && hasBalance) {
            b.metric(ProviderCapability.QUOTA, "剩余余额", remainBalance, "USD", null,
                    "ToAPIs 令牌余额");
        }
        if (usedBalance != null) {
            b.detail("已用余额", String.format("$%.2f", usedBalance));
        }
        if (!unlimited && hasCredits) {
            b.detail("剩余 Credits", String.format("%.0f", remainCredits));
        }
        if (creditsPerUsd != null && creditsPerUsd > 0) {
            b.detail("换算比例", String.format("1 USD = %.0f Credits", creditsPerUsd));
        }

        return b.build();
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