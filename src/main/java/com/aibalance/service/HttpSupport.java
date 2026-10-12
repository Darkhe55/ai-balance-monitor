package com.aibalance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 共享的 HTTP 基础设施：连接池、超时、统一请求/异常处理。
 * 实现：OkHttp + Conscrypt（BoringSSL 移植）。
 *
 * <p>安全与兼容加固：
 * - 仅允许 HTTPS（拒绝明文 http）；
 * - TLS 指纹使用 Conscrypt/BoringSSL（接近浏览器），绕开按 ClientHello 指纹拦截
 *   的网络设备——JDK 默认 OpenSSL 指纹常被此类设备以 handshake_failure 拒绝；
 * - 强制 HTTP/1.1（规避 HTTP/2 ALPN 与代理/中间设备的兼容性失败）；
 * - 握手失败自动回退「仅 TLSv1.2」通道重试一次（GET 幂等）；
 * - 支持"跟随系统代理 / 直连"两种网络模式，运行时可切换。
 */
public final class HttpSupport {

    /** 网络模式："system" 跟随系统代理设置，"direct" 强制直连。 */
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_DIRECT = "direct";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /**
     * Conscrypt 可用性。部分网络设备按 TLS ClientHello 指纹拦截 JDK 默认 OpenSSL
     * 实现（表现为服务端 handshake_failure），而放行浏览器/SChannel；Conscrypt
     * 指纹接近浏览器，可绕过此类拦截。不可用时静默回退 JDK TLS。
     */
    private static final boolean CONSCRYPT_OK = initConscrypt();

    private static boolean initConscrypt() {
        try {
            java.security.Security.addProvider(new org.conscrypt.OpenSSLProvider());
            return true;
        } catch (Throwable t) {
            // native 库缺失/加载失败等：回退 JDK TLS，不影响应用启动
            return false;
        }
    }

    /** 仅 TLSv1.2 的 ConnectionSpec（握手失败回退用）。 */
    private static final ConnectionSpec TLS12_SPEC = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build();

    private final OkHttpClient systemClient;
    private final OkHttpClient directClient;
    private volatile OkHttpClient tls12SystemClient;
    private volatile OkHttpClient tls12DirectClient;
    private final AtomicReference<String> proxyMode = new AtomicReference<>(MODE_SYSTEM);
    private final ObjectMapper mapper;

    public HttpSupport(String proxyMode) {
        this.systemClient = buildClient(ProxySelector.getDefault(), null);
        this.directClient = buildClient(ProxySelector.of(null), null);
        this.proxyMode.set(MODE_SYSTEM.equals(proxyMode) ? MODE_SYSTEM : MODE_DIRECT);
        this.mapper = new ObjectMapper();
        logDiag("startup", "n/a",
                CONSCRYPT_OK ? "conscrypt-ok (TLS 指纹=Conscrypt/BoringSSL)"
                        : "conscrypt-unavailable (回退 JDK TLS)",
                null);
    }

    public HttpSupport() {
        this(MODE_SYSTEM);
    }

    /** 切换网络模式（"system" 跟随系统代理 / "direct" 直连），立即对后续请求生效。 */
    public void setProxyMode(String mode) {
        proxyMode.set(MODE_SYSTEM.equals(mode) ? MODE_SYSTEM : MODE_DIRECT);
    }

    public String proxyMode() {
        return proxyMode.get();
    }

    private OkHttpClient buildClient(ProxySelector proxy, ConnectionSpec spec) {
        javax.net.ssl.TrustManager[] tms = buildTrustManagers();
        javax.net.ssl.SSLContext ctx = createSslContext(tms);
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .readTimeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .followRedirects(true)
                .followSslRedirects(true)
                // HTTP/1.1：避免 h2 ALPN 协商在代理/中间设备上握手失败
                .protocols(List.of(Protocol.HTTP_1_1))
                .connectionSpecs(spec == null
                        ? List.of(TLS12_SPEC)
                        : List.of(spec))
                .proxySelector(proxy);
        if (ctx != null) {
            javax.net.ssl.X509TrustManager tm = firstX509(tms);
            if (tm != null) {
                b.sslSocketFactory(ctx.getSocketFactory(), tm);
            }
        }
        return b.build();
    }

