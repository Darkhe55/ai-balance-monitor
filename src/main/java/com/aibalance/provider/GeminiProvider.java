package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Google Gemini：余额需 Google Cloud 账单体系（OAuth + 项目），API Key 无法直接查询。
 * 通过 GET /v1beta/models?key=… 校验 Key 有效性并统计可用模型数。
 */
public final class GeminiProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final HttpSupport http;

    public GeminiProvider(HttpSupport http) {
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
            return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/models?key=" + config.getApiKey().strip(),
                    Map.of("Accept", "application/json"));
            if (resp.status() == 400 && (resp.body() != null && resp.body().contains("API key"))) {
                return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效").build();
            }
            if (resp.status() == 403 || resp.status() == 401) {
                return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            int modelCount = 0;
            if (root != null) {
                JsonNode models = root.get("models");
                if (models != null && models.isArray()) {
                    modelCount = models.size();
                }
            }
            return BalanceSnapshot.ok(ProviderId.GEMINI, config.getId(), config.getName())
                    .label("Key 有效")
                    .detail("可用模型", String.format("%,d 个", modelCount))
                    .detail("余额查询", "需 Google Cloud 账单（API Key 无法查询）")
                    .build();
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.GEMINI, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }
}
