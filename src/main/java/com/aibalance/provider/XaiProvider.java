package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * xAI / Grok：Management API 真实预付余额。
 * <p>
 * 接入：API Key 填 <b>xAI Management API Key</b>（控制台 Billing → 创建 Management Key；
 * 不是日常调用 Grok 的 inference API Key），团队 ID 在「附加参数」填 team_id=xxx
 * （组织级 Key 无法自动推断团队，必须填写；团队级 Key 亦建议填写）。
 * 接口：GET /v1/billing/teams/{team_id}/prepaid/balance —— 官方文档
 *（docs.x.ai Management API Billing，已核实）定义该接口为「List prepaid credit balance
 * and balance changes」，返回 {"changes":[…],"total":{"val":"…"}}，total.val 为
 * USD 美分字符串（可为负）。无真实 Management Key 无法真机验证，按官方文档实现并标注。
 */
public final class XaiProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://management-api.x.ai";

    private final HttpSupport http;

    public XaiProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.XAI;
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 xAI Management API Key").build();
        }
        String teamId = config.parseExtraParams().get("team_id");
        if (teamId == null || teamId.isBlank()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED)
                    .message("缺少团队 ID：请在「附加参数」填写 team_id=xxx（xAI 控制台团队 ID）").build();
        }
        try {
            String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                    ? DEFAULT_BASE : config.getBaseUrl().strip().replaceAll("/+$", "");
            String url = base + "/v1/billing/teams/" + teamId.strip() + "/prepaid/balance";
            HttpSupport.Response resp = http.get(url,
                    Map.of("Authorization", "Bearer " + config.getApiKey().strip(),
                            "Accept", "application/json"));
            HttpSupport.logDiagnostic("xai prepaid status=" + resp.status()
                    + " body=" + truncate(resp.body()));
            if (resp.status() == 401) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Management API Key 无效").build();
            }
            if (resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("Key 无权访问该团队余额（组织级 Key 需填对 team_id）").build();
            }
            if (resp.status() == 404) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("团队 ID 不存在或路径错误（HTTP 404）").build();
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

    /**
     * 解析 prepaid/balance 响应（可独立测试）。
     * 官方结构（docs.x.ai Management API，已核实）：
     *   {"changes":[{changeOrigin,topupStatus,amount:{val:"-1000"},invoiceNumber,createTime}],
     *    "total":{"val":"-1000"}}
     * 其中 val 为 USD 美分字符串（可为负，负值=欠费/扣款）。
     * 兼容旧候选路径：prepaid_balance.available_credits / balance / credits / amount。
     */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        if (root.has("error") && root.get("error").isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("xAI 错误: " + JsonSupport.text(root.get("error"), "message")).build();
        }
        Double amountUsd = null;
        String totalVal = null;
        // 官方路径：total.val（USD 美分字符串，可为负）
        JsonNode total = root.get("total");
        if (total != null && total.isObject()) {
            Double cents = JsonSupport.number(total, "val");
            if (cents != null) {
                amountUsd = cents / 100.0;
                totalVal = String.format("%.0f", cents);
            }
        }
        // 兼容候选：旧版/第三方实现字段
        if (amountUsd == null) {
            amountUsd = fallbackAmount(root);
        }
        if (amountUsd == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("无法识别响应格式: " + truncate(root.toString())).build();
        }
        String teamId = JsonSupport.text(root, "team_id");
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);
        builder.label("预付余额").currency("USD").amount(amountUsd);
        builder.metric(ProviderCapability.BALANCE, "预付余额", amountUsd, "USD", null,
                teamId == null ? "xAI prepaid/balance（美分）" : "团队 " + teamId + "（美分）");
        if (totalVal != null) {
            builder.detail("余额（美分）", totalVal);
        }
        // changes[]：最近 3 条余额变动（amount.val 为美分，负=支出）
        JsonNode changes = root.get("changes");
        if (changes != null && changes.isArray()) {
            int shown = 0;
            for (JsonNode c : changes) {
                if (c == null || !c.isObject() || shown >= 3) {
                    break;
                }
                String origin = JsonSupport.text(c, "changeOrigin");
                Double amt = null;
                JsonNode amtNode = c.get("amount");
                if (amtNode != null && amtNode.isObject()) {
                    amt = JsonSupport.number(amtNode, "val");
                }
                String time = JsonSupport.text(c, "createTime");
                String timeShort = time == null || time.length() < 16 ? time : time.substring(0, 16);
                String label = origin == null ? "变动" : "变动 " + origin;
                StringBuilder val = new StringBuilder();
                if (amt != null) {
                    val.append(String.format("%+.2f USD", amt / 100.0));
                }
                if (timeShort != null && !timeShort.isBlank()) {
                    if (val.length() > 0) {
                        val.append(" · ");
                    }
                    val.append(timeShort);
                }
                if (val.length() > 0) {
                    builder.detail(label, val.toString());
                }
                shown++;
            }
        }
        return builder.build();
    }

    /** 兼容旧字段候选：prepaid_balance.available_credits / .credits / .amount / balance / credits / amount。 */
    private static Double fallbackAmount(JsonNode root) {
        Double amount = null;
        JsonNode pb = root.get("prepaid_balance");
        if (pb != null && pb.isObject()) {
            amount = JsonSupport.number(pb, "available_credits");
            if (amount == null) {
                amount = JsonSupport.number(pb, "credits");
            }
            if (amount == null) {
                amount = JsonSupport.number(pb, "amount");
            }
        }
        if (amount == null) {
            JsonNode bal = root.get("balance");
            if (bal != null && bal.isObject()) {
                amount = JsonSupport.number(bal, "available_credits");
                if (amount == null) {
                    amount = JsonSupport.number(bal, "credits");
                }
                if (amount == null) {
                    amount = JsonSupport.number(bal, "amount");
                }
            }
        }
        if (amount == null) {
            amount = JsonSupport.number(root, "balance");
        }
        if (amount == null) {
            amount = JsonSupport.number(root, "credits");
        }
        if (amount == null) {
            amount = JsonSupport.number(root, "amount");
        }
        if (amount == null && root.isNumber()) {
            amount = root.doubleValue();
        }
        return amount;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
