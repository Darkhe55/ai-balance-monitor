package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void parsesKeyQuota() throws Exception {
        String body = """
                {"data": {"label": "my-key", "usage": 12.5, "limit": 50.0,
                          "is_limited": false,
                          "rate_limit": {"requests": 1000, "interval": "10s"}}}
                """;
        BalanceSnapshot snap = OpenRouterProvider.fromKeyJson(
                "a1", "中转", MAPPER.readTree(body)).build();

        assertEquals(BalanceSnapshot.Status.OK, snap.status());
        // limit - usage = 37.5 USD
        assertEquals(37.5, snap.primaryAmount(), 1e-9);
        assertEquals("USD", snap.primaryCurrency());
        assertEquals("Key 额度上限", snap.primaryLabel());
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("Key 名称") && l.value().equals("my-key")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("额度上限") && l.value().equals("$ 50.00")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("已用额度") && l.value().equals("$ 12.50")));
        assertTrue(snap.details().stream().anyMatch(l -> l.label().equals("速率限制") && l.value().equals("1,000 次 / 10s")));
    }

    @Test
    void limitReachedMarksLimited() throws Exception {
        String body = """
                {"data": {"usage": 100.0, "limit": 100.0, "is_limited": true}}
                """;
        BalanceSnapshot snap = OpenRouterProvider.fromKeyJson(
                "a1", "中转", MAPPER.readTree(body)).build();
        assertEquals(0.0, snap.primaryAmount(), 1e-9);
        assertTrue(snap.message().contains("已达限额"));
    }

    @Test
    void missingDataYieldsError() throws Exception {
        BalanceSnapshot snap = OpenRouterProvider.fromKeyJson(
                "a1", "中转", MAPPER.readTree("{\"error\": \"unauthorized\"}")).build();
        assertEquals(BalanceSnapshot.Status.ERROR, snap.status());
    }

    @Test
    void parsesCreditsBalance() throws Exception {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.OPENROUTER, "a1", "中转")
                .label("Key 额度上限").currency("USD").amount(37.5);
        String body = """
                {"credits": {"total_credits": 100.0, "total_usage": 30.0}}
                """;
        OpenRouterProvider.creditsInto(b, MAPPER.readTree(body));
        BalanceSnapshot snap = b.build();
        assertTrue(snap.details().stream().anyMatch(l ->
                l.label().equals("账户余额") && l.value().equals("$ 100.00（可用 $ 70.00）")));
    }

    @Test
    void creditsMissingFieldNotesIt() throws Exception {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(ProviderId.OPENROUTER, "a1", "中转");
        OpenRouterProvider.creditsInto(b, MAPPER.readTree("{\"credits\": {}}"));
        BalanceSnapshot snap = b.build();
        assertTrue(snap.details().stream().anyMatch(l ->
                l.label().equals("账户余额") && l.value().equals("未返回 total_credits")));
    }
}
