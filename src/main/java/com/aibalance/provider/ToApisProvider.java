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
 * 官方账户接口（两个端点）：
 * <ul>
 *   <li>GET {base}/v1/balance —— <b>令牌余额</b>（当前 API Key 自身的额度）</li>
 *   <li>GET {base}/v1/user/balance —— <b>用户余额</b>（该 Key 所属账户的整体余额）</li>
 * </ul>
 * 两者返回字段一致：
 * <pre>{@code
 * {
 *   "success": true,
 *   "remain_balance": 10.50,     // 剩余余额（USD）
 *   "used_balance": 2.50,        // 已用余额
 *   "remain_credits": 2100,      // 剩余积分（固定 1 USD = 200 积分换算）
 *   "used_credits": 500,         // 已用积分
 *   "credits_per_usd": 200,      // 每美元积分
 *   "unlimited_quota": false     // 无限额度令牌：remain_balance 返回 -1
 * }
 * }</pre>
 * 注意：业务失败时 HTTP 仍为 200，用 {@code success:false} + {@code message} 表达。
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
        String auth = "Bearer " + config.getApiKey().strip();

        try {
            // 第一步：令牌余额（主数据）
            HttpSupport.Response resp = http.get(base + "/v1/balance",
                    Map.of("Authorization", auth, "Accept", "application/json"));
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
            BalanceSnapshot.Builder builder = parseBuilder(pid, config.getId(), config.getName(), root);

            // 第二步：用户（账户）整体余额——失败不阻断令牌余额展示
            JsonNode user = fetchUserBalance(base, auth);
            if (user != null) {
                appendUserBalance(builder, user);
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

    /** 查询账户整体余额（/v1/user/balance）；任何异常都返回 null，不影响主流程。 */
    private JsonNode fetchUserBalance(String base, String auth) {
        try {
            HttpSupport.Response resp = http.get(base + "/v1/user/balance",
                    Map.of("Authorization", auth, "Accept", "application/json"));
            if (resp.status() != 200) {
                return null;
            }
            JsonNode node = resp.bodyAsJson();
            if (node == null || !JsonSupport.bool(node, "success", false)) {
                return null;
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析余额响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseBalance(ProviderId pid, String accountId, String accountName, JsonNode root) {
        return parseBuilder(pid, accountId, accountName, root).build();
    }

    /** 解析余额响应为 Builder（供主流程追加账户级数据）。 */
    static BalanceSnapshot.Builder parseBuilder(ProviderId pid, String accountId, String accountName,
                                                JsonNode root) {
        // 业务失败：HTTP 200 但 success=false，用 message 说明原因
        if (root != null && !JsonSupport.bool(root, "success", true)) {
            String msg = JsonSupport.text(root, "message");
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message(msg == null || msg.isBlank() ? "接口返回失败" : msg);
        }

        boolean unlimited = JsonSupport.bool(root, "unlimited_quota", false);
        Double remainBalance = JsonSupport.number(root, "remain_balance");
        Double usedBalance = JsonSupport.number(root, "used_balance");
        Double remainCredits = JsonSupport.number(root, "remain_credits");
        Double usedCredits = JsonSupport.number(root, "used_credits");
        Double creditsPerUsd = JsonSupport.number(root, "credits_per_usd");

        // 不限量或数值为负（-1 表示无限）时，不展示具体余额，避免误导
        boolean hasBalance = remainBalance != null && remainBalance >= 0;
        boolean hasCredits = remainCredits != null && remainCredits > 0;

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);

        if (unlimited) {
            builder.label("剩余额度").currency("").amount(null).message("不限量套餐");
        } else if (hasBalance) {
            builder.label("剩余余额").currency("USD").amount(remainBalance);
        } else {
            builder.label("剩余额度").currency("").amount(null);
        }

        if (!unlimited && hasBalance) {
            builder.metric(ProviderCapability.QUOTA, "剩余余额", remainBalance, "USD", null,
                    "ToAPIs 令牌余额");
        }
        if (usedBalance != null) {
            builder.detail("已用余额", String.format("$%.2f", usedBalance));
        }
        if (!unlimited && hasCredits) {
            builder.detail("剩余 Credits", String.format("%.0f", remainCredits));
        }
        if (usedCredits != null) {
            builder.detail("已用 Credits", String.format("%.0f", usedCredits));
        }
        if (creditsPerUsd != null && creditsPerUsd > 0) {
            builder.detail("换算比例", String.format("1 USD = %.0f Credits", creditsPerUsd));
        }

        return builder;
    }

    /** 追加账户（用户）整体余额：作为独立指标展示，与令牌余额区分。 */
    static void appendUserBalance(BalanceSnapshot.Builder builder, JsonNode user) {
        Double remainBalance = JsonSupport.number(user, "remain_balance");
        Double usedBalance = JsonSupport.number(user, "used_balance");
        Double remainCredits = JsonSupport.number(user, "remain_credits");
        boolean unlimited = JsonSupport.bool(user, "unlimited_quota", false);

        if (unlimited) {
            builder.detail("账户余额", "不限量");
            return;
        }
        if (remainBalance != null && remainBalance >= 0) {
            builder.metric(ProviderCapability.QUOTA, "账户余额", remainBalance, "USD", null,
                    "ToAPIs 账户整体余额");
        }
        if (usedBalance != null) {
            builder.detail("账户已用", String.format("$%.2f", usedBalance));
        }
        if (remainCredits != null && remainCredits > 0) {
            builder.detail("账户剩余 Credits", String.format("%.0f", remainCredits));
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