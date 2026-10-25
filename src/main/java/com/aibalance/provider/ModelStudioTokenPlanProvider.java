package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云百炼·Token Plan 额度：Model Studio OpenAPI（ACS3-HMAC-SHA256 签名）。
 * <p>
 * 官方接口（华北2 北京，AccessKey 签名，不支持 Bearer API Key）：
 * <ul>
 *   <li>GET /tokenplan/subscription/shared-packages（x-acs-action: ListSubscriptionSharedPackages）——共享用量包</li>
 *   <li>GET /tokenplan/subscription/seat-detail（x-acs-action: GetSubscriptionSeatDetails）——订阅席位明细</li>
 * </ul>
 * 返回 Data.Items[].EquityList[]（EquityType=CREDITS）的 CycleTotalValue（周期总额度）/
 * CycleSurplusValue（周期剩余额度）/ CycleStartTime / CycleEndTime（毫秒或秒）。
 * 认证：API Key 字段填 AccessKeyId，Secret 字段填 AccessKeySecret（均 DPAPI 加密存储）。
 * 无真实 AccessKey 无法真机验证，本实现按官方 ACS3 签名协议与响应字段解析。
 */
public final class ModelStudioTokenPlanProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://modelstudio.cn-beijing.aliyuncs.com";
    private static final String VERSION = "2026-02-10";
    private static final String ALGORITHM = "ACS3-HMAC-SHA256";
    private static final String SHARED_PATH = "/tokenplan/subscription/shared-packages";
    private static final String SEAT_PATH = "/tokenplan/subscription/seat-detail";
    private static final String ACTION_SHARED = "ListSubscriptionSharedPackages";
    private static final String ACTION_SEAT = "GetSubscriptionSeatDetails";
    private static final int PAGE_SIZE = 50;
    /** 单次快照最多展示的 Credits 权益条数（多席位/多共享包时的进度条上限）。 */
    private static final int MAX_ENTRIES = 30;
    /** 兼容毫秒/秒两种时间戳：大于该值视为毫秒。 */
    private static final long MS_THRESHOLD = 1_000_000_000_000L;
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final HttpSupport http;

    public ModelStudioTokenPlanProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.BAILIAN_TOKENPLAN;
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKeyId").build();
        }
        if (!config.hasValidSecret()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKeySecret").build();
        }
        String base = normalize(config.getBaseUrl());
        if (base == null) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.ERROR).message("需填写 Base URL（默认华北2北京）").build();
        }
        String ak = config.getApiKey().strip();
        String sk = config.getSecret().strip();
        try {
            // 接口 1：共享用量包
            SignedRequest shared = buildSignedRequest(ak, sk, base, SHARED_PATH, ACTION_SHARED,
                    Map.of("PageNo", "1", "PageSize", String.valueOf(PAGE_SIZE)));
            HttpSupport.Response resp1 = http.get(shared.url, shared.headers);
            HttpSupport.logDiagnostic("bailian-tokenplan action=ListSubscriptionSharedPackages"
                    + " status=" + resp1.status() + " body=" + truncate(resp1.body()));
            JsonNode root1 = resp1.bodyAsJson();

            // 接口 2：订阅席位明细
            SignedRequest seat = buildSignedRequest(ak, sk, base, SEAT_PATH, ACTION_SEAT,
                    Map.of("PageNo", "1", "PageSize", String.valueOf(PAGE_SIZE)));
            HttpSupport.Response resp2 = http.get(seat.url, seat.headers);
            HttpSupport.logDiagnostic("bailian-tokenplan action=GetSubscriptionSeatDetails"
                    + " status=" + resp2.status() + " body=" + truncate(resp2.body()));
            JsonNode root2 = resp2.bodyAsJson();

            if (resp1.status() == 401 || resp1.status() == 403 || resp2.status() == 401 || resp2.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("AccessKey 无效或无调用权限（RAM 需 AliyunModelStudioReadOnlyAccess，勿用主账号 AK）").build();
            }
            List<JsonNode> roots = new ArrayList<>();
            if (resp1.status() == 200 && root1 != null) {
                roots.add(root1);
            }
            if (resp2.status() == 200 && root2 != null) {
                roots.add(root2);
            }
            if (roots.isEmpty()) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + resp1.status() + " " + DeepSeekProvider.firstError(resp1.body())).build();
            }
            return fromJson(pid, config.getId(), config.getName(), roots);
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

    /** 签名请求（URL + 请求头，可独立测试）。 */
    static final class SignedRequest {
        final String url;
        final Map<String, String> headers;

        SignedRequest(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /** 构造带 ACS3-HMAC-SHA256 签名的 GET 请求（当前时间）。 */
    static SignedRequest buildSignedRequest(String ak, String sk, String base, String path,
                                            String action, Map<String, String> query) {
        return buildSignedRequestAt(ak, sk, base, path, action, query,
                UTC_DATE.format(Instant.now()), UUID.randomUUID().toString());
    }

    /** 指定日期与 nonce 构造 GET 签名请求（生产入口）。 */
    static SignedRequest buildSignedRequestAt(String ak, String sk, String base, String path,
                                              String action, Map<String, String> query,
                                              String date, String nonce) {
        return signRequest("GET", ak, sk, hostOf(base), VERSION, path, action, query, date, nonce);
    }

    /**
     * 通用 ACS3-HMAC-SHA256 签名（可独立测试；官方示例为 POST + 空请求体）。
     * <p>
     * 严格遵循阿里云官方 V3 签名机制：
     * <ul>
     *   <li>CanonicalRequest = Method\n + CanonicalURI\n + CanonicalQueryString\n +
     *       CanonicalHeaders\n + SignedHeaders\n + HashedRequestPayload</li>
     *   <li>参与签名的头：host + 全部 x-acs-* 头，键小写并按字典序；值去除首尾空白</li>
     *   <li>GET 无请求体：HashedRequestPayload = SHA256(空串)（官方固定值
     *       e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855）</li>
     *   <li>StringToSign = ACS3-HMAC-SHA256\n + SHA256(CanonicalRequest)</li>
     *   <li>Signature = 小写十六进制 HMAC-SHA256(AccessKeySecret, StringToSign)</li>
     *   <li>AccessKeySecret 只参与 HMAC，不出现在任何请求头</li>
     * </ul>
     */
    static SignedRequest signRequest(String method, String ak, String sk, String host,
                                     String version, String path, String action,
                                     Map<String, String> query, String date, String nonce) {
        String payloadHash = SignUtil.sha256Hex("");
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-acs-action", action);
        headers.put("x-acs-version", version);
        headers.put("x-acs-date", date);
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-content-sha256", payloadHash);

        StringBuilder cq = new StringBuilder();
        Map<String, String> q = new TreeMap<>(query);
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (cq.length() > 0) {
                cq.append("&");
            }
            cq.append(SignUtil.percentEncode(e.getKey()))
                    .append("=").append(SignUtil.percentEncode(e.getValue()));
        }
        String canonicalQuery = cq.toString();

        List<String> signedKeys = new ArrayList<>(headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String k : signedKeys) {
            canonicalHeaders.append(k).append(":").append(headers.get(k).trim()).append("\n");
        }
        String signedHeaders = String.join(";", signedKeys);

        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String hashedCanonical = SignUtil.sha256Hex(canonicalRequest);
        String stringToSign = ALGORITHM + "\n" + hashedCanonical;
        String signature = SignUtil.toHex(SignUtil.hmacSha256(sk, stringToSign));

        headers.put("Authorization", ALGORITHM + " Credential=" + ak
                + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
        return new SignedRequest("https://" + host + path + "?" + canonicalQuery, headers);
    }

    /**
     * 解析 Token Plan 订阅/共享包响应（合并多个接口响应；可独立测试）。
     * 每个 EquityList 中 EquityType=CREDITS 的权益生成一条 QUOTA 进度条；
     * 主金额为所有 Credits 权益剩余之和。
     */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName,
                                    List<JsonNode> roots) {
        List<CreditsEntry> entries = new ArrayList<>();
        for (JsonNode root : roots) {
            collectCredits(root, entries);
        }
        if (entries.isEmpty()) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("未查询到 Token Plan Credits 权益（可能未订阅；可到百炼控制台 Token Plan → 我的订阅确认）")
                    .build();
        }
        double totalSurplus = 0;
        double totalValue = 0;
        for (CreditsEntry e : entries) {
            totalSurplus += e.surplus;
            totalValue += e.total;
        }
        BalanceSnapshot.Builder builder = BalanceSnapshot.ok(pid, accountId, accountName)
                .label("Token Plan 剩余 Credits").currency("Credits").amount(totalSurplus);
        if (totalValue > 0) {
            builder.percent(totalSurplus / totalValue * 100.0);
        }
        int shown = 0;
        for (CreditsEntry e : entries) {
            if (shown >= MAX_ENTRIES) {
                break;
            }
            Double pct = e.total > 0 ? e.surplus / e.total * 100.0 : null;
            builder.metricWithDetails(ProviderCapability.QUOTA,
                    e.label, e.surplus, "Credits", pct,
                    "总额 " + trim(e.total) + " Credits",
                    e.details);
            shown++;
        }
        return builder.build();
    }

    /** 从单个 OpenAPI 响应收集 CREDITS 权益。 */
    private static void collectCredits(JsonNode root, List<CreditsEntry> out) {
        JsonNode data = root == null ? null : root.get("Data");
        JsonNode items = data == null ? null : data.get("Items");
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String instanceCode = JsonSupport.text(item, "InstanceCode");
            String seatId = JsonSupport.text(item, "SeatId");
            String specType = JsonSupport.text(item, "SpecType");
            String status = JsonSupport.text(item, "Status");
            String accountName = JsonSupport.text(item, "AccountName");
            Long endTime = JsonSupport.longNumber(item, "EndTime");
            JsonNode equityList = item.get("EquityList");
            if (equityList == null || !equityList.isArray()) {
                continue;
            }
            for (JsonNode eq : equityList) {
                if (eq == null || !eq.isObject()) {
                    continue;
                }
                String equityType = JsonSupport.text(eq, "EquityType");
                if (equityType == null || !equityType.equalsIgnoreCase("CREDITS")) {
                    continue;
                }
                Double total = JsonSupport.number(eq, "CycleTotalValue");
                Double surplus = JsonSupport.number(eq, "CycleSurplusValue");
                if (total == null || surplus == null) {
                    continue;
                }
                Long start = JsonSupport.longNumber(eq, "CycleStartTime");
                Long end = JsonSupport.longNumber(eq, "CycleEndTime");
                List<BalanceSnapshot.DetailLine> details = new ArrayList<>();
                if (start != null) {
                    details.add(new BalanceSnapshot.DetailLine("周期开始", formatTime(start)));
                }
                if (end != null) {
                    details.add(new BalanceSnapshot.DetailLine("周期结束", formatTime(end)));
                }
                if (status != null) {
                    details.add(new BalanceSnapshot.DetailLine("状态", translateStatus(status)));
                }
                if (specType != null) {
                    details.add(new BalanceSnapshot.DetailLine("席位类型", translateSpecType(specType)));
                }
                if (accountName != null) {
                    details.add(new BalanceSnapshot.DetailLine("成员", accountName));
                }
                if (endTime != null) {
                    details.add(new BalanceSnapshot.DetailLine("到期时间", formatTime(endTime)));
                }
                out.add(new CreditsEntry(buildLabel(seatId, specType, instanceCode),
                        surplus, total, details));
            }
        }
    }

    /** 席位/共享包条目标签：保证多条目 key 唯一且用户可读。 */
    private static String buildLabel(String seatId, String specType, String instanceCode) {
        if (seatId != null) {
            String shortSeat = seatId.length() > 12 ? seatId.substring(0, 12) : seatId;
            return "席位 " + (specType == null ? "" : translateSpecType(specType) + " ") + shortSeat;
        }
        if (instanceCode != null) {
            String shortCode = instanceCode.length() > 12 ? instanceCode.substring(0, 12) : instanceCode;
            return "共享包 " + shortCode;
        }
        return "Token Plan 权益";
    }

    private static String translateStatus(String status) {
        return switch (status) {
            case "CREATING" -> "创建中";
            case "NORMAL" -> "有效";
            case "LIMIT" -> "欠费受限";
            case "RELEASE" -> "到期释放";
            case "STOP" -> "到期停机";
            case "REFUNDED" -> "已退款";
            default -> status;
        };
    }

    private static String translateSpecType(String specType) {
        return switch (specType) {
            case "standard" -> "标准";
            case "pro" -> "高级";
            case "max" -> "尊享";
            default -> specType;
        };
    }

    /** 兼容毫秒（>1e12）与秒两种官方时间戳。 */
    private static String formatTime(long ts) {
        long ms = ts > MS_THRESHOLD ? ts : ts * 1000L;
        return DISPLAY_TIME.format(Instant.ofEpochMilli(ms));
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    /** 单条 Credits 权益展示数据。 */
    private static final class CreditsEntry {
        final String label;
        final double surplus;
        final double total;
        final List<BalanceSnapshot.DetailLine> details;

        CreditsEntry(String label, double surplus, double total,
                     List<BalanceSnapshot.DetailLine> details) {
            this.label = label;
            this.surplus = surplus;
            this.total = total;
            this.details = details;
        }
    }

    private static String hostOf(String base) {
        String s = base;
        if (s.startsWith("https://")) {
            s = s.substring(8);
        } else if (s.startsWith("http://")) {
            s = s.substring(7);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
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

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
