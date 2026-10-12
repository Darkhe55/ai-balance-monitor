package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * DeepSeek：官方余额接口 GET https://api.deepseek.com/user/balance
 * 响应示例：
 * {"is_available": true, "balance_infos": [
 *   {"currency": "CNY", "total_balance": "110.00",
 *    "granted_balance": "0.00", "topped_up_balance": "110.00"}]}
 */
public final class DeepSeekProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.deepseek.com";

    private final HttpSupport http;

    public DeepSeekProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/user/balance",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null || !root.isObject()) {
                return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return fromJson(ProviderId.DEEPSEEK, config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.DEEPSEEK, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析余额响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        boolean available = JsonSupport.bool(root, "is_available", false);
        JsonNode infos = root.get("balance_infos");
        Double total = null;
        Double granted = null;
        Double toppedUp = null;
        String currency = "CNY";
        if (infos != null && infos.isArray() && !infos.isEmpty()) {
            JsonNode first = infos.get(0);
            total = JsonSupport.number(first, "total_balance");
            granted = JsonSupport.number(first, "granted_balance");
            toppedUp = JsonSupport.number(first, "topped_up_balance");
            String c = JsonSupport.text(first, "currency");
            if (c != null) {
                currency = c;
            }
        }

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("账户余额")
                .currency(currency)
                .amount(total);
        if (total == null) {
            b.message("未返回余额信息");
        }
        if (granted != null) {
            b.detail("赠送余额", fmt(granted, currency));
        }
        if (toppedUp != null) {
            b.detail("充值余额", fmt(toppedUp, currency));
        }
        b.detail("可用状态", available ? "可用" : "不可用（余额不足）");
        return b.build();
    }

    private static String fmt(double v, String currency) {
        return String.format("%.2f %s", v, currency);
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

    static String firstError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            JsonNode err = root.get("error");
            if (err != null && err.isObject()) {
                JsonNode msg = err.get("message");
                if (msg != null) {
                    return msg.asText();
                }
            }
            JsonNode msg = root.get("message");
            if (msg != null) {
                return msg.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，截取片段
        }
        String s = body.strip();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