    /**
     * 优先使用 Conscrypt 的 TrustManagerImpl：Conscrypt 在 TLS1.3 下传给验证器的
     * authType 是 "GENERIC"，JDK 默认 X509TrustManagerImpl 无法识别并抛
     * CertificateException: Unknown authType: GENERIC（表现为握手失败）。
     * Conscrypt 的 TrustManagerImpl 接受该 authType；取不到时回退 JDK 默认实现。
     */
    private static javax.net.ssl.TrustManager[] buildTrustManagers() {
        if (CONSCRYPT_OK) {
            try {
                // 显式加载系统信任库（cacerts），Conscrypt 的 TrustManagerImpl
                // 接受 TLS1.3 下协商出的 "GENERIC" authType（JDK 默认实现会拒绝）
                java.security.KeyStore ks =
                        java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                java.io.File cacerts = new java.io.File(
                        System.getProperty("java.home"), "lib/security/cacerts");
                try (java.io.FileInputStream in = new java.io.FileInputStream(cacerts)) {
                    ks.load(in, null);
                }
                return new javax.net.ssl.TrustManager[]{new org.conscrypt.TrustManagerImpl(ks)};
            } catch (Exception e) {
                try {
                    return new javax.net.ssl.TrustManager[]{
                            org.conscrypt.Conscrypt.getDefaultX509TrustManager()};
                } catch (java.security.KeyManagementException e2) {
                    return defaultTrustManagers();
                }
            }
        }
        return defaultTrustManagers();
    }

    private static javax.net.ssl.TrustManager[] defaultTrustManagers() {
        try {
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null); // 系统 cacerts
            return tmf.getTrustManagers();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static javax.net.ssl.X509TrustManager firstX509(javax.net.ssl.TrustManager[] tms) {
        if (tms != null) {
            for (javax.net.ssl.TrustManager tm : tms) {
                if (tm instanceof javax.net.ssl.X509TrustManager x) {
                    return x;
                }
            }
        }
        return null;
    }

    /** 仅 TLSv1.2 的通道（懒创建）：部分网络中间设备拦截 TLS1.3 ClientHello 时回退使用。 */
    private OkHttpClient tls12Client(boolean direct) {
        if (direct) {
            OkHttpClient c = tls12DirectClient;
            if (c == null) {
                synchronized (this) {
                    if (tls12DirectClient == null) {
                        tls12DirectClient = buildClient(ProxySelector.of(null), TLS12_SPEC);
                    }
                    c = tls12DirectClient;
                }
            }
            return c;
        }
        OkHttpClient c = tls12SystemClient;
        if (c == null) {
            synchronized (this) {
                if (tls12SystemClient == null) {
                    tls12SystemClient = buildClient(ProxySelector.getDefault(), TLS12_SPEC);
                }
                c = tls12SystemClient;
            }
        }
        return c;
    }

    private static javax.net.ssl.SSLContext createSslContext(javax.net.ssl.TrustManager[] tms) {
        if (CONSCRYPT_OK) {
            try {
                // Conscrypt：BoringSSL 指纹，接近浏览器；需显式 init 才可用
                javax.net.ssl.SSLContext ctx =
                        javax.net.ssl.SSLContext.getInstance("TLS", "Conscrypt");
                ctx.init(null, tms, new SecureRandom());
                return ctx;
            } catch (Exception e) {
                // 回退 JDK TLS
            }
        }
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tms, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("TLS 初始化失败: " + e.getMessage(), e);
        }
    }

