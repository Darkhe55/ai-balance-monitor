package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * fal.ai Provider（海外模型平台：图像/视频/音频/多模态推理）。
 * <p>
 * 官方余额接口（已核实官方文档）：GET {base}/v1/account/billing?expand=credits，
 * 认证方式 {@code Authorization: Key <API_KEY>}，需使用带 {@code READONLY} / {@code BILLING}
 * 预置权限（或含 {@code billing:usage:read} 权限）的 API Key。
 * <p>
 * 响应结构（官方 OpenAPI 示例）：
 * <pre>{@code
 * {
 *   "username": "my-team",
 *   "credits": {
 *     "current_balance": 24.5,
 *     "currency": "USD"
 *   }
 * }
 * }</pre>
 */
public final class FalProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.fal.ai";

    private final HttpSupport http;
    private final ProviderId pid;

    public FalProvider(HttpSupport http) {
        this(http, ProviderId.FAL);
    }

    public FalProvider(HttpSupport http, ProviderId pid) {
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
            HttpSupport.Response resp = http.get(base + "/v1/account/billing?expand=credits",
                    Map.of("Authorization", "Key " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无账单只读权限（需 READONLY/BILLING 权限 Key）").build();
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

    /** 解析账户账单响应（包内可见，供单元测试）。 */
    static BalanceSnapshot.Builder fromJson(ProviderId pid, String accountId, String accountName,
                                            JsonNode root) {
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        JsonNode credits = root == null ? null : root.get("credits");
        String currency = null;
        if (credits != null && !credits.isNull()) {
            JsonNode c = credits.get("currency");
            if (c != null && !c.isNull() && !c.asText().isBlank()) {
                currency = c.asText();
            }
        }
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        Double balance = credits == null ? null : JsonSupport.number(credits, "current_balance");
        if (balance != null) {
            builder.label("可用余额").currency(currency).amount(balance);
            builder.metric(ProviderCapability.BALANCE, "可用余额", balance, currency, null,
                    "fal.ai 账户可用余额（官方账户账单接口）");
        } else {
            builder.label("可用余额").currency(currency).amount(null)
                    .message("余额响应缺少 credits.current_balance");
        }
        String username = JsonSupport.text(root, "username");
        if (username != null && !username.isBlank()) {
            builder.detail("账户", username);
        }
        builder.detail("币种", currency);
        return builder;
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