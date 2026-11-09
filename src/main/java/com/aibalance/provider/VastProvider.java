package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Vast.ai Provider（海外 GPU 云市场，预付费 credits）。
 * <p>
 * 官方余额接口（已核实官方文档）：GET {base}/api/v0/users/current/，
 * 认证方式 {@code Authorization: Bearer <API_KEY>}（建议使用仅含 {@code user_read}
 * 权限的 scoped API Key）。
 * <p>
 * 响应结构（官方 Authentication 文档示例，余额字段为 {@code credit}）：
 * <pre>{@code
 * {
 *   "id": 123456,
 *   "email": "you@example.com",
 *   "credit": 25.00,
 *   "ssh_key": "ssh-rsa AAAAB3..."
 * }
 * }</pre>
 * 部分文档亦将余额字段写作 {@code balance}，解析时 {@code credit} 优先、{@code balance} 兜底。
 */
public final class VastProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://console.vast.ai";

    private final HttpSupport http;
    private final ProviderId pid;

    public VastProvider(HttpSupport http) {
        this(http, ProviderId.VAST);
    }

    public VastProvider(HttpSupport http, ProviderId pid) {
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
            HttpSupport.Response resp = http.get(base + "/api/v0/users/current/",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无 user_read 权限").build();
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

    /** 解析用户账户响应（包内可见，供单元测试）。 */
    static BalanceSnapshot.Builder fromJson(ProviderId pid, String accountId, String accountName,
                                            JsonNode root) {
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        Double credit = JsonSupport.number(root, "credit");
        if (credit == null) {
            credit = JsonSupport.number(root, "balance");
        }
        if (credit != null) {
            builder.label("可用余额").currency("USD").amount(credit);
            builder.metric(ProviderCapability.BALANCE, "可用余额", credit, "USD", null,
                    "Vast.ai 账户信用余额（官方 Show User 接口）");
        } else {
            builder.label("可用余额").currency("USD").amount(null)
                    .message("余额响应缺少 credit/balance 字段");
        }
        String email = JsonSupport.text(root, "email");
        if (email != null && !email.isBlank()) {
            builder.detail("邮箱", email);
        }
        Long id = JsonSupport.longNumber(root, "id");
        if (id != null) {
            builder.detail("用户 ID", String.valueOf(id));
        }
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