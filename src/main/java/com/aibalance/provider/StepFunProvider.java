package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 阶跃星辰（StepFun）：官方余额接口。
 * GET {base}/v1/accounts
 * 响应：{"object":"account","type":"prepaid|postpaid","balance":0.00,
 *        "total_cash_balance":0.00,"total_voucher_balance":26.00}
 * balance=当前可用余额，total_cash_balance=累计充值，total_voucher_balance=剩余赠送。
 */
public final class StepFunProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.stepfun.com";

    private final HttpSupport http;

    public StepFunProvider(HttpSupport http) {
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
            return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        try {
            HttpSupport.Response resp = http.get(base + "/v1/accounts",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应为空").build();
            }
            return fromJson(ProviderId.STEPFUN, config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.STEPFUN, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析官方 GET /v1/accounts 响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String id, String name, JsonNode root) {
        Double balance = JsonSupport.number(root, "balance");
        Double cash = JsonSupport.number(root, "total_cash_balance");
        Double voucher = JsonSupport.number(root, "total_voucher_balance");
        String type = root.path("type").asText(null);

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, id, name);
        b.label("账户余额").currency("CNY").amount(balance);
        if (cash != null) {
            b.detail("累计充值", String.format("%.2f CNY", cash));
        }
        if (voucher != null) {
            b.detail("剩余赠送", String.format("%.2f CNY", voucher));
        }
        b.detail("账户类型", "prepaid".equals(type) ? "预付费" : "postpaid".equals(type) ? "后付费" : (type == null ? "未知" : type));
        return b.build();
    }
}
