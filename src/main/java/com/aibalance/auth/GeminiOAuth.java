package com.aibalance.auth;

import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Google OAuth 2.0 桌面应用授权助手（用于「Google Gemini（Cloud 账单）」入口）。
 * <p>
 * 流程：启动本机回环 HTTP 服务器 → 用系统浏览器打开 Google 授权页 →
 * 用户在浏览器中登录并授权 → Google 回调到 {@code http://localhost:{port}/} →
 * 工具用授权码兑换 access_token + refresh_token。
 * <p>
 * 使用说明：
 * <ul>
 *   <li>{@link #CLIENT_ID} / {@link #CLIENT_SECRET} 为占位值，建议替换为你自己注册的
 *       Google OAuth Client（Google Cloud Console → APIs &amp; Services → Credentials → 创建 OAuth Client ID，
 *       应用类型选「桌面应用」）。桌面应用通常无需 client_secret。</li>
 *   <li>也可在账户「附加参数」里填 {@code client_id=xxx; client_secret=xxx} 覆盖，无需重新编译。</li>
 * </ul>
 */
public final class GeminiOAuth {

    /** 占位 OAuth Client ID：使用前请替换为自己注册的 Google OAuth Client ID。 */
    public static final String CLIENT_ID = "YOUR_OAUTH_CLIENT_ID.apps.googleusercontent.com";
    /** 桌面应用通常没有 client_secret；如使用 Web 应用类型 Client，请在此填写。 */
    public static final String CLIENT_SECRET = "";

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    /** Google Cloud Billing 读取权限（Budgets API 需要）。 */
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-billing";
    private static final long AUTH_TIMEOUT_SECONDS = 5 * 60;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 令牌交换结果：accessToken 必填；refreshToken 首次授权时返回，刷新时可能为空。 */
    public record TokenResult(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private GeminiOAuth() {
    }

    /**
     * 完成浏览器授权并返回令牌。
     *
     * @param http         共享 HTTP 基础设施（复用代理设置）
     * @param clientId     Google OAuth Client ID
     * @param clientSecret Google OAuth Client Secret（桌面应用可为空）
     * @return 访问令牌与刷新令牌
     * @throws Exception 授权取消/失败、令牌兑换失败等
     */
    public static TokenResult authorize(HttpSupport http, String clientId, String clientSecret) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        String redirectUri = "http://localhost:" + port + "/";
        String state = randomState();
        String authUrl = AUTH_URL
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + urlEncode(state);

        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        server.createContext("/", exchange -> handleCallback(exchange, state, codeFuture));
        server.start();
        try {
            openBrowser(authUrl);
            String code = codeFuture.get(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return exchangeCode(http, code, redirectUri, clientId, clientSecret);
        } finally {
            server.stop(0);
        }
    }

    /** 用刷新令牌换取新的访问令牌（自动续期）。 */
    public static TokenResult refresh(HttpSupport http, String refreshToken,
                                      String clientId, String clientSecret) throws Exception {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&client_id=" + urlEncode(clientId)
                + clientSecretParam(clientSecret);
        return postAndParse(http, form);
    }

    private static void handleCallback(HttpExchange exchange, String state, CompletableFuture<String> future) {
        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String error = params.get("error");
            String code = params.get("code");
            String receivedState = params.get("state");
            String html;
            if (error != null) {
                String desc = params.get("error_description");
                future.completeExceptionally(new IllegalStateException(
                        "授权失败: " + error + (desc == null || desc.isBlank() ? "" : " " + desc)));
                html = "<html><body><h3>授权失败</h3><p>请关闭此窗口并在工具中重试。</p></body></html>";
            } else if (code == null || code.isBlank() || !state.equals(receivedState)) {
                future.completeExceptionally(new IllegalStateException("回调参数异常（缺少 code 或 state 不匹配）"));
                html = "<html><body><h3>授权失败</h3><p>回调参数异常，请关闭此窗口并重试。</p></body></html>";
            } else {
                future.complete(code);
                html = "<html><body><h3>授权成功</h3><p>已获取访问令牌，请关闭此窗口返回工具。</p></body></html>";
            }
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static TokenResult exchangeCode(HttpSupport http, String code, String redirectUri,
                                            String clientId, String clientSecret) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(clientId)
                + clientSecretParam(clientSecret);
        return postAndParse(http, form);
    }

    private static TokenResult postAndParse(HttpSupport http, String form) throws Exception {
        HttpSupport.Response resp = http.post(TOKEN_URL,
                Map.of("Content-Type", "application/x-www-form-urlencoded",
                        "Accept", "application/json"),
                form);
        return parseTokenResponse(resp.body(), resp.status());
    }

    /** 解析令牌接口响应（可独立单元测试）。 */
    static TokenResult parseTokenResponse(String body, int status) throws Exception {
        JsonNode root = (body == null || body.isBlank()) ? null : MAPPER.readTree(body);
        if (root == null) {
            throw new IllegalStateException("令牌接口返回空响应（HTTP " + status + "）");
        }
        if (status != 200) {
            String error = text(root, "error");
            String desc = text(root, "error_description");
            throw new IllegalStateException("令牌接口错误（HTTP " + status + "）: " + error
                    + (desc == null || desc.isBlank() ? "" : " " + desc));
        }
        String access = text(root, "access_token");
        if (access == null || access.isBlank()) {
            throw new IllegalStateException("令牌响应缺少 access_token");
        }
        String refresh = text(root, "refresh_token");
        long expires = longValue(root, "expires_in");
        return new TokenResult(access, refresh, expires);
    }

    private static String clientSecretParam(String clientSecret) {
        return (clientSecret == null || clientSecret.isBlank())
                ? "" : "&client_secret=" + urlEncode(clientSecret);
    }

    private static void openBrowser(String url) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("当前环境不支持自动打开浏览器，请手动访问: " + url);
        }
        Desktop.getDesktop().browse(URI.create(url));
    }

    private static String randomState() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(pair, "");
            } else {
                map.put(uriDecode(pair.substring(0, eq)), uriDecode(pair.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String uriDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n == null || n.isNull()) ? 0L : n.asLong();
    }
}