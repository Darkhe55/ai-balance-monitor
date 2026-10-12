package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.BillingMode;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;

import java.util.Map;

/**
 * 小米 MiMo 开放平台（OpenAI 兼容网关），双计费形态（按量付费 + Token Plan 订阅套餐）。
 * 同一厂商两种计费 Key 相互独立：
 *   - 按量付费：普通 API Key（前缀 sk-），默认地址 https://api.xiaomimimo.com/v1；
 *   - Token Plan 套餐：套餐专属 Key，专用地址 https://token-plan-cn.xiaomimimo.com/v1。
 * 两种计费官方均未开放余额/额度查询接口，策略（如实标注，不虚构余额）：
 *  1) 调用 /v1/models 校验 Key 有效性；
 *  2) 尽力读取响应头中的速率配额（x-ratelimit-* 等，网关返回时展示）；
 *  3) 额度数值需到小米 MiMo 控制台查看。
 */
public final class XiaomiProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.xiaomimimo.com/v1";

    private final HttpSupport http;

    public XiaomiProvider(HttpSupport http) {
        this.http = http;
    }

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
            return BalanceSnapshot.of(ProviderId.XIAOMI, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        // Base URL 已含 /v1 时直接拼 /models；否则按 OpenAI 兼容补 /v1/models
        String modelsPath = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        BillingMode mode = config.effectiveBillingMode();
        try {
            HttpSupport.Response resp = http.get(modelsPath,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.XIAOMI, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message(mode == BillingMode.SUBSCRIPTION
                                ? "Token Plan 套餐 Key 无效或无权限（请确认已填套餐专属地址 "
                                        + "https://token-plan-cn.xiaomimimo.com/v1）"
                                : "按量付费 API Key 无效或无权限（按量 Key 前缀 sk-）").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.XIAOMI, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            return fromOk(ProviderId.XIAOMI, config.getId(), config.getName(), resp, mode);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.XIAOMI, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.XIAOMI, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析 200 校验响应（Key 有效 + 尽力读取速率配额 + 按计费形态如实标注），可独立测试。 */
    static BalanceSnapshot fromOk(ProviderId pid, String id, String name,
                                  HttpSupport.Response resp, BillingMode mode) {
        Long tokensRemaining = OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-tokens");
        Long tokensLimit = OpenAIProvider.longHeader(resp, "x-ratelimit-limit-tokens");
        Long requestsRemaining = OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-requests");
        Long requestsLimit = OpenAIProvider.longHeader(resp, "x-ratelimit-limit-requests");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, id, name);
        if (tokensRemaining != null && tokensLimit != null) {
            b.label("令牌配额剩余").currency("tokens").amount(tokensRemaining.doubleValue());
            b.detail("配额上限", String.format("%,d tokens / 分钟", tokensLimit));
        } else {
            b.label("账户状态");
            if (mode == BillingMode.SUBSCRIPTION) {
                b.message("Token Plan 套餐 Key 有效；额度无公开查询接口，请到控制台「订阅管理 / Token Plan」查看");
            } else {
                b.message("按量付费 Key 有效；官方未开放余额接口，余额请到控制台查看");
            }
        }
        if (mode == BillingMode.SUBSCRIPTION) {
            b.detail("计费形态", "Token Plan 订阅套餐（Key 与按量 Key 相互独立）");
        } else {
            b.detail("计费形态", "按量付费（Key 前缀 sk-）");
        }
        if (requestsRemaining != null) {
            b.detail("请求配额剩余", requestsLimit != null
                    ? String.format("%,d / %,d 次/分钟", requestsRemaining, requestsLimit)
                    : String.format("%,d 次/分钟", requestsRemaining));
        }
        return b.build();
    }
}

