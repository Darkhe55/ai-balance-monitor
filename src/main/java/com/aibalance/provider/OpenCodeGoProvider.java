package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * OpenCode Go 套餐专用 Provider。
 * 官方用量端点：GET {base}/zen/go/v1/usage
 * 返回额度使用百分比和重置时间。
 */
public class OpenCodeGoProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public OpenCodeGoProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需要配置 Base URL").build();
        }
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }

        try {
            HttpSupport.Response resp = http.get(base + "/zen/go/v1/usage",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                String body = resp.body();
                if (body != null && body.length() > 200) body = body.substring(0, 200);
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效: " + (body == null ? "无返回" : body)).build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询失败: HTTP " + resp.status()).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return buildSnapshot(config, root);
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

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode root) {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName());

        // 调试：把原始 JSON 显示出来
        String rawJson = root == null ? "null" : root.toString();
        if (rawJson.length() > 300) rawJson = rawJson.substring(0, 300) + "...";
        b.detail("原始响应", rawJson);

        // 使用百分比（剩余比例）
        Double usagePercent = JsonSupport.number(root, "usage_percent");
        Double limitPercent = JsonSupport.number(root, "limit_percent");
        String resetAt = text(root, "reset_at");

        // 尝试嵌套在 data 里
        if (usagePercent == null && root.has("data")) {
            JsonNode data = root.get("data");
            usagePercent = JsonSupport.number(data, "usage_percent");
            limitPercent = JsonSupport.number(data, "limit_percent");
            resetAt = text(data, "reset_at");
        }

        if (usagePercent != null) {
            // usage_percent 是已用百分比，剩余 = 100 - usage
            double remaining = 100.0 - usagePercent;
            b.label("套餐剩余").currency("%")
                    .amount(remaining)
                    .percent(remaining);
            b.detail("已使用", String.format("%.1f%%", usagePercent));
        }
        if (limitPercent != null) {
            b.detail("额度上限", String.format("%.1f%%", limitPercent));
        }
        if (resetAt != null) {
            b.detail("下次重置", resetAt);
        }

        return b.build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.strip();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
