package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 百度千帆：百度智能云账户余额（POST /v1/finance/cash/balance，BCE bce-auth-v1 签名）。
 * <p>
 * 接入：API Key 填 Access Key ID，Secret 填 Secret Access Key（均 DPAPI 加密存储）。
 * Host 默认 billing.baidubce.com；签名有效期 1800 秒；返回现金余额（元）。
 * 无真实 AK/SK 无法真机验证，本实现按官方 BCE 签名协议与响应字段解析。
 */
public final class BaiduProvider implements BalanceProvider {

    private static final String DEFAULT_BASE = "https://billing.baidubce.com";
    private static final String URI_PATH = "/v1/finance/cash/balance";
    private static final DateTimeFormatter BCE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final HttpSupport http;

    public BaiduProvider(HttpSupport http) {
        this.http = http;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = ProviderId.BAIDU;
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 Access Key ID").build();
        }
        if (!config.hasValidSecret()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 Secret Access Key").build();
        }
        try {
            String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                    ? DEFAULT_BASE : config.getBaseUrl().strip().replaceAll("/+$", "");
            SignedRequest req = buildSignedRequest(config.getApiKey().strip(),
                    config.getSecret().strip(), base);
            HttpSupport.Response resp = http.post(req.url, req.headers, req.body);
            HttpSupport.logDiagnostic("baidu action=cash/balance"
                    + " status=" + resp.status() + " body=" + truncate(resp.body()));
            if (resp.status() == 401 || resp.status() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("AccessKey/SecretKey 无效或无余额查询权限").build();
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

    /** 构造带百度 BCE bce-auth-v1 签名的 POST 请求（可独立测试）。 */
    static SignedRequest buildSignedRequest(String ak, String sk, String base) {
        String host = URI.create(base).getHost();
        String xBceDate = Instant.now().atZone(ZoneOffset.UTC).format(BCE_DATE);
        String body = "{}";

        String authStringPrefix = "bce-auth-v1/" + ak + "/" + xBceDate + "/1800";
        byte[] signingKey = SignUtil.hmacSha256(sk, authStringPrefix);
        String signingKeyHex = SignUtil.toHex(signingKey);

        // CanonicalURI：路径除 "/" 外逐段 URI 编码（本路径无特殊字符，原样保留）
        String canonicalUri = URI_PATH;
        // CanonicalHeaders：host 与 x-bce-date（值需 URI 编码）
        String encodedDate = urlEncode(xBceDate);
        String canonicalHeaders = "host:" + host + "\nx-bce-date:" + encodedDate;
        String canonicalRequest = "POST\n" + canonicalUri + "\n\n" + canonicalHeaders;

        byte[] signature = SignUtil.hmacSha256(signingKeyHex.getBytes(StandardCharsets.UTF_8), canonicalRequest);
        String auth = authStringPrefix + "/host;x-bce-date/" + SignUtil.toHex(signature);

        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", auth);
        headers.put("Host", host);
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("x-bce-date", xBceDate);
        return new SignedRequest(base + URI_PATH, headers, body);
    }

    /** 百度签名规范要求的 URL 编码（空格 → %20，保留字母数字及部分符号）。 */
    private static String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** 解析 cash/balance 响应（可独立测试）。 */
    static BalanceSnapshot fromJson(ProviderId pid, String accountId, String accountName, JsonNode root) {
        if (root.get("code") != null && !"200".equals(String.valueOf(root.get("code").asText()))
                && root.get("code").asInt(0) != 0) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("百度智能云错误: " + JsonSupport.text(root, "message")).build();
        }
        Double cash = JsonSupport.number(root, "cashBalance");
        if (cash == null) {
            return BalanceSnapshot.of(pid, accountId, accountName, BalanceSnapshot.Status.ERROR)
                    .message("响应缺少 cashBalance 字段").build();
        }
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, accountId, accountName);
        b.label("现金余额").currency("CNY").amount(cash);
        b.metric(ProviderCapability.BALANCE, "现金余额", cash, "CNY", null,
                "百度智能云 cash/balance");
        return b.build();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }
}
