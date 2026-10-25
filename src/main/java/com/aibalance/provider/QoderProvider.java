package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Qoder（国内 qoder.com.cn / 国际 qoder.com）专用 Provider。
 *
 * <p>Qoder 的 OpenAPI 不直接接受 PAT 作为 Bearer，需要先兑换短期 Job Token：
 * <ol>
 *   <li>POST {base}/api/v1/jobToken/exchange，body 为 {@code {"personal_token": "<PAT>"}}，
 *       返回 {@code {"token": "<jobToken>"}}；</li>
 *   <li>GET {base}/api/v2/quota/usage，Authorization: Bearer &lt;jobToken&gt;，
 *       返回个人/组织/赠额的 Credits 余量；</li>
 *   <li>GET {base}/api/v2/user/plan，返回当前套餐信息（可选）。</li>
 * </ol>
 *
 * <p>响应结构（usage）：
 * <pre>{@code
 * {
 *   "userQuota":           { "total": 5000, "used": 1200, "remaining": 3800,
 *                            "percentage": 24, "unit": "credits", "available": true },
 *   "addOnQuota":          { ... },
 *   "orgResourcePackage":  { ... },
 *   "totalUsagePercentage": 24,
 *   "isQuotaExceeded":      false,
 *   "expiresAt":            1780000000000
 * }
 * }</pre>
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

        try {
            String base = resolveBase(config);
            String jobToken = exchangeJobToken(base, config.getApiKey().strip());
            if (jobToken == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效或已过期").build();
            }

            HttpSupport.Response usageResp = http.get(base + "/api/v2/quota/usage",
                    Map.of("Authorization", "Bearer " + jobToken, "Accept", "application/json"));
            if (usageResp.status() == 401 || usageResp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED).message("API Key 无效或已过期").build();
            }
            if (usageResp.status() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("查询额度失败: HTTP " + usageResp.status()).build();
            }
            JsonNode usage = usageResp.bodyAsJson();
            if (usage == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("响应格式异常").build();
            }

            // 可选：套餐信息（失败不影响主展示）
            JsonNode plan = null;
            try {
                HttpSupport.Response planResp = http.get(base + "/api/v2/user/plan",
                        Map.of("Authorization", "Bearer " + jobToken, "Accept", "application/json"));
                if (planResp.status() == 200) {
                    plan = planResp.bodyAsJson();
                }
            } catch (Exception ignored) {
                // 套餐信息可选
            }

            return buildSnapshot(config, usage, plan);
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

    /** 兑换 Job Token；PAT 无效或兑换失败返回 null。 */
    private String exchangeJobToken(String base, String pat) {
        try {
            HttpSupport.Response resp = http.post(base + "/api/v1/jobToken/exchange",
                    Map.of("Content-Type", "application/json", "Accept", "application/json"),
                    "{\"personal_token\":\"" + JsonSupport.escape(pat) + "\"}");
            if (resp.status() == 401 || resp.status() == 403 || resp.status() == 400) {
                return null;
            }
            if (resp.status() != 200) {
                return null;
            }
            JsonNode root = resp.bodyAsJson();
            return root == null ? null : JsonSupport.text(root, "token");
        } catch (Exception e) {
            return null;
        }
    }

    private BalanceSnapshot buildSnapshot(AccountConfig config, JsonNode usage, JsonNode plan) {
        // 组织模式（附加参数 org_id）优先展示 orgResourcePackage，否则展示个人 userQuota
        Map<String, String> extras = config.parseExtraParams();
        boolean orgMode = extras.get("org_id") != null && !extras.get("org_id").isBlank();
        return parseUsage(pid, config.getId(), config.getName(), usage, plan, orgMode);
    }

    /** 解析额度响应（包内可见，供单元测试）。 */
    static BalanceSnapshot parseUsage(ProviderId pid, String accountId, String accountName,
                                      JsonNode usage, JsonNode plan, boolean orgMode) {
        Quota user = parseQuota(usage, orgMode ? "orgResourcePackage" : "userQuota");
        Quota addOn = parseQuota(usage, "addOnQuota");

        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName);

        if (user != null && user.remaining != null) {
            String unit = user.unit == null ? "Credits" : capitalize(user.unit);
            builder.label("剩余 Credits").currency(unit).amount(user.remaining);
            Double percent = (user.total != null && user.total > 0)
                    ? user.remaining / user.total * 100.0 : null;
            builder.percent(percent);
            builder.metric(ProviderCapability.QUOTA, "剩余 Credits", user.remaining, unit, percent,
                    orgMode ? "Qoder 组织资源包" : "Qoder 个人额度");
            if (user.total != null) {
                builder.detail("总配额", String.format("%.2f %s", user.total, unit));
            }
            if (user.used != null) {
                builder.detail("已使用", String.format("%.2f %s", user.used, unit));
            }
        } else {
            builder.label("剩余 Credits").currency("Credits").amount(null);
        }

        if (addOn != null && addOn.remaining != null && addOn.remaining > 0) {
            String unit = addOn.unit == null ? "Credits" : capitalize(addOn.unit);
            builder.detail("赠额 Credits", String.format("%.2f %s", addOn.remaining, unit));
        }

        Double expiresAt = JsonSupport.number(usage, "expiresAt");
        if (expiresAt != null && expiresAt > 0) {
            builder.detail("额度到期", formatEpoch(expiresAt.longValue()));
        }

        // 套餐信息
        if (plan != null && plan.isObject()) {
            String planName = JsonSupport.text(plan, "planTierName");
            if (planName == null) {
                planName = JsonSupport.text(plan, "planTier");
            }
            if (planName != null && !planName.isBlank()) {
                builder.detail("当前套餐", planName);
            }
            String endDate = JsonSupport.text(plan, "endDate");
            if (endDate == null) {
                endDate = JsonSupport.text(plan, "end_date");
            }
            if (endDate != null && !endDate.isBlank()) {
                builder.detail("套餐到期", endDate);
            }
        }

        return builder.build();
    }

    /** 解析 usage 响应中的一个额度对象。 */
    private static Quota parseQuota(JsonNode usage, String field) {
        JsonNode q = usage == null ? null : usage.get(field);
        if (q == null || !q.isObject()) {
            return null;
        }
        Double total = JsonSupport.number(q, "total");
        if (total == null) {
            total = JsonSupport.number(q, "cap");
        }
        Double used = JsonSupport.number(q, "used");
        Double remaining = JsonSupport.number(q, "remaining");
        if (remaining == null && total != null && used != null) {
            remaining = Math.max(0, total - used);
        }
        return new Quota(total, used, remaining, JsonSupport.text(q, "unit"));
    }

    private static String formatEpoch(long millis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(millis));
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return "Credits";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String resolveBase(AccountConfig config) {
        String base = normalize(config.getBaseUrl());
        if (base != null) {
            return base;
        }
        // 配置为空时按地区使用官方 OpenAPI 域名
        return pid == ProviderId.QODER_INTL
                ? "https://openapi.qoder.sh"
                : "https://openapi.qoder.com.cn";
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

    /** 一个额度对象（total/used/remaining/unit）。 */
    private static final class Quota {
        final Double total;
        final Double used;
        final Double remaining;
        final String unit;

        Quota(Double total, Double used, Double remaining, String unit) {
            this.total = total;
            this.used = used;
            this.remaining = remaining;
            this.unit = unit;
        }
    }
}