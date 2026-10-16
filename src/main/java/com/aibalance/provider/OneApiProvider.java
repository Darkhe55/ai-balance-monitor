package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * OneAPI / NewAPI 兼容网关：GET {base}/api/user/self
 * 响应示例：
 * {"success": true, "message": "", "data": {"quota": 2000000, "usedQuota": 500000,
 *  "requestCount": 120, "username": "..."}}
 * OneAPI 配额单位约定：1 美元 ≈ 500000 配额；仅作估算展示。
 */
public class OneApiProvider implements BalanceProvider {

    /** OneAPI 标准换算：quota = 金额(USD) * 500000 */
    private static final double QUOTA_PER_USD = 500_000.0;

    private final HttpSupport http;
    private final ProviderId pid;

    public OneApiProvider(HttpSupport http) {
        this(http, ProviderId.ONEAPI);
    }

    public OneApiProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需要配置网关 Base URL").build();
        }
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(base + "/api/user/self",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Token 无效或无权限").build();
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

    /** 解析 OneAPI 用户信息响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("响应缺少 data 字段（非 OneAPI 网关？）").build();
        }
        Double quota = JsonSupport.number(data, "quota");
        Double usedQuota = JsonSupport.number(data, "usedQuota");
        Double requestCount = JsonSupport.number(data, "requestCount");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("剩余配额").currency("USD")
                .amount(quota == null ? null : quota / QUOTA_PER_USD);
        if (quota != null) {
            if (usedQuota != null && quota + usedQuota > 0) {
                b.percent(100.0 * quota / (quota + usedQuota));
            }
            b.detail("配额原始值", String.format("%,.0f", quota));
        } else {
            b.message("未返回 quota 字段");
        }
        if (usedQuota != null) {
            b.detail("已用配额", String.format("$ %.2f", usedQuota / QUOTA_PER_USD));
        }
        if (requestCount != null) {
            b.detail("累计请求", String.format("%,.0f 次", requestCount));
        }
        b.detail("换算说明", "按 1 USD = 500000 配额估算");
        return b.build();
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String s = baseUrl.strip();
        if (s.isEmpty()) {
            return null;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
