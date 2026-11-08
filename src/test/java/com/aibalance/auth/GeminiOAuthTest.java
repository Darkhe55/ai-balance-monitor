package com.aibalance.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiOAuthTest {

    @Test
    void parsesSuccessfulTokenResponse() throws Exception {
        String body = """
                {"access_token":"ya29.abc","refresh_token":"1//xyz","expires_in":3599,
                 "token_type":"Bearer","scope":"https://www.googleapis.com/auth/cloud-billing"}
                """;
        GeminiOAuth.TokenResult r = GeminiOAuth.parseTokenResponse(body, 200);
        assertEquals("ya29.abc", r.accessToken());
        assertEquals("1//xyz", r.refreshToken());
        assertEquals(3599, r.expiresInSeconds());
    }

    @Test
    void refreshResponseMayLackRefreshToken() throws Exception {
        String body = "{\"access_token\":\"ya29.new\",\"expires_in\":3600}";
        GeminiOAuth.TokenResult r = GeminiOAuth.parseTokenResponse(body, 200);
        assertEquals("ya29.new", r.accessToken());
        assertNull(r.refreshToken());
    }

    @Test
    void errorResponseThrows() {
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}";
        Exception ex = assertThrows(IllegalStateException.class,
                () -> GeminiOAuth.parseTokenResponse(body, 400));
        assertTrue(ex.getMessage().contains("invalid_grant"));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void missingAccessTokenThrows() {
        Exception ex = assertThrows(IllegalStateException.class,
                () -> GeminiOAuth.parseTokenResponse("{\"refresh_token\":\"x\"}", 200));
        assertTrue(ex.getMessage().contains("access_token"));
    }
}