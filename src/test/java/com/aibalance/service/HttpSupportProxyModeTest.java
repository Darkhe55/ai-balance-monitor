package com.aibalance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpSupportProxyModeTest {

    @Test
    void defaultsToSystemProxyMode() {
        HttpSupport http = new HttpSupport();
        assertEquals(HttpSupport.MODE_SYSTEM, http.proxyMode());
    }

    @Test
    void switchModeRoundTrip() {
        HttpSupport http = new HttpSupport(HttpSupport.MODE_DIRECT);
        assertEquals(HttpSupport.MODE_DIRECT, http.proxyMode());
        http.setProxyMode(HttpSupport.MODE_SYSTEM);
        assertEquals(HttpSupport.MODE_SYSTEM, http.proxyMode());
        http.setProxyMode(HttpSupport.MODE_DIRECT);
        assertEquals(HttpSupport.MODE_DIRECT, http.proxyMode());
    }

    @Test
    void invalidModeFallsBackToDirect() {
        HttpSupport http = new HttpSupport("unknown-mode");
        assertEquals(HttpSupport.MODE_DIRECT, http.proxyMode());
    }
}
