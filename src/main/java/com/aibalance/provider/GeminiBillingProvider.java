package com.aibalance.provider;

import com.aibalance.auth.GeminiOAuth;
import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Google Gemini（Cloud 账单）：Google Cloud Billing Budget API。
 * <p>
 * 接入方式（推荐浏览器 OAuth）：
 * <ul>
 *   <li>Base URL 补全为 billingAccounts/{账单ID}，例如
 *       https://billingbudgets.googleapis.com/v1/billingAccounts/0123-4567-8901</li>
 *   <li>在设置表单点击「通过 Google 浏览器授权」，自动填充 Access Token 与 Refresh Token：
 *       访问令牌即时有效，刷新令牌由本 Provider 每次检查前自动兑换新令牌续期。</li>
 *   <li>旧方式仍兼容：只填 Access Token（gcloud auth print-access-token 生成，约 1 小时有效）也能用，
 *       但不会自动续期。</li>
 * </ul>
 * 请求：GET {base}/budgets，官方响应字段（已核实官方文档）：
 * budgets[].amount.specifiedAmount（预算上限，units/nanos/currencyCode）、
 * budgets[].amount.lastPeriodCost（上一期实际花费）、budgets[].displayName。
 * 当前期实际花费不随 budgets 返回，如实标注请在 Google Cloud 控制台查看。
 * 无凭证真机验证不可行，本实现按官方字段解析。
 */
public final class GeminiBillingProvider implements BalanceProvider {

    /** 令牌刷新函数（可注入以便测试）。 */
    @FunctionalInterface
    interface TokenRefresher {
        String refresh(String refreshToken, String clientId, String clientSecret) throws Exception;
    }

    private static final String DEFAULT_BASE = "https://billingbudgets.googleapis.com/v1";

    private final HttpSupport http;
    private final TokenRefresher refresher;

    public GeminiBillingProvider(HttpSupport http) {
        this(http, null);
    }

    GeminiBillingProvider(HttpSupport http, TokenRefresher refresher) {
        this.http = http;
        this.refresher = refresher;
    }

    /** Base URL 归一化：去尾斜杠，空值回退官方默认。 */
    static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? DEFAULT_BASE : s;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.GEMINI_BILLING;
        boolean hasRefresh = config.getSecret() != null && !config.getSecret().isBlank();
        if (!config.hasValidKey() && !hasRefresh) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED)
                    .message("尚未填写 OAuth 访问令牌（可点击「通过 Google 浏览器授权」）").build();
        }
        String base = normalize(config.getBaseUrl());
        String accessToken;
        try {
            accessToken = resolveAccessToken(config.getApiKey(), config.getSecret(),
                    refresherOrDefault(), clientIdFrom(config), clientSecretFrom(config));
        } catch (Exception e) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.UNAUTHORIZED)
                    .message("OAuth 令牌刷新失败，请重新通过浏览器授权: " + e.getMessage()).build();
        }
        if (accessToken == null || accessToken.isBlank()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.UNAUTHORIZED)
                    .message("OAuth 访问令牌为空，请重新授权").build();
        }
        try {
            HttpSupport.Response resp = http.get(base + "/budgets",
                    Map.of("Authorization", "Bearer " + accessToken,
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("gemini-billing url=" + base + "/budgets status=" + resp.status()
                    + " body=" + truncate(resp.body()));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("OAuth 令牌无效或无 Cloud Billing 权限").build();
            }
            if (resp.status() == 404) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP 404：请确认 Base URL 已补全 billingAccounts/{账单ID}").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return fromJson(pid, config.getId(), config.getName(), root);
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

    /**
     * 解析本次请求使用的访问令牌：
     * 账户配置了刷新令牌时，先调用刷新函数换取新访问令牌（自动续期）；
     * 否则直接使用配置中的访问令牌（旧 gcloud 手动令牌方式）。
     */
    static String resolveAccessToken(String apiKey, String refreshToken, TokenRefresher refresher,
                                     String clientId, String clientSecret) throws Exception {
        if (refreshToken != null && !refreshToken.isBlank()) {
            return refresher.refresh(refreshToken, clientId, clientSecret);
        }
        return apiKey == null ? "" : apiKey.strip();
    }

    private TokenRefresher refresherOrDefault() {
        return refresher != null ? refresher
                : (refreshToken, clientId, clientSecret) ->
                        GeminiOAuth.refresh(http, refreshToken, clientId, clientSecret).accessToken();
    }

    /** 从账户附加参数读取自定义 OAuth Client ID（未填则用内置占位值）。 */
    static String clientIdFrom(AccountConfig config) {
        String v = config.parseExtraParams().get("client_id");
        return (v == null || v.isBlank()) ? GeminiOAuth.CLIENT_ID : v;
    }

    /** 从账户附加参数读取自定义 OAuth Client Secret（未填则用内置占位值）。 */
    static String clientSecretFrom(AccountConfig config) {
        String v = config.parseExtraParams().get("client_secret");
        return (v == null || v.isBlank()) ? GeminiOAuth.CLIENT_SECRET : v;
    }

    /** 解析 budgets 列表（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode budgets = root.get("budgets");
        if (budgets == null || !budgets.isArray() || budgets.isEmpty()) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.OK)
                    .label("预算数量").amount(0.0)
                    .message("该账单账号下未配置预算（Budgets）").build();
        }
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        boolean anyCost = false;
        double totalBudget = 0;
        String currency = null;
        for (JsonNode budget : budgets) {
            String displayName = JsonSupport.text(budget, "displayName");
            if (displayName == null || displayName.isBlank()) {
                displayName = "未命名预算";
            }
            JsonNode amount = budget.get("amount");
            Double specified = amount != null ? moneyValue(amount.get("specifiedAmount")) : null;
            Double lastPeriod = amount != null ? moneyValue(amount.get("lastPeriodCost")) : null;
            if (amount != null && currency == null) {
                JsonNode sa = amount.get("specifiedAmount");
                if (sa != null) {
                    currency = JsonSupport.text(sa, "currencyCode");
                }
            }
            if (specified != null) {
                totalBudget += specified;
                anyCost = true;
            }
            StringBuilder line = new StringBuilder();
            if (specified != null) {
                line.append(String.format("预算 %.2f", specified));
            } else {
                line.append("预算 未设置");
            }
            if (lastPeriod != null) {
                line.append(String.format(" · 上期花费 %.2f", lastPeriod));
            }
            builder.detail(displayName, line.toString());
        }
        if (currency == null) {
            currency = "USD";
        }
        if (!anyCost) {
            builder.label("预算总数").currency(currency).amount(0.0)
                    .message("预算均未设置金额，请到 Google Cloud 控制台检查");
            builder.detail("说明", "当前期实际花费不随 Budgets API 返回，请在 Google Cloud 控制台查看");
            return builder.build();
        }
        builder.label("预算总额").currency(currency).amount(totalBudget);
        builder.metric(ProviderCapability.COST, "预算总额", totalBudget, currency, null,
                "Google Cloud Budgets（specifiedAmount）合计");
        builder.detail("说明", "当前期实际花费不随 Budgets API 返回，请在 Google Cloud 控制台查看");
        return builder.build();
    }

    /** money 字段：units(整数) + nanos(小数) 转 Double。 */
    static Double moneyValue(JsonNode money) {
        if (money == null) {
            return null;
        }
        Double units = JsonSupport.number(money, "units");
        Double nanos = JsonSupport.number(money, "nanos");
        if (units == null) {
            return null;
        }
        return units + (nanos == null ? 0.0 : nanos / 1e9);
    }

    /** 诊断日志截断（不记录令牌）。 */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
