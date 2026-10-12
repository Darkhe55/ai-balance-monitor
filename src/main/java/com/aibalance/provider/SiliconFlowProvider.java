package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 硅基流动 SiliconFlow：官方曾提供账户信息接口 GET https://api.siliconflow.cn/v1/user/info
 * 该接口已于 2026-08-14 停止服务（410 Gone，官方公告暂无替代 API），保留实现仅为
 * 存量账户展示准确的错误原因；新增入口已在选型弹窗置灰。
 */
public final class SiliconFlowProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api.siliconflow.cn";

    private final HttpSupport http;

    public SiliconFlowProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        try {
            HttpSupport.Response resp = http.get(normalize(config.getBaseUrl()) + "/v1/user/info",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
            if (resp.status() == 410) {
                // 官方公告：/user/info 余额接口已于 2026-08-14 正式停止服务，暂无替代 API
                return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("官方余额接口已下线（2026-08-14），暂无法查询余额").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return fromJson(ProviderId.SILICONFLOW, config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.SILICONFLOW, config.getId(), config.getName(),
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

    /** 解析账户信息响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        Double balance = JsonSupport.number(root, "balance");
        Double totalBalance = JsonSupport.number(root, "total_balance");
        String status = JsonSupport.text(root, "status");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("账户余额")
                .currency("CNY")
                .amount(balance);
        if (balance == null) {
            b.message("未返回余额字段");
        }
        if (totalBalance != null) {
            b.detail("总额度", String.format("%.2f CNY", totalBalance));
        }
        if (status != null && !status.isBlank()) {
            b.detail("账户状态", status);
        }
        return b.build();
    }
}
