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
import java.util.TreeMap;

/**
 * 火山方舟：火山引擎费用中心账户余额（QueryBalanceAcct，AK/SK 签名）。
 * <p>
 * 接入：API Key 填 AccessKey ID，Secret 填 AccessKey Secret（均 DPAPI 加密存储）。
 * 签名采用火山引擎 HMAC-SHA256 认证头（X-Date + Authorization），
 * Service=billing、Region=cn-beijing、Version=2022-01-01。
 * 无真实 AK/SK 无法真机验证，本实现按官方签名协议与响应字段解析。
 */
public final class VolcengineProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://billing.volcengineapi.com";
    private static final String SERVICE = "billing";
    private static final String REGION = "cn-beijing";
    private static final String VERSION = "2022-01-01";
    private static final String ACTION = "QueryBalanceAcct";
    private static final DateTimeFormatter X_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final HttpSupport http;

    public VolcengineProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.VOLCENGINE;
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKey ID").build();
        }
        if (!config.hasValidSecret()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKey Secret").build();
        }
        try {
            String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                    ? DEFAULT_BASE : config.getBaseUrl().strip().replaceAll("/+$", "");
            Signing s = buildSignedUrl(config.getApiKey().strip(), config.getSecret().strip(), base);
            // 严格按官方签名示例：请求头仅携带 X-Date 与 Authorization（GET 无 body，
            // SignedHeaders=host;x-date，不发送未参与签名的 content-type 等头，避免签名校验失败）
            HttpSupport.Response resp = http.get(s.url,
                    Map.of("X-Date", s.xDate, "Authorization", s.authorization));
            HttpSupport.logDiagnostic("volcengine action=QueryBalanceAcct"
                    + " status=" + resp.status() + " body=" + truncate(resp.body()));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("AccessKey 无效或无余额查询权限（需 BillingCenterReadOnlyAccess）").build();
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

    /** 签名结果（URL + 认证头，可独立测试）。 */
    static final class Signing {
        final String url;
        final String xDate;
        final String authorization;

        Signing(String url, String xDate, String authorization) {
            this.url = url;
            this.xDate = xDate;
            this.authorization = authorization;
        }
    }

    /** 构造带火山引擎 HMAC-SHA256 认证头签名的请求（可独立测试）。 */
    static Signing buildSignedUrl(String ak, String sk, String base) {
        String xDate = Instant.now().atZone(ZoneOffset.UTC).format(X_DATE);
        String shortDate = xDate.substring(0, 8);
        String host = URI.create(base).getHost();

        TreeMap<String, String> query = new TreeMap<>();
        query.put("Action", ACTION);
        query.put("Version", VERSION);
        StringBuilder canonicalQuery = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (canonicalQuery.length() > 0) {
                canonicalQuery.append("&");
            }
            canonicalQuery.append(SignUtil.percentEncode(e.getKey()))
                    .append("=").append(SignUtil.percentEncode(e.getValue()));
        }

        String bodySha = SignUtil.sha256Hex("");
        // 官方签名示例：SignedHeaders=host;x-date，CanonicalHeaders 仅含 host 与 x-date
        String headersBlock = "host:" + host + "\n"
                + "x-date:" + xDate;
        String signedHeaders = "host;x-date";
        String canonicalRequest = "GET\n/\n" + canonicalQuery + "\n" + headersBlock
                + "\n\n" + signedHeaders + "\n" + bodySha;
        String scope = shortDate + "/" + REGION + "/" + SERVICE + "/request";
        String stringToSign = "HMAC-SHA256\n" + xDate + "\n" + scope + "\n"
                + SignUtil.sha256Hex(canonicalRequest);

        byte[] kDate = SignUtil.hmacSha256(sk, shortDate);
        byte[] kRegion = SignUtil.hmacSha256(kDate, REGION);
        byte[] kService = SignUtil.hmacSha256(kRegion, SERVICE);
        byte[] kSigning = SignUtil.hmacSha256(kService, "request");
        String signature = SignUtil.toHex(SignUtil.hmacSha256(kSigning, stringToSign));

        String authorization = "HMAC-SHA256 Credential=" + ak + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signing(base + "/?" + canonicalQuery, xDate, authorization);
    }

    /** 解析 QueryBalanceAcct 响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        JsonNode meta = root.get("ResponseMetadata");
        if (meta != null && meta.get("Error") != null && meta.get("Error").isObject()) {
            JsonNode err = meta.get("Error");
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("火山引擎错误: " + JsonSupport.text(err, "Code") + " "
                            + JsonSupport.text(err, "Message")).build();
        }
        JsonNode result = root.get("Result");
        if (result == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 Result 字段").build();
        }
        Double available = JsonSupport.number(result, "AvailableBalance");
        Double cash = JsonSupport.number(result, "CashBalance");
        Double arrears = JsonSupport.number(result, "ArrearsBalance");
        Double freeze = JsonSupport.number(result, "FreezeAmount");
        Double creditLimit = JsonSupport.number(result, "CreditLimit");
        String currency = JsonSupport.text(result, "Currency");
        if (currency == null || currency.isBlank()) {
            currency = "CNY";
        }
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);
        b.label("可用余额").currency(currency).amount(available);
        b.metric(ProviderCapability.BALANCE, "可用余额", available, currency, null,
                "火山引擎 QueryBalanceAcct");
        if (cash != null) {
            b.detail("现金余额", String.format("%.2f %s", cash, currency));
        }
        if (freeze != null) {
            b.detail("冻结金额", String.format("%.2f %s", freeze, currency));
        }
        if (arrears != null) {
            b.detail("欠费金额", String.format("%.2f %s", arrears, currency));
        }
        if (creditLimit != null) {
            b.detail("信控额度", String.format("%.2f %s", creditLimit, currency));
        }
        return b.build();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
