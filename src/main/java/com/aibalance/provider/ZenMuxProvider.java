package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * ZenMux 专用 Provider。
 * 官方 Management API（Bearer Management API Key）：
 * <ul>
 *   <li>GET {base}/api/v1/management/subscription/detail → 订阅套餐与各时间窗口额度</li>
 *   <li>GET {base}/api/v1/management/payg/balance         → PAYG 预存余额（Credits）</li>
 * </ul>
 */
public final class ZenMuxProvider implements BalanceProvider {

    private final HttpSupport http;
    private final ProviderId pid;

    public ZenMuxProvider(HttpSupport http, ProviderId pid) {
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
            // 官方域名：zenmux.ai 在部分网络环境不可达时，自动回退到 zenmux.dev（同一套 Management API）
            String primary = base;
            String fallback = primary.contains("zenmux.dev")
                    ? null : "https://zenmux.dev";

            JsonNode subscription = fetchData(primary, "/api/v1/management/subscription/detail", config, fallback);
            JsonNode payg = fetchData(primary, "/api/v1/management/payg/balance", config, fallback);

            if ((subscription == null || subscription.get("data") == null)
                    && (payg == null || payg.get("data") == null)) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("未获取到 ZenMux 额度数据（检查 API Key 是否为 Management Key）").build();
            }

            return buildSnapshot(config, subscription, payg);
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

    /** 请求指定端点；主域名网络失败时自动尝试备用域名。 */
    private JsonNode fetchData(String base, String path, AccountConfig config, String fallback) {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + config.getApiKey().strip(),
                "Accept", "application/json");
        try {
            HttpSupport.Response r = http.get(base + path, headers);
            return r.status() == 200 ? r.bodyAsJson() : null;
        } catch (HttpSupport.HttpFailure e) {
            if (fallback != null && e.isNetwork()) {
                try {
                    HttpSupport.Response r = http.get(fallback + path, headers);
                    return r.status() == 200 ? r.bodyAsJson() : null;
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode subscription, JsonNode payg) {
        return parseData(pid, config.getId(), config.getName(), subscription, payg);
    }

    /** 解析订阅 + PAYG 响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseData(ProviderId pid, String accountId, String accountName,
                                     JsonNode subscription, JsonNode payg) {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);

        JsonNode subData = subscription != null && subscription.has("data") && subscription.get("data").isObject()
                ? subscription.get("data") : null;
        JsonNode paygData = payg != null && payg.has("data") && payg.get("data").isObject()
                ? payg.get("data") : null;

        // PAYG 余额作为主展示（入口按量付费语义）
        Double totalCredits = paygData == null ? null : JsonSupport.number(paygData, "total_credits");
        if (totalCredits != null) {
            b.label("PAYG 余额").currency("USD").amount(totalCredits);
            b.metric(ProviderCapability.QUOTA, "PAYG 余额", totalCredits, "USD", null,
                    "ZenMux 预存 Credits");
            Double topUp = JsonSupport.number(paygData, "top_up_credits");
            Double bonus = JsonSupport.number(paygData, "bonus_credits");
            if (topUp != null) {
                b.detail("充值 Credits", String.format("$%.2f", topUp));
            }
            if (bonus != null) {
                b.detail("赠送 Credits", String.format("$%.2f", bonus));
            }
        } else {
            b.label("订阅额度").currency("").amount(null);
        }

        // 订阅信息与额度窗口
        if (subData != null) {
            JsonNode plan = subData.get("plan");
            if (plan != null && plan.isObject()) {
                String tier = JsonSupport.text(plan, "tier");
                String expiresAt = JsonSupport.text(plan, "expires_at");
                if (tier != null && !tier.isBlank()) {
                    b.detail("订阅套餐", tier);
                }
                if (expiresAt != null && !expiresAt.isBlank()) {
                    b.detail("套餐到期", expiresAt);
                }
            }
            String accountStatus = JsonSupport.text(subData, "account_status");
            if (accountStatus != null && !accountStatus.isBlank()) {
                b.detail("账户状态", accountStatus);
            }
            addWindow(b, subData, "quota_5_hour", "5小时窗口");
            addWindow(b, subData, "quota_7_day", "7天窗口");
            addMonthly(b, subData, "quota_monthly", "每月窗口");
        }

        return b.build();
    }

    /** 解析一个滚动窗口：usage_percentage 为已用比例（0-1），剩余 = (1 - usage) * 100。 */
    private static void addWindow(BalanceSnapshot.Builder b, JsonNode subData, String key, String label) {
        JsonNode w = subData == null ? null : subData.get(key);
        if (w == null || !w.isObject()) {
            return;
        }
        Double usedRatio = JsonSupport.number(w, "usage_percentage");
        Double remainingFlows = JsonSupport.number(w, "remaining_flows");
        Double maxFlows = JsonSupport.number(w, "max_flows");
        String resetsAt = JsonSupport.text(w, "resets_at");

        double remainingPercent = usedRatio != null
                ? Math.max(0, Math.min(100, (1.0 - usedRatio) * 100.0))
                : (maxFlows != null && maxFlows > 0 && remainingFlows != null
                        ? remainingFlows / maxFlows * 100.0 : -1);

        if (remainingPercent >= 0) {
            b.detail(label, String.format("剩余 %.1f%%", remainingPercent));
            if (resetsAt != null && !resetsAt.isBlank()) {
                b.detail(label + "重置", resetsAt);
            }
            b.metric(ProviderCapability.QUOTA, label + "额度", remainingPercent, "%", remainingPercent,
                    "ZenMux 滚动窗口");
        }
        if (remainingFlows != null) {
            b.detail(label + "剩余 Flows", String.format("%.1f", remainingFlows));
        }
    }

    /** 每月窗口只有上限（无实时用量），展示上限信息。 */
    private static void addMonthly(BalanceSnapshot.Builder b, JsonNode subData, String key, String label) {
        JsonNode w = subData == null ? null : subData.get(key);
        if (w == null || !w.isObject()) {
            return;
        }
        Double maxFlows = JsonSupport.number(w, "max_flows");
        Double maxValueUsd = JsonSupport.number(w, "max_value_usd");
        if (maxFlows != null) {
            b.detail(label + "上限", String.format("%.0f Flows", maxFlows));
        }
        if (maxValueUsd != null) {
            b.detail(label + "上限金额", String.format("$%.2f", maxValueUsd));
        }
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