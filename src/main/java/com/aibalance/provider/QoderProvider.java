package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Qoder：支持两种查询方式。
 * <p>
 * <b>方式三（个人用量，默认）：</b>GET {base}/api/v3/user/status，Bearer PAT 认证，
 * 返回 totalCredits/totalCharged/currentPlan。
 * <p>
 * <b>方式二（组织用量，需填附加参数）：</b>GET {base}/v1/organizations/{org_id}/members/{member_id}/usage-events，
 * 需要在附加参数中填 org_id=xxx; member_id=xxx。Base URL 需改为 https://api.qoder.com.cn。
 */
public final class QoderProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public QoderProvider(HttpSupport http, ProviderId pid) {
        this.http = http;
        this.pid = pid;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 Qoder API Key").build();
        }
        Map<String, String> extras = config.parseExtraParams();
        String orgId = extras.get("org_id");
        String memberId = extras.get("member_id");
        boolean teamsMode = orgId != null && !orgId.isBlank()
                && memberId != null && !memberId.isBlank();

        try {
            String url;
            if (teamsMode) {
                // Teams 模式：使用官方 Teams API 域名
                // 国内站 api.qoder.com.cn，国际站 api.qoder.com
                String teamsBase = (pid == ProviderId.QODER_INTL)
                        ? "https://api.qoder.com"
                        : "https://api.qoder.com.cn";
                url = teamsBase + "/v1/organizations/" + orgId.strip()
                        + "/members/" + memberId.strip() + "/quota";
            } else {
                // 个人模式：国内站 openapi.qoder.com.cn，国际站 openapi.qoder.sh
                String personalBase = (pid == ProviderId.QODER_INTL)
                        ? "https://openapi.qoder.sh"
                        : "https://openapi.qoder.com.cn";
                url = personalBase + "/api/v3/user/status";
            }
            HttpSupport.Response resp = http.get(url,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("qoder " + (teamsMode ? "usage-events" : "user/status")
                    + " status=" + resp.status() + " body=" + truncate(resp.body()));
            if (resp.status() == 401) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效或已过期").build();
            }
            if (resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message(teamsMode ? "Key 无权访问该组织成员用量（确认 Key 绑定组织一致）"
                                : "Key 无权访问用户状态接口").build();
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
            return teamsMode
                    ? parseTeamsUsage(pid, config.getId(), config.getName(), root)
                    : parseUserStatus(pid, config.getId(), config.getName(), root);
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

    /** 方式三：解析 user/status 响应。 */
    static BalanceSnapshot parseUserStatus(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode data = root.has("data") ? root.get("data") : root;
        Double totalCredits = JsonSupport.number(data, "totalCredits");
        if (totalCredits == null) {
            totalCredits = JsonSupport.number(data, "remainingCredits");
        }
        if (totalCredits == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("无法识别响应格式: " + truncate(root.toString())).build();
        }
        Double totalCharged = JsonSupport.number(data, "totalCharged");
        JsonNode plan = data.get("currentPlan");
        String planName = plan != null ? JsonSupport.text(plan, "name") : null;

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);
        b.label("剩余 Credits").currency("Credits").amount(totalCredits);
        StringBuilder detail = new StringBuilder(String.format("%.2f Credits", totalCredits));
        if (totalCharged != null && totalCharged > 0) {
            detail.append(" · 已消耗 ").append(String.format("%.2f", totalCharged));
        }
        if (planName != null && !planName.isBlank()) {
            detail.append(" · 套餐 ").append(planName);
            b.detail("当前套餐", planName);
        }
        Double creditLimit = JsonSupport.number(data, "creditLimit");
        if (creditLimit == null) creditLimit = JsonSupport.number(data, "totalLimit");
        Double percent = (creditLimit != null && creditLimit > 0)
                ? totalCredits / creditLimit * 100.0 : null;
        b.metric(ProviderCapability.QUOTA, "剩余 Credits", totalCredits, "Credits", percent,
                "Qoder user/status");
        return b.build();
    }

    /** Teams 模式：解析成员配额响应。 */
    static BalanceSnapshot parseTeamsUsage(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode data = root.has("data") ? root.get("data") : root;
        Double remainingCredits = JsonSupport.number(data, "remainingCredits");
        if (remainingCredits == null) remainingCredits = JsonSupport.number(data, "remaining");
        if (remainingCredits == null) remainingCredits = JsonSupport.number(data, "credits");
        Double totalCredits = JsonSupport.number(data, "totalCredits");
        if (totalCredits == null) totalCredits = JsonSupport.number(data, "total");

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);
        if (remainingCredits != null) {
            b.label("剩余 Credits").currency("Credits").amount(remainingCredits);
            Double percent = (totalCredits != null && totalCredits > 0)
                    ? remainingCredits / totalCredits * 100.0 : null;
            b.metric(ProviderCapability.QUOTA, "剩余 Credits", remainingCredits, "Credits", percent,
                    "Qoder Teams 成员配额");
        } else {
            b.label("已消耗 Credits").currency("Credits").amount(0.0);
        }
        if (totalCredits != null) {
            b.detail("总配额", String.format("%.2f", totalCredits));
        }
        return b.build();
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
