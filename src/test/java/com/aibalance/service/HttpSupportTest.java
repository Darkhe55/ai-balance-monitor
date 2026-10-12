package com.aibalance.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSupportTest {

    @Test
    void rejectsPlainHttpUrl() {
        HttpSupport http = new HttpSupport();
        HttpSupport.HttpFailure e = assertThrows(HttpSupport.HttpFailure.class,
                () -> http.get("http://api.deepseek.com/user/balance", Map.of("Authorization", "Bearer sk-x")));
        assertTrue(e.getMessage().contains("HTTPS"));
        // 非网络类错误（本地校验失败）
        assertEquals(false, e.isNetwork());
    }

    @Test
    void rejectsNullUrl() {
        HttpSupport http = new HttpSupport();
        assertThrows(HttpSupport.HttpFailure.class, () -> http.get(null, Map.of()));
    }
}
