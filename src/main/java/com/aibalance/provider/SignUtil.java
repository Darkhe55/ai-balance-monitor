package com.aibalance.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 云厂商签名共用工具（火山引擎 HMAC-SHA256 认证头、腾讯云 TC3-HMAC-SHA256、
 * 百度 BCE bce-auth-v1 共用底层原语）。仅包内使用，不对外暴露。
 */
final class SignUtil {

    private SignUtil() {
    }

    /** HMAC-SHA256（key 为 UTF-8 字节）。 */
    static byte[] hmacSha256(String key, String data) {
        return hmacSha256(key.getBytes(StandardCharsets.UTF_8), data);
    }

    /** HMAC-SHA256（key 为原始字节）。 */
    static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 十六进制（小写）。 */
    static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 字节转小写十六进制。 */
    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** RFC3986 百分号编码（阿里云/腾讯/火山签名规范通用）。 */
    static String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
