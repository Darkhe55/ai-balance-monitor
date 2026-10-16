package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;

import java.util.Map;

/**
 * 魔塔社区 ModelScope：官方未开放余额查询 REST API，
 * 仅通过调用 models 接口校验 Key 有效性。
 * Base URL: https://api-inference.modelscope.cn/v1
 */
public final class ModelScopeProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api-inference.modelscope.cn/v1";

    private final HttpSupport http;

    public ModelScopeProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/models",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status()).build();
            }
            return BalanceSnapshot.ok(ProviderId.MODELSCOPE, config.getId(), config.getName())
                    .label("魔塔社区")
                    .message("Key 有效（官方未开放余额查询接口）")
                    .build();
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
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
}
