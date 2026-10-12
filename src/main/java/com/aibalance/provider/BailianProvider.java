package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 阿里云百炼（通义千问）模型限流配额查询。
 * 官方接口：GET {base}/api/v1/models/limits?page_size=100（Bearer 普通 API Key）
 *   base 必须填写完整业务空间地址：
 *   https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com
 * 返回 output.quotas[].model_limit：各模型的请求频率限制（request_limit /
 * request_limit_period）与用量限制（usage_limit / usage_limit_field /
 * usage_limit_period）。
 * 口径说明：这是「API Key 下各模型的限流配额」，不是账户余额、也不是订阅套餐；
 * 百炼 Coding Plan（订阅）需要 CLI 登录态，无 API Key 通道。
 */
public final class BailianProvider implements BalanceProvider {

    private static final String LIMITS_PATH = "/api/v1/models/limits?page_size=100";
    private static final int MAX_DETAIL_MODELS = 30;

    private final HttpSupport http;

    public BailianProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未配置 API Key").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR)
                    .message("需填写完整 Base URL（https://{业务空间ID}.cn-beijing.maas.aliyuncs.com）").build();
        }
        try {
            HttpSupport.Response resp = http.get(base + LIMITS_PATH,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("API Key 无效或无权访问（检查业务空间 Base URL 与 Key）").build();
            }
            if (resp.status() != 200) {
                return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp.status() + " " + DeepSeekProvider.firstError(resp.body())).build();
            }
            JsonNode root = resp.bodyAsJson();
            if (root == null) {
                return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }
            return fromJson(config.getId(), config.getName(), root);
        } catch (HttpSupport.HttpFailure e) {
            return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            return BalanceSnapshot.of(ProviderId.BAILIAN, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        }
    }

    /** 解析模型限额响应（可独立测试）。 */
    static BalanceSnapshot fromJson(String accountId, String accountName, JsonNode root) {
        JsonNode output = root == null ? null : root.get("output");
        JsonNode quotas = output == null ? null : output.get("quotas");
        if (quotas == null || !quotas.isArray() || quotas.isEmpty()) {
            return BalanceSnapshot.of(ProviderId.BAILIAN, accountId, accountName,
                    BalanceSnapshot.Status.ERROR).message("未解析到模型配额数据").build();
        }
        int total = quotas.size();
        Double totalModels = JsonSupport.number(output, "total");
        if (totalModels != null) {
            total = (int) Math.round(totalModels);
        }

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.BAILIAN, accountId, accountName)
                .label("模型限流配额").currency("个");
        b.amount((double) total);
        b.message("已查询到 " + total + " 个模型配额");
        int shown = 0;
        for (JsonNode q : quotas) {
            if (shown >= MAX_DETAIL_MODELS) {
                break;
            }
            String model = JsonSupport.text(q, "model");
            JsonNode ml = q.get("model_limit");
            if (model == null || ml == null || !ml.isObject()) {
                continue;
            }
            String text = formatLimit(ml);
            b.detail(model, text);
            shown++;
        }
        if (total > shown) {
            b.detail("其他模型", "另有 " + (total - shown) + " 个模型（可在控制台查看完整列表）");
        }
        b.detail("口径", "各模型请求/用量限流（非余额、非订阅套餐）");
        return b.build();
    }

    private static String formatLimit(JsonNode ml) {
        Double reqLimit = JsonSupport.number(ml, "request_limit");
        Double reqPeriod = JsonSupport.number(ml, "request_limit_period");
        Double usageLimit = JsonSupport.number(ml, "usage_limit");
        Double usagePeriod = JsonSupport.number(ml, "usage_limit_period");
        StringBuilder sb = new StringBuilder();
        if (reqLimit != null) {
            String per = reqPeriod != null && Math.round(reqPeriod) == 60 ? "分钟" : "秒";
            sb.append("请求 ").append(trim(reqLimit)).append(" 次/").append(per);
        }
        if (usageLimit != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            String field = JsonSupport.text(ml, "usage_limit_field");
            sb.append("用量 ").append(trim(usageLimit));
            if (field != null) {
                sb.append(" ").append(field);
            }
            if (usagePeriod != null) {
                String per = Math.round(usagePeriod) == 60 ? "分钟" : "秒";
                sb.append("/").append(per);
            }
        }
        return sb.length() == 0 ? "无限流配置" : sb.toString();
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }
}
