package com.aibalance.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyProtectorTest {

    @Test
    void roundTripOnWindows() {
        Assumptions.assumeTrue(KeyProtector.enabled(), "DPAPI 仅 Windows 可用");
        String plain = "sk-test-123456";
        String stored = KeyProtector.protect(plain);
        assertNotNull(stored);
        assertTrue(stored.startsWith("dpapi:"), "密文应带 dpapi: 前缀");
        assertEquals(plain, KeyProtector.unprotect(stored));
    }

    @Test
    void legacyPlainTextPassesThrough() {
        assertEquals("sk-old-plain", KeyProtector.unprotect("sk-old-plain"));
    }

    @Test
    void protectIsIdempotentForCiphertext() {
        assertEquals("dpapi:abc", KeyProtector.protect("dpapi:abc"));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(KeyProtector.unprotect(null));
        assertEquals("", KeyProtector.protect(""));
    }
}
