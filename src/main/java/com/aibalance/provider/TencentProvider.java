package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 腾讯混元：腾讯云账户余额（DescribeAccountBalance，TC3-HMAC-SHA256 签名）。
 * <p>
 * 接入：API Key 填 SecretId，Secret 填 SecretKey（均 DPAPI 加密存储）。
 * POST https://billing.tencentcloudapi.com/，Content-Type: application/json; charset=utf-8，
 * body 为空 JSON（"{}"，签名时对同样的 "{}" 做 SHA256）。
 * 余额单位分为（人民币），显示时已转元。
 * 子账号需 CAM 计费相关只读权限。
 */
public final class TencentProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://billing.tencentcloudapi.com";
    /** Credential Scope 中的服务名：计费服务 billing（非 bss），须与官方一致。 */
    private static final String SERVICE = "billing";
    private static final String VERSION = "2018-07-09";
    private static final String ACTION = "DescribeAccountBalance";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final HttpSupport http;

    public TencentProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.TENCENT;
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 SecretId").build();
        }
        if (!config.hasValidSecret()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 SecretKey").build();
        }
        try {
            String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                    ? DEFAULT_BASE : config.getBaseUrl().strip().replaceAll("/+$", "");
            String secretId = config.getApiKey().strip();
            String secretKey = config.getSecret().strip();
            SignedRequest req = buildSignedRequest(secretId, secretKey, base);
            HttpSupport.Response resp = http.post(req.url, req.headers, req.body);
            HttpSupport.logDiagnostic("tencent action=DescribeAccountBalance"
                    + " status=" + resp.status() + " body=" + truncate(resp.body()));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("SecretId/SecretKey 无效或无余额查询权限（建议 CAM 子用户仅授权计费只读 BillingRead）").build();
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

    /** 签名请求（URL/头/体，可独立测试）。 */
    static final class SignedRequest {
        final String url;
        final Map<String, String> headers;
        final String body;

        SignedRequest(String url, Map<String, String> headers, String body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /** 构造带腾讯云 TC3-HMAC-SHA256 签名的 POST 请求（可独立测试）。 */
    static SignedRequest buildSignedRequest(String secretId, String secretKey, String base) {
        return buildSignedRequestAt(secretId, secretKey, base, Instant.now().getEpochSecond());
    }

    /**
     * 指定时间戳构造签名请求（供测试注入固定时间独立重算签名；生产走当前时间）。
     * 严格遵循官方 TC3 规范：
     * <ul>
     *   <li>Credential Scope：{date}/billing/tc3_request（服务名 billing）</li>
     *   <li>CanonicalURI = "/"，CanonicalQueryString = 空</li>
     *   <li>body 固定 "{}"，payload 哈希对同样的 "{}" 计算</li>
     *   <li>SignedHeaders = content-type;host，两头的值（application/json; charset=utf-8、
     *       host）与实际发送完全一致</li>
     *   <li>时间戳与 UTC 日期同源（同一 timestamp 派生）</li>
     * </ul>
     */
    static SignedRequest buildSignedRequestAt(String secretId, String secretKey, String base, long timestamp) {
        String host = URI.create(base).getHost();
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).format(DATE);
        String body = "{}";

        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + host + "\n";
        String signedHeaders = "content-type;host";
        String payloadHash = SignUtil.sha256Hex(body);
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n"
                + SignUtil.sha256Hex(canonicalRequest);

        byte[] kDate = SignUtil.hmacSha256("TC3" + secretKey, date);
        byte[] kService = SignUtil.hmacSha256(kDate, SERVICE);
        byte[] kSigning = SignUtil.hmacSha256(kService, "tc3_request");
        String signature = SignUtil.toHex(SignUtil.hmacSha256(kSigning, stringToSign));

        String authorization = ALGORITHM + " Credential=" + secretId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("X-TC-Action", ACTION);
        headers.put("X-TC-Timestamp", String.valueOf(timestamp));
        headers.put("X-TC-Version", VERSION);
        headers.put("Content-Type", "application/json; charset=utf-8");
        return new SignedRequest(base + "/", headers, body);
    }

    /** 解析 DescribeAccountBalance 响应（可独立测试）；所有金额单位分（人民币）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode err = root.get("Error");
        if (err == null) {
            JsonNode respErr = root.get("Response");
            if (respErr != null && respErr.isObject()) {
                err = respErr.get("Error");
            }
        }
        if (err != null && err.isObject()) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("腾讯云错误: " + JsonSupport.text(err, "Code") + " "
                            + JsonSupport.text(err, "Message")).build();
        }
        JsonNode response = root.get("Response");
        if (response == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 Response 字段").build();
        }
        // Balance = 当前真实可用余额（与 RealBalance 相同），单位分
        Long balanceFen = JsonSupport.longNumber(response, "Balance");
        if (balanceFen == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 Balance 字段").build();
        }
        double balanceYuan = balanceFen / 100.0;
        Double cash = centsToYuan(JsonSupport.number(response, "CashAccountBalance"));
        Double creditAmount = centsToYuan(JsonSupport.number(response, "CreditAmount"));
        Double freeze = centsToYuan(JsonSupport.number(response, "FreezeAmount"));
        Double owe = centsToYuan(JsonSupport.number(response, "OweAmount"));
        Double income = centsToYuan(JsonSupport.number(response, "IncomeIntoAccountBalance"));
        Double present = centsToYuan(JsonSupport.number(response, "PresentAccountBalance"));

        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);
        b.label("可用余额").currency("CNY").amount(balanceYuan);
        b.metric(ProviderCapability.BALANCE, "可用余额", balanceYuan, "CNY", null,
                "腾讯云 DescribeAccountBalance");
        if (cash != null) {
            b.detail("现金余额", String.format("%.2f CNY", cash));
        }
        if (creditAmount != null) {
            b.detail("信用额度", String.format("%.2f CNY", creditAmount));
        }
        if (freeze != null) {
            b.detail("冻结金额", String.format("%.2f CNY", freeze));
        }
        if (owe != null) {
            b.detail("欠费金额", String.format("%.2f CNY", owe));
        }
        if (income != null) {
            b.detail("收益转入余额", String.format("%.2f CNY", income));
        }
        if (present != null) {
            b.detail("赠送余额", String.format("%.2f CNY", present));
        }
        return b.build();
    }

    /** 分 → 元；null 安全。 */
    private static Double centsToYuan(Double cents) {
        return cents == null ? null : cents / 100.0;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
