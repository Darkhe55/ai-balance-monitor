package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;

import java.util.Map;

/**
 * Anthropic Claude：无公开余额接口。
 * 通过 GET /v1/models 校验 Key，并读取响应头中的速率配额
 * （anthropic-ratelimit-tokens-limit / -remaining 等，按配额窗口重置）。
 */
public final class AnthropicProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.anthropic.com";

    private final HttpSupport http;

    public AnthropicProvider(HttpSupport http) {
        this.http = http;
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
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.ANTHROPIC, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/v1/models",
                    Map.of("x-api-key", config.getApiKey().strip(),
                            "anthropic-version", "2023-06-01",
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.ANTHROPIC, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.ANTHROPIC, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            return fromResponse(ProviderId.ANTHROPIC, config.getId(), config.getName(), resp);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.ANTHROPIC, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.ANTHROPIC, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 从响应头解析配额快照（可独立测试）。 */
    static BalanceSnapshot fromResponse(ProviderId pid, String accountId, String accountName, HttpSupport.Response resp) {
        Long tokensRemaining = longHeader(resp, "anthropic-ratelimit-tokens-remaining");
        Long tokensLimit = longHeader(resp, "anthropic-ratelimit-tokens-limit");
        Long requestsRemaining = longHeader(resp, "anthropic-ratelimit-requests-remaining");
        Long requestsLimit = longHeader(resp, "anthropic-ratelimit-requests-limit");

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        if (tokensRemaining != null && tokensLimit != null) {
            builder.label("令牌配额剩余").currency("tokens").amount(tokensRemaining.doubleValue());
            builder.percent(tokensLimit > 0 ? 100.0 * tokensRemaining / tokensLimit : null);
            builder.detail("配额上限", String.format("%,d tokens", tokensLimit));
        } else {
            builder.label("账户状态");
            builder.message("该 Key 未返回配额信息（配额以控制台为准）");
        }
        if (requestsRemaining != null) {
            builder.detail("请求配额剩余", requestsLimit != null
                    ? String.format("%,d / %,d 次", requestsRemaining, requestsLimit)
                    : String.format("%,d 次", requestsRemaining));
        }
        return builder.build();
    }

    static Long longHeader(HttpSupport.Response resp, String name) {
        String v = resp.firstHeader(name);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