    private static javax.net.ssl.X509TrustManager defaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null); // 系统 cacerts
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager x) {
                    return x;
                }
            }
        } catch (Exception ignore) {
            // 取不到信任管理器时由 OkHttp 使用默认
        }
        return null;
    }

    /**
     * 发起 GET 请求并返回完整响应（状态码 + 头 + 体）。
     * 安全约束：仅接受 HTTPS 地址，拒绝明文 HTTP 传输，避免 API Key 在链路上被窃听。
     */
    public Response get(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("https://")) {
            throw new HttpFailure("仅支持 HTTPS 地址（已拒绝明文传输）", false);
        }
        Request.Builder rb = new Request.Builder().url(url).get();
        headers.forEach(rb::header);
        boolean direct = MODE_DIRECT.equals(proxyMode.get());
        String mode = direct ? "direct" : "system";
        OkHttpClient client = direct ? directClient : systemClient;
        try {
            return execute(client, rb);
        } catch (IOException e) {
            if (isHandshake(e)) {
                logDiag(mode, url, "tls-handshake", e);
                // 自动改用仅 TLSv1.2 的通道重试一次（GET 幂等，安全）
                OkHttpClient tls12 = tls12Client(direct);
                try {
                    return execute(tls12, rb);
                } catch (IOException e2) {
                    logDiag(mode, url, "tls12-retry", e2);
                    if (isHandshake(e2)) {
                        throw new HttpFailure("TLS 握手失败（handshake_failure），可尝试在顶部将网络模式切换为「直连」或检查代理",
                                true);
                    }
                    throw new HttpFailure("请求失败: " + e2.getMessage(), true);
                }
            }
            logDiag(mode, url, "request", e);
            throw new HttpFailure("请求失败: " + e.getMessage(), true);
        } catch (Exception e) {
            logDiag(mode, url, "request", e);
            throw new HttpFailure("请求异常: " + e.getMessage(), false);
        }
    }

    /**
     * 发起 POST 请求（JSON 体）并返回完整响应。
     * 安全约束：仅接受 HTTPS 地址；签名类请求（腾讯/百度云）不做 TLS12 自动重试，
     * 避免重放导致签名过期。
     */
    public Response post(String url, Map<String, String> headers, String body) {
        if (url == null || !url.startsWith("https://")) {
            throw new HttpFailure("仅支持 HTTPS 地址（已拒绝明文传输）", false);
        }
        String ct = headers.entrySet().stream()
                .filter(e -> "content-type".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("application/json; charset=utf-8");
        okhttp3.RequestBody rb = okhttp3.RequestBody.create(
                body == null ? "" : body, okhttp3.MediaType.get(ct));
        Request.Builder rbq = new Request.Builder().url(url).post(rb);
        headers.forEach(rbq::header);
        boolean direct = MODE_DIRECT.equals(proxyMode.get());
        String mode = direct ? "direct" : "system";
        OkHttpClient client = direct ? directClient : systemClient;
        try {
            return execute(client, rbq);
        } catch (IOException e) {
            logDiag(mode, url, "request", e);
            throw new HttpFailure("请求失败: " + e.getMessage(), true);
        } catch (Exception e) {
            logDiag(mode, url, "request", e);
            throw new HttpFailure("请求异常: " + e.getMessage(), false);
        }
    }

    private static boolean isHandshake(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    private Response execute(OkHttpClient client, Request.Builder rb) throws IOException {
        try (okhttp3.Response resp = client.newCall(rb.build()).execute()) {
            ResponseBody body = resp.body();
            long len = body == null ? -1 : body.contentLength();
            if (len > MAX_RESPONSE_BYTES) {
                throw new IOException("响应体过大（" + len + " 字节）");
            }
            String text = body == null ? "" : body.string();
            return new Response(resp.code(), lowerKeys(resp.headers().toMultimap()), text);
        }
    }

    private static Map<String, List<String>> lowerKeys(Map<String, List<String>> in) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        in.forEach((k, v) -> out.put(k == null ? "" : k.toLowerCase(), v));
        return out;
    }

    /** 响应包裹：状态码、响应头（小写键、多值）、响应体。 */
    public static final class Response {
        private final int status;
        private final Map<String, List<String>> headers;
        private final String body;

        public Response(int status, Map<String, List<String>> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public String body() {
            return body;
        }

        public JsonNode bodyAsJson() {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                return null;
            }
        }

        public String firstHeader(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** 统一封装的 HTTP 失败。 */
    public static final class HttpFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean network;

        public HttpFailure(String message, boolean network) {
            super(message);
            this.network = network;
        }

        public boolean isNetwork() {
            return network;
        }
    }

    /**
     * 公共诊断日志（%USERPROFILE%\.ai-balance-monitor\diagnostics.log）。
     * 供 Provider 记录查询响应（URL/HTTP 状态/响应体截断），严禁传入 API Key。
     */
    public static void logDiagnostic(String line) {
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getProperty("user.home"),
                            ".ai-balance-monitor", "diagnostics.log"),
                    java.time.LocalDateTime.now() + " [query] " + line + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // 诊断日志失败不影响主流程
        }
    }

    /** 失败诊断日志（%USERPROFILE%\.ai-balance-monitor\diagnostics.log），不记录 API Key 与完整 URL。 */
    private static void logDiag(String mode, String url, String phase, Throwable t) {        try {
            String host;
            try {
                host = URI.create(url).getHost();
            } catch (Exception ex) {
                host = "unknown";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(java.time.LocalDateTime.now()).append(" [").append(mode).append("] ")
                    .append(phase).append(" host=").append(host).append(" ex=")
                    .append(t == null ? "-" : t.getClass().getName() + ": " + t.getMessage()).append('\n');
            if (t != null) {
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) {
                    sb.append("    at ").append(st[i]).append('\n');
                }
            }
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getProperty("user.home"),
                            ".ai-balance-monitor", "diagnostics.log"),
                    sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // 诊断日志失败不影响主流程
        }
    }
}
