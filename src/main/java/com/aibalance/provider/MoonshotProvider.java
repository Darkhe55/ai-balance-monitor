package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Kimi / Moonshot：官方余额接口 GET https://api.moonshot.cn/v1/users/me/balance
 * 响应示例：
 * {"code": 0, "data": {"available_balance": "10.00", "voucher_balance": "5.00",
 *  "cash_balance": "5.00", "temp_voucher_balance": "0", "temp_cash_balance": "0",
 *  "currency": "CNY"}}
 */
public final class MoonshotProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.moonshot.cn";

    private final HttpSupport http;

    public MoonshotProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = config.getProviderId();
        if (pid == null) {
            pid = ProviderId.MOONSHOT;
        }
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/v1/users/me/balance",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
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

    /** 解析余额响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应缺少 data 字段").build();
        }
        Double available = JsonSupport.number(data, "available_balance");
        Double cash = JsonSupport.number(data, "cash_balance");
        Double voucher = JsonSupport.number(data, "voucher_balance");
        Double tempVoucher = JsonSupport.number(data, "temp_voucher_balance");
        Double tempCash = JsonSupport.number(data, "temp_cash_balance");
        String currency = JsonSupport.text(data, "currency");
        if (currency == null) {
            currency = "CNY";
        }

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("可用余额")
                .currency(currency)
                .amount(available);
        if (cash != null) {
            builder.detail("现金余额", String.format("%.2f %s", cash, currency));
        }
        if (voucher != null && voucher > 0) {
            builder.detail("代金券", String.format("%.2f %s", voucher, currency));
        }
        if (tempVoucher != null && tempVoucher > 0) {
            builder.detail("临时代金券", String.format("%.2f %s", tempVoucher, currency));
        }
        if (tempCash != null && tempCash > 0) {
            builder.detail("临时现金", String.format("%.2f %s", tempCash, currency));
        }
        if (available == null) {
            builder.message("未返回可用余额");
        }
        return builder.build();
    }
}
