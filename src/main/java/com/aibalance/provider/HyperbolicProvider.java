package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Hyperbolic Provider（海外 AI 云：GPU 云 + 开源模型推理，OpenAI 兼容）。
 * <p>
 * 官方余额接口（已核实官方 OpenAPI spec）：GET {base}/v2/customer/balance，
 * 认证方式 {@code Authorization: Bearer <API_KEY>}（普通 API Key 即可）。
 * <p>
 * 响应结构（官方 AccountBalance schema，字段均为数字）：
 * <pre>{@code
 * {
 *   "balanceCents": 12345,
 *   "maxOverdraftCents": 5000
 * }
 * }</pre>
 * 金额单位均为<b>美分</b>，÷100 即 USD。
 */
public final class HyperbolicProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.hyperbolic.xyz";
    /** 官方余额字段的换算因子：1 USD = 100 cents。 */
    static final double CENTS_DIVISOR = 100.0;

    private final HttpSupport http;
    private final ProviderId pid;

    public HyperbolicProvider(HttpSupport http) {
        this(http, ProviderId.HYPERBOLIC);
    }

    public HyperbolicProvider(HttpSupport http, ProviderId pid) {
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
        try {
            HttpSupport.Response resp = http.get(base + "/v2/customer/balance",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无余额查询权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询余额失败: HTTP " + resp.status()).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("余额响应格式异常").build();
            }
            return fromJson(pid, config.getId(), config.getName(), root).build();
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

    /** 解析余额响应（包内可见，供单元测试）。金额单位为美分，÷100 = USD。 */
    static BalanceSnapshot.Builder fromJson(ProviderId pid, String accountId, String accountName,
                                            JsonNode root) {
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        Double balanceCents = JsonSupport.number(root, "balanceCents");
        Double maxOverdraftCents = JsonSupport.number(root, "maxOverdraftCents");

        Double balance = centsToUsd(balanceCents);
        if (balance != null) {
            builder.label("可用余额").currency("USD").amount(balance);
            builder.metric(ProviderCapability.BALANCE, "可用余额", balance, "USD", null,
                    "Hyperbolic 账户信用余额（官方余额接口，单位美分）");
        } else {
            builder.label("可用余额").currency("USD").amount(null)
                    .message("余额响应缺少 balanceCents");
        }
        Double maxOverdraft = centsToUsd(maxOverdraftCents);
        if (maxOverdraft != null) {
            builder.detail("最大透支额度", String.format("%.2f USD", maxOverdraft));
        }
        return builder;
    }

    /** 美分转美元；null 返回 null。 */
    static Double centsToUsd(Double cents) {
        return cents == null ? null : cents / CENTS_DIVISOR;
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