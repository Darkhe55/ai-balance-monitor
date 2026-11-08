package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Novita AI Provider（海外：LLM + 图像/视频/音频 + GPU 云）。
 * <p>
 * 官方余额接口（已核实官方文档）：GET {base}/openapi/v1/billing/balance/detail，
 * 认证方式 {@code Authorization: Bearer <API_KEY>}（普通 API Key 即可）。
 * <p>
 * 响应字段均为<b>字符串</b>，单位 1/10000 USD（如 {@code "10000"} = $1.00）：
 * <ul>
 *   <li>{@code availableBalance} 可用余额</li>
 *   <li>{@code cashBalance} 充值余额</li>
 *   <li>{@code creditLimit} 信用额度</li>
 *   <li>{@code pendingCharges} 待扣款项（默认 0）</li>
 *   <li>{@code outstandingInvoices} 未付账单</li>
 * </ul>
 */
public final class NovitaProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.novita.ai";
    /** 官方余额字段的换算因子：1/10000 USD。 */
    static final double USD_DIVISOR = 10_000.0;

    private final HttpSupport http;
    private final ProviderId pid;

    public NovitaProvider(HttpSupport http) {
        this(http, ProviderId.NOVITA);
    }

    public NovitaProvider(HttpSupport http, ProviderId pid) {
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
            HttpSupport.Response resp = http.get(base + "/openapi/v1/billing/balance/detail",
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

    /** 解析余额响应（包内可见，供单元测试）。字段为字符串，单位 1/10000 USD。 */
    static BalanceSnapshot.Builder fromJson(ProviderId pid, String accountId, String accountName,
                                            JsonNode root) {
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        Double available = usdValue(root == null ? null : root.get("availableBalance"));
        Double cash = usdValue(root == null ? null : root.get("cashBalance"));
        Double creditLimit = usdValue(root == null ? null : root.get("creditLimit"));
        Double pending = usdValue(root == null ? null : root.get("pendingCharges"));
        Double outstanding = usdValue(root == null ? null : root.get("outstandingInvoices"));

        if (available != null) {
            builder.label("可用余额").currency("USD").amount(available);
            builder.metric(ProviderCapability.BALANCE, "可用余额", available, "USD", null,
                    "Novita 账户可用余额（官方余额接口，1/10000 USD）");
        } else {
            builder.label("可用余额").currency("USD").amount(null)
                    .message("余额响应缺少 availableBalance");
        }
        if (cash != null) {
            builder.detail("充值余额", String.format("%.4f USD", cash));
        }
        if (creditLimit != null) {
            builder.detail("信用额度", String.format("%.4f USD", creditLimit));
        }
        if (pending != null) {
            builder.detail("待扣款项", String.format("%.4f USD", pending));
        }
        if (outstanding != null) {
            builder.detail("未付账单", String.format("%.4f USD", outstanding));
        }
        return builder;
    }

    /** 把 1/10000 USD 的字符串字段转成美元金额；null/空/非数字返回 null。 */
    static Double usdValue(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        try {
            return Long.parseLong(node.asText().trim()) / USD_DIVISOR;
        } catch (NumberFormatException e) {
            return null;
        }
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