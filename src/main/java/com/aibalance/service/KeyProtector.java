package com.aibalance.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * API Key 本地保护：Windows DPAPI（CryptProtectData）加密。
 * - 密文以 "dpapi:" 前缀标识，同一 Windows 用户下可解密，跨用户/跨机器不可解；
 * - 非 Windows 平台回退为明文（工具当前仅分发 Windows 自包含运行时）；
 * - 旧版明文配置（无前缀）读取时原样返回，首次保存时自动迁移为密文。
 */
public final class KeyProtector {

    private static final String PREFIX = "dpapi:";
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private KeyProtector() {
    }

    /** 是否启用 DPAPI 加密（仅 Windows）。 */
    public static boolean enabled() {
        return WINDOWS;
    }

    /** 加密明文 Key；已是密文或不可用时原样返回。 */
    public static String protect(String plain) {
        if (plain == null || plain.isBlank() || plain.startsWith(PREFIX)) {
            return plain;
        }
        if (!WINDOWS) {
            return plain;
        }
        try {
            byte[] blob = com.sun.jna.platform.win32.Crypt32Util
                    .cryptProtectData(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (Throwable t) {
            // 加密失败不阻断保存（回退明文，配置仍可用）
            return plain;
        }
    }

    /**
     * 解密存储的 Key；无前缀视为旧明文原样返回；
     * 解密失败（跨用户/跨机器/损坏）返回 null。
     */
    public static String unprotect(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (!WINDOWS) {
            return null;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] plain = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(blob);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
