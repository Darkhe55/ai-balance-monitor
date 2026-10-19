package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 魔塔社区 ModelScope：官方「魔粒」余额接口。
 *
 * <p>余额端点：GET https://www.modelscope.cn/openapi/v1/magicubes/balance
 * 请求头：Authorization: Bearer &lt;访问令牌&gt;（控制台「访问令牌」生成，形如 ms-…）
 * 成功响应示例：
 * {"success":true,"data":{"total_balance":281,"available_balance":281,"frozen_amount":0}}
 *
 * <p>注意：该端点位于 www.modelscope.cn 的 /openapi/v1/ 前缀（不是 /api/v1/、也不是
 * api-inference.modelscope.cn 网关），因此固定使用官方地址，不随可配置的 Base URL 变化。
 * Base URL 仅用于模型网关（/models）的兜底 Key 校验。
 */
public final class ModelScopeProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://api-inference.modelscope.cn/v1";

    /** 魔粒余额端点：独立于模型网关主机，官方实测地址（2026-10 经真实 Key 验证）。 */
    private static final String BALANCE_URL = "https://www.modelscope.cn/openapi/v1/magicubes/balance";

    /** 平台个人中心页，作为请求来源标识（官方前端同款，防风控误杀）。 */
    private static final String REFERER = "https://www.modelscope.cn/my/overview";

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
            HttpSupport.Response resp = http.get(BALANCE_URL,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json",
                            "Content-Type", "application/json",
                            "Referer", REFERER));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                                BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限（modelscope.cn 控制台 → 访问令牌 重新生成）")
                        .build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                                BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null || !root.isObject()) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            if (!JsonSupport.bool(root, "success", false)) {
                // Key 可能有效但端点拒绝（常见：账号未完善个人信息/未实名，魔粒不可用）
                return handleEndpointRejected(config, root);
            }
            return fromJson(config.getId(), config.getName(), root);
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

    /** 解析魔粒余额成功响应（可独立测试）。 */
    static BalanceSnapshot fromJson(String accountId, String accountName, JsonNode root) {
        JsonNode data = root.get("data");
        Double total = JsonSupport.number(data, "total_balance");
        if (total == null) {
            return BalanceSnapshot.of(ProviderId.MODELSCOPE, accountId, accountName,
                            BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 total_balance（上游可能改版）").build();
        }
        Double available = JsonSupport.number(data, "available_balance");
        Double frozen = JsonSupport.number(data, "frozen_amount");
        double used = Math.max(0, total - (available == null ? total : available));

        return BalanceSnapshot.ok(ProviderId.MODELSCOPE, accountId, accountName)
                .label("魔粒余额")
                .amount(total)
                .currency("魔粒")
                .metric(ProviderCapability.BALANCE, "魔粒余额", total, "魔粒", null,
                        "魔搭平台积分，随模型推理消耗")
                .detail("可用魔粒", fmt(available))
                .detail("已用魔粒", fmt(used))
                .detail("冻结魔粒", fmt(frozen))
                .message("Key 有效")
                .build();
    }

    /**
     * success=false 时的处理：先用 /models 再校验一次 Key，区分「Key 无效」与
     * 「Key 有效但端点拒绝」（魔搭规则：魔粒需完善个人信息/实名认证后方可使用）。
     */
    private BalanceSnapshot handleEndpointRejected(AccountConfig config, JsonNode root) {
        String code = JsonSupport.text(root, "code");
        String message = JsonSupport.text(root, "message");
        String suffix = (code == null || code.isBlank()) && (message == null || message.isBlank())
                ? "" : "（" + nz(code) + " " + nz(message) + "）".trim();
        try {
            HttpSupport.Response models = http.get(normalize(config.getBaseUrl()) + "/models",
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (models.status() == 401 || models.status() == 403) {
                return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                                BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权限").build();
            }
        } catch (HttpSupport.HttpFailure ignored) {
            // 网络失败不改变结论，以下方 message 为准
        }
        return BalanceSnapshot.of(ProviderId.MODELSCOPE, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                .message("魔搭未返回魔粒余额" + suffix
                        + "。若为刚注册账号，请先在 modelscope.cn 完善个人信息并实名认证（平台规则：魔粒需个人信息完善后方可使用）")
                .build();
    }

    private static String fmt(Double v) {
        if (v == null) {
            return "-";
        }
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.format("%.0f", v);
        }
        return String.format("%.2f", v);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "" : s;
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

    /** 从错误响应体中提取 message（非 JSON 时截取片段）。 */
    static String firstError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            JsonNode message = root.get("message");
            if (message != null) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，截取片段
        }
        String s = body.strip();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}