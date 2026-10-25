package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * OpenRouter（及兼容网关）：
 *  1) GET {base}/api/v1/key      官方 Key 配额：limit / usage / is_limited（"token plan" 语义）
 *  2) GET {base}/api/v1/credits  账户 Credits 余额：total_credits / total_usage
 * 认证：Authorization: Bearer &lt;apiKey&gt;；Base URL 留空时用官方 https://openrouter.ai/api。
 * 参考 CodexBar / pi-usage 等项目的实现方式：Key 配额与账户余额分开读取、任一失败不拖垮整卡。
 */
public final class OpenRouterProvider implements BalanceProvider {

    /** 官方 API 根地址 */
    private static final String DEFAULT_BASE = "https://openrouter.ai/api";

    private final HttpSupport http;

    public OpenRouterProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("网关 Base URL 无效").build();
        }
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + config.getApiKey().strip(),
                "Accept", "application/json");
        try {
            HttpSupport.Response keyResp = http.get(base + "/api/v1/key", headers);
            if (keyResp.status() == 401 || keyResp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (keyResp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + keyResp.status() + " " + DeepSeekProvider.firstError(keyResp.body())).build();
            }
            JsonNode keyRoot = keyResp.bodyAsJson();
            if (keyRoot == null) {
                return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            BalanceSnapshot.Builder builder = fromKeyJson(config.getId(), config.getName(), keyRoot);

            // 尽力读取账户 Credits 余额；失败仅补充说明，不影响 Key 配额主状态
            try {
                HttpSupport.Response credResp = http.get(base + "/api/v1/credits", headers);
                if (credResp.status() == 200) {
                    JsonNode credRoot = credResp.bodyAsJson();
                    if (credRoot != null) {
                        creditsInto(builder, credRoot);
                    }
                } else {
                    builder.detail("账户余额", "接口返回 HTTP " + credResp.status());
                }
            } catch (Exception ignored) {
                builder.detail("账户余额", "查询失败");
            }
            return builder.build();
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.OPENROUTER, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析 /api/v1/key 响应（可独立测试）。 */
    static BalanceSnapshot.Builder fromKeyJson(String accountId, String accountName, JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            return BalanceSnapshot.of(ProviderId.OPENROUTER, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应缺少 data 字段（非 OpenRouter 网关？）");
        }
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(ProviderId.OPENROUTER, accountId, accountName)
                .label("Key 额度上限").currency("USD");
        Double limit = JsonSupport.number(data, "limit");
        Double usage = JsonSupport.number(data, "usage");
        boolean limited = JsonSupport.bool(data, "is_limited", false);
        String label = JsonSupport.text(data, "label");

        if (limit != null && usage != null) {
            builder.amount(limit - usage);
            builder.percent(limit > 0 ? 100.0 * (limit - usage) / limit : null);
            builder.message(limited ? "已达限额（is_limited）" : "限额内");
        } else if (limit != null) {
            builder.amount(limit).message("未返回用量字段");
        } else {
            builder.label("Key 状态").message("未返回 limit/usage");
        }
        if (label != null && !label.isBlank()) {
            builder.detail("Key 名称", label);
        }
        if (limit != null) {
            builder.detail("额度上限", String.format("$ %.2f", limit));
        }
        if (usage != null) {
            builder.detail("已用额度", String.format("$ %.2f", usage));
        }
        JsonNode rl = data.get("rate_limit");
        if (rl != null && rl.isObject()) {
            Double requests = JsonSupport.number(rl, "requests");
            String interval = JsonSupport.text(rl, "interval");
            if (requests != null) {
                builder.detail("速率限制", interval == null
                        ? String.format("%,.0f 次", requests)
                        : String.format("%,.0f 次 / %s", requests, interval));
            }
        }
        return builder;
    }

    /** 将 /api/v1/credits 响应合并进快照（尽力而为，可独立测试）。 */
    static void creditsInto(BalanceSnapshot.Builder builder, JsonNode root) {
        JsonNode credits = root == null ? null : root.get("credits");
        if (credits == null || !credits.isObject()) {
            builder.detail("账户余额", "响应缺少 credits 字段");
            return;
        }
        Double total = JsonSupport.number(credits, "total_credits");
        Double used = JsonSupport.number(credits, "total_usage");
        if (total != null && used != null) {
            builder.detail("账户余额", String.format("$ %.2f（可用 $ %.2f）", total, Math.max(0, total - used)));
        } else if (total != null) {
            builder.detail("账户余额", String.format("$ %.2f", total));
        } else {
            builder.detail("账户余额", "未返回 total_credits");
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
