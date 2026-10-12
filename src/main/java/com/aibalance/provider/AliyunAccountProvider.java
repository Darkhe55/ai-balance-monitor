package com.aibalance.provider;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.CallBack;
import com.aliyuncs.http.FormatType;
import com.aliyuncs.http.HttpClientConfig;
import com.aliyuncs.http.HttpRequest;
import com.aliyuncs.http.HttpResponse;
import com.aliyuncs.http.IHttpClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 阿里云百炼·账户余额与账单：经阿里云官方 SDK（BssOpenApi 2017-12-14）调用
 * QueryAccountBalance + QueryAccountBill 并合并展示。中国站与国际站拆成两个独立入口：
 * <ul>
 *   <li>{@link ProviderId#BAILIAN_ACCOUNT}（中国站）→ https://business.aliyuncs.com</li>
 *   <li>{@link ProviderId#BAILIAN_ACCOUNT_INTL}（国际站）→ https://business.ap-southeast-1.aliyuncs.com</li>
 * </ul>
 * <p>
 * 配置说明（SDK 风格，非 URL）：
 * <ul>
 *   <li>API Key 字段 = 阿里云 AccessKey ID（LTAI…）</li>
 *   <li>Secret 字段 = 阿里云 AccessKey Secret（创建时仅显示一次）</li>
 *   <li>Base URL 字段 = 官方 endpoint（按入口预填，可改为自定义 / 代理地址）</li>
 * </ul>
 * 兼容旧配置：该字段早期语义是「Region 地域」，因此填非 URL 值（如 cn-beijing / ap-southeast-1）
 * 仍按 cn- 前缀映射到中国站 / 国际站端点，旧账户不会因升级而失效。
 * 签名与请求构造完全由官方 SDK 完成；实际网络发送通过 {@link HttpClientConfig#setCustomClientClassName}
 * 注入自定义 {@link HttpBridge}，桥接到本工具统一的 HttpSupport（Conscrypt TLS + 系统代理/直连开关），
 * 保证与其它厂商一致的网络行为与 TLS 兼容性。
 */
public final class AliyunAccountProvider implements BalanceProvider {

    private static final String PRODUCT = "BssOpenApi";
    /** 中国站 BssOpenApi 公网 endpoint（官方「服务接入点」）。 */
    private static final String CN_ENDPOINT = "https://business.aliyuncs.com";
    /**
     * 国际站 BssOpenApi 公网 endpoint。官方文档中海外地域（新加坡 / 东京 / 首尔 / 美国 / 欧洲等）
     * 统一使用 business.ap-southeast-1.aliyuncs.com；business-intl.aliyuncs.com 只存在于
     * VPC 接入地址（business-intl.vpc-proxy.aliyuncs.com），公网无法解析。
     */
    private static final String INTL_ENDPOINT = "https://business.ap-southeast-1.aliyuncs.com";
    private static final String CN_REGION = "cn-hangzhou";
    private static final String INTL_REGION = "ap-southeast-1";
    private static final DateTimeFormatter BILL_CYCLE = DateTimeFormatter.ofPattern("yyyy-MM");

    private final HttpSupport http;

    public AliyunAccountProvider(HttpSupport http) {
        this.http = http;
        HttpBridge.shared = http;
    }

    /** 该入口的官方默认 endpoint。 */
    static String defaultEndpoint(ProviderId pid) {
        return pid == ProviderId.BAILIAN_ACCOUNT_INTL ? INTL_ENDPOINT : CN_ENDPOINT;
    }

    /** 该入口 SDK profile 使用的地域；endpoint 由 {@link #resolveEndpoint} 显式注册，地域仅作标识。 */
    static String defaultRegion(ProviderId pid) {
        return pid == ProviderId.BAILIAN_ACCOUNT_INTL ? INTL_REGION : CN_REGION;
    }

    /**
     * Base URL 字段解析：留空 → 该入口官方默认 endpoint；填完整 URL → 归一化后原样使用
     * （支持自定义 / 代理地址）；填地域名（旧配置遗留语义）→ cn- 前缀走中国站、其余走国际站端点。
     */
    public static String resolveEndpoint(String baseUrlOrRegion, ProviderId pid) {
        String value = baseUrlOrRegion == null ? "" : baseUrlOrRegion.strip();
        if (value.isEmpty()) {
            return defaultEndpoint(pid);
        }
        if (value.contains("://")) {
            return normalizeUrl(value);
        }
        return value.startsWith("cn-") ? CN_ENDPOINT : INTL_ENDPOINT;
    }

    /** SDK profile 的 region：URL / 留空 → 入口默认地域；地域名（旧配置）→ 原值。 */
    static String resolveRegion(String baseUrlOrRegion, ProviderId pid) {
        String value = baseUrlOrRegion == null ? "" : baseUrlOrRegion.strip();
        if (value.isEmpty() || value.contains("://")) {
            return defaultRegion(pid);
        }
        return value;
    }

    /** 旧配置迁移辅助：该 Base URL / 地域是否指向国际站（含已废弃的 business-intl 域名）。 */
    public static boolean looksInternational(String baseUrlOrRegion) {
        String value = baseUrlOrRegion == null ? "" : baseUrlOrRegion.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        if (value.contains("://")) {
            return value.contains("-intl") || value.contains("ap-southeast");
        }
        return !value.startsWith("cn-");
    }

    /** 兼容旧配置：Base URL 以 URL 形式填写的归一化（去尾斜杠）。 */
    static String normalizeUrl(String url) {
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? CN_ENDPOINT : s;
    }

    @Override
    public BalanceSnapshot check(AccountConfig config) {
        ProviderId pid = config.getProviderId();
        if (!config.hasValidKey()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKey ID").build();
        }
        if (!config.hasValidSecret()) {
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                    BalanceSnapshot.Status.NOT_CONFIGURED).message("尚未填写 AccessKey Secret").build();
        }
        try {
            String ak = config.getApiKey().strip();
            String sk = config.getSecret().strip();
            String baseUrlOrRegion = config.getBaseUrl();
            String region = resolveRegion(baseUrlOrRegion, pid);
            String endpoint = resolveEndpoint(baseUrlOrRegion, pid);

            // 官方 SDK：profile 注册 AccessKey 凭证 + Region，并按 Service=BssOpenApi 显式注册 endpoint。
            // 注意：addEndpoint 只接受纯域名；传带 https:// 的完整 URL 会让 SDK 把 scheme 当 host，
            // 拼出 http://https://… 的畸形地址而连接失败（历史 bug）。
            DefaultProfile profile = DefaultProfile.getProfile(region, ak, sk);
            DefaultProfile.addEndpoint(region, PRODUCT, endpointHost(endpoint));
            // 注入自定义 HTTP 客户端：SDK 签名后经 HttpBridge 走本工具统一网络通道
            HttpClientConfig clientCfg = HttpClientConfig.getDefault();
            clientCfg.setCustomClientClassName(HttpBridge.class.getName());
            profile.setHttpClientConfig(clientCfg);
            DefaultAcsClient client = new DefaultAcsClient(profile);

            BalanceSnapshot.Builder b = BalanceSnapshot.ok(pid, config.getId(), config.getName());

            // 1) 账户余额 QueryAccountBalance（显式 HTTPS：SDK 注册 endpoint 的默认协议是 HTTP）
            com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBalanceRequest balReq =
                    new com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBalanceRequest();
            balReq.setSysProtocol(ProtocolType.HTTPS);
            HttpResponse balResp = client.doAction(balReq);
            HttpSupport.logDiagnostic("aliyun-account action=QueryAccountBalance region=" + region
                    + " status=" + balResp.getStatus() + " body=" + truncate(bodyOf(balResp)));
            if (balResp.getStatus() == 401 || balResp.getStatus() == 403) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("AccessKey 无效或无余额查询权限（RAM 需 AliyunBSSReadOnlyAccess）").build();
            }
            if (balResp.getStatus() != 200) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("HTTP " + balResp.getStatus() + " "
                                + DeepSeekProvider.firstError(bodyOf(balResp))).build();
            }
            JsonNode balJson = bodyAsJson(balResp);
            if (balJson == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR).message("余额响应格式异常").build();
            }
            BalanceSnapshot.Builder afterBalance = fromBalanceJson(b, balJson);
            if (afterBalance == null) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.ERROR)
                        .message("阿里云错误: " + firstErrorOf(balJson)).build();
            }

            // 2) 当月账单 QueryAccountBill（账单接口失败不阻断余额展示，附加为明细）
            try {
                com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBillRequest billReq =
                        new com.aliyuncs.bssopenapi.model.v20171214.QueryAccountBillRequest();
                String cycle = LocalDate.now().format(BILL_CYCLE);
                billReq.setBillingCycle(cycle);
                // 官方要求 MONTHLY / DAILY（传 MONTH 会返回 InvalidParameter，账单明细会静默丢失）
                billReq.setGranularity("MONTHLY");
                billReq.setSysProtocol(ProtocolType.HTTPS);
                HttpResponse billResp = client.doAction(billReq);
                HttpSupport.logDiagnostic("aliyun-account action=QueryAccountBill billingCycle=" + cycle
                        + " status=" + billResp.getStatus() + " body=" + truncate(bodyOf(billResp)));
                if (billResp.getStatus() == 200) {
                    JsonNode billJson = bodyAsJson(billResp);
                    if (billJson != null) {
                        fromBillJson(afterBalance, billJson);
                    }
                }
            } catch (Exception e) {
                HttpSupport.logDiagnostic("aliyun-account action=QueryAccountBill failed=" + e);
            }

            return afterBalance.build();
        } catch (com.aliyuncs.exceptions.ClientException e) {
            // 失败也写诊断日志（脱敏），便于排查 AK 无效 / 签名 / 端点等问题
            HttpSupport.logDiagnostic("aliyun-account failed errCode=" + e.getErrCode()
                    + " errMsg=" + sanitize(e.getErrMsg()));
            // ServerException 是 ClientException 的子类，统一在此处理
            if (e instanceof com.aliyuncs.exceptions.ServerException se) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                                BalanceSnapshot.Status.ERROR)
                        .message("阿里云服务端错误: " + se.getErrCode() + " " + se.getErrMsg()).build();
            }
            if (e.getErrCode() != null
                    && (e.getErrCode().contains("InvalidAccessKey")
                    || e.getErrCode().contains("Forbidden")
                    || e.getErrCode().contains("NoPermission"))) {
                return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                        BalanceSnapshot.Status.UNAUTHORIZED)
                        .message("AccessKey 无效或无权限（RAM 需 AliyunBSSReadOnlyAccess）："
                                + e.getErrCode()).build();
            }
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("阿里云 SDK 调用失败: " + e.getErrCode() + " " + e.getErrMsg()).build();
        } catch (HttpSupport.HttpFailure e) {
            HttpSupport.logDiagnostic("aliyun-account failed network=" + e.isNetwork()
                    + " msg=" + sanitize(e.getMessage()));
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            e.isNetwork() ? BalanceSnapshot.Status.TIMEOUT : BalanceSnapshot.Status.ERROR)
                    .message(e.getMessage()).build();
        } catch (Exception e) {
            HttpSupport.logDiagnostic("aliyun-account failed ex=" + e.getClass().getName()
                    + " msg=" + sanitize(e.getMessage()));
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("解析失败: " + e.getClass().getSimpleName()).build();
        } catch (LinkageError e) {
            // jlink 精简运行时缺模块（如 java.naming）时 SDK 会抛 NoClassDefFoundError。
            // 它是 Error 而非 Exception，若不单独捕获会静默失败且不留任何日志。
            HttpSupport.logDiagnostic("aliyun-account failed linkage=" + e);
            return BalanceSnapshot.of(pid, config.getId(), config.getName(),
                            BalanceSnapshot.Status.ERROR)
                    .message("运行时缺少阿里云 SDK 所需模块: " + e.getMessage()
                            + "（发行版需包含 java.naming 等模块）").build();
        }
    }

    /**
     * 从 Base URL / endpoint 取出官方 SDK {@code addEndpoint} 需要的纯域名。
     * addEndpoint 只接受 host（如 business.aliyuncs.com）；若传入带 scheme 的完整 URL，
     * SDK 会把 scheme 一起当 host，拼出 {@code http://https://business.aliyuncs.com/…} 而连接失败。
     */
    static String endpointHost(String endpoint) {
        String s = endpoint == null ? "" : endpoint.strip();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    /** 诊断日志脱敏：抹掉错误消息里 URL 附带的 AccessKeyId / Signature / Token 等参数值。 */
    static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)(AccessKeyId|Signature|SecurityToken|Secret)[=:%][^&\\s,)]*", "$1=***");
    }

    /** 解析 QueryAccountBalance 响应并合并进快照；接口报错返回 null。 */
    static BalanceSnapshot.Builder fromBalanceJson(BalanceSnapshot.Builder b, JsonNode root) {
        if (root.get("Success") != null && !root.get("Success").asBoolean(false)) {
            return null;
        }
        JsonNode data = root.get("Data");
        if (data == null) {
            return null;
        }
        // 官方字段（实测结构）：可用额度 AvailableAmount（含信控）、可用现金 AvailableCashAmount、
        // 信控额度 CreditAmount（均为字符串数字）
        Double available = JsonSupport.number(data, "AvailableAmount");
        Double cash = JsonSupport.number(data, "AvailableCashAmount");
        Double creditAmount = JsonSupport.number(data, "CreditAmount");
        // 兼容旧字段名 CreditLimit（个别环境仍返回）
        if (creditAmount == null) {
            creditAmount = JsonSupport.number(data, "CreditLimit");
        }
        String currency = JsonSupport.text(data, "Currency");
        if (currency == null || currency.isBlank()) {
            currency = "CNY";
        }
        Double main = available != null ? available : cash;
        b.label("账户余额").currency(currency).amount(main);
        b.metric(ProviderCapability.BALANCE, "账户余额", main, currency, null,
                "阿里云 SDK QueryAccountBalance");
        if (cash != null) {
            b.detail("可用现金余额", String.format("%.2f %s", cash, currency));
        }
        if (creditAmount != null) {
            b.detail("信控额度", String.format("%.2f %s", creditAmount, currency));
        }
        return b;
    }

    /** 解析 QueryAccountBill 响应并合并进快照（账单为附加明细，失败静默跳过）。 */
    static void fromBillJson(BalanceSnapshot.Builder b, JsonNode root) {
        if (root.get("Success") != null && !root.get("Success").asBoolean(false)) {
            return;
        }
        JsonNode data = root.get("Data");
        if (data == null) {
            return;
        }
        double pretaxTotal = 0;
        double cashTotal = 0;
        double prepaidTotal = 0;
        java.util.Map<String, Double> byProduct = new java.util.LinkedHashMap<>();
        JsonNode items = data.get("Items");
        if (items != null) {
            JsonNode itemArr = items.get("Item");
            if (itemArr != null && itemArr.isArray()) {
                for (JsonNode item : itemArr) {
                    Double pretax = JsonSupport.number(item, "PretaxAmount");
                    Double cash = JsonSupport.number(item, "CashAmount");
                    Double prepaid = JsonSupport.number(item, "PrepaidAmount");
                    if (pretax != null) {
                        pretaxTotal += pretax;
                    }
                    if (cash != null) {
                        cashTotal += cash;
                    }
                    if (prepaid != null) {
                        prepaidTotal += prepaid;
                    }
                    String product = JsonSupport.text(item, "ProductName");
                    if (product != null && pretax != null) {
                        byProduct.merge(product, pretax, Double::sum);
                    }
                }
            }
        }
        String cycle = JsonSupport.text(data, "BillingCycle");
        b.metric(ProviderCapability.COST, "当月费用（原价）", pretaxTotal, "CNY", null,
                cycle == null ? "阿里云 QueryAccountBill" : "阿里云账单 " + cycle);
        if (cashTotal > 0) {
            b.detail("账单现金支付", String.format("%.2f CNY", cashTotal));
        }
        if (prepaidTotal > 0) {
            b.detail("账单预付/代金抵扣", String.format("%.2f CNY", prepaidTotal));
        }
        byProduct.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(8)
                .forEach(e -> b.detail("产品 " + e.getKey(), String.format("%.2f CNY", e.getValue())));
    }

    private static String firstErrorOf(JsonNode root) {
        String code = JsonSupport.text(root, "Code");
        String msg = JsonSupport.text(root, "Message");
        return (code == null ? "" : code) + (msg == null ? "" : " " + msg);
    }

    private static String bodyOf(HttpResponse resp) {
        if (resp == null || resp.getHttpContent() == null) {
            return "";
        }
        return new String(resp.getHttpContent(), StandardCharsets.UTF_8);
    }

    private static JsonNode bodyAsJson(HttpResponse resp) {
        String body = bodyOf(resp);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…(" + body.length() + "chars)" : body;
    }

    /**
     * 官方 SDK → 本工具统一 HTTP 通道的桥接：SDK 负责 endpoint 解析与 RPC 签名，
     * 实际发送走 HttpSupport（Conscrypt TLS + 系统代理/直连开关），保证与其它厂商一致的网络行为。
     * 经 {@link HttpClientConfig#setCustomClientClassName} 由 SDK 反射实例化（需带 HttpClientConfig 构造器）。
     */
    @SuppressWarnings("deprecation") // SDK 将 ignoreSSLCertificate/restoreSSLCertificate 标为过时，仍须实现接口
    static final class HttpBridge extends IHttpClient {

        /** 共享的网络通道实例（注册表构造 Provider 时注入；SDK 侧反射实例化无法传构造参数）。 */
        static volatile HttpSupport shared;

        HttpBridge(HttpClientConfig config) throws com.aliyuncs.exceptions.ClientException {
            super(config);
        }

        @Override
        protected void init(HttpClientConfig config) {
            // 网络由 HttpSupport 统一管理，无需额外初始化
        }

        @Override
        public HttpResponse syncInvoke(HttpRequest request) {
            HttpSupport h = shared;
            if (h == null) {
                throw new IllegalStateException("阿里云 SDK 网络通道未初始化");
            }
            try {
                String url = normalizeBridgeUrl(request.getSysUrl());
                Map<String, String> headers = new java.util.LinkedHashMap<>();
                if (request.getSysHeaders() != null) {
                    headers.putAll(request.getSysHeaders());
                }
                // SDK 的 Content-Type 存在 httpContentType 字段而不是 sysHeaders：
                // 不补齐会让 HttpSupport 按默认 application/json 发送 form 请求，网关解析不到参数
                boolean hasContentType = headers.keySet().stream()
                        .anyMatch(k -> k.equalsIgnoreCase("Content-Type"));
                if (!hasContentType) {
                    String contentType = request.getContentTypeValue(request.getHttpContentType(), "UTF-8");
                    if (contentType != null && !contentType.isBlank()) {
                        headers.put("Content-Type", contentType);
                    }
                }
                HttpSupport.Response resp;
                if (request.getSysMethod() == MethodType.GET) {
                    resp = h.get(url, headers);
                } else {
                    byte[] content = request.getHttpContent();
                    String body = content == null ? "" : new String(content, StandardCharsets.UTF_8);
                    resp = h.post(url, headers, body);
                }
                HttpResponse out = new HttpResponse(url);
                out.setStatus(resp.status());
                byte[] bytes = resp.body() == null ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
                out.setHttpContent(bytes, "UTF-8", FormatType.RAW);
                return out;
            } catch (Exception e) {
                throw new IllegalStateException("阿里云 SDK 请求失败: " + e.getMessage(), e);
            }
        }

        /**
         * SDK 请求 URL 归一化：本工具统一强制 HTTPS，而 SDK 注册 endpoint 的默认协议是 HTTP，
         * 且 endpoint 若被误设成带 scheme 的完整 URL 会出现 {@code http://https://…} 畸形地址；
         * 两种情况都在此修正，避免落到明文请求或被 HTTPS 策略直接拒绝。
         */
        static String normalizeBridgeUrl(String url) {
            if (url == null) {
                return null;
            }
            String s = url.strip();
            while (s.startsWith("http://https://")) {
                s = s.substring("http://".length());
            }
            if (s.startsWith("http://")) {
                s = "https://" + s.substring("http://".length());
            }
            return s;
        }

        @Override
        public Future<HttpResponse> asyncInvoke(HttpRequest request, CallBack callback) {
            throw new UnsupportedOperationException("本工具仅使用阿里云 SDK 同步调用");
        }

        @Override
        public void ignoreSSLCertificate() {
            // 桥接通道由 HttpSupport 统一管理 TLS
        }

        @Override
        public void restoreSSLCertificate() {
            // 桥接通道由 HttpSupport 统一管理 TLS
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        @Override
        public void close() throws java.io.IOException {
            // 共享网络通道由 HttpSupport 统一管理生命周期
        }
    }
}
