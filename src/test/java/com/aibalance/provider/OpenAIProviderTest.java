package com.aibalance.provider;

import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import com.aibalance.service.HttpSupport;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAIProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void mergeCostsSumsMultiDimBuckets() throws Exception {
        String body = """
                {"object":"page","has_more":false,"next_page":null,
                 "data":[
                   {"object":"bucket","start_time":1730419200,"end_time":1730505600,
                    "results":[
                      {"object":"organization.costs.result","amount":{"value":0.06,"currency":"usd"},
                       "line_item":"Chat","project_id":"proj_a"},
                      {"object":"organization.costs.result","amount":{"value":0.04,"currency":"usd"},
                       "line_item":"Embeddings","project_id":"proj_a"},
                      {"object":"organization.costs.result","amount":{"value":0.02,"currency":"usd"},
                       "line_item":"Chat","project_id":"proj_b"}
                    ]}
                 ]}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(
                com.aibalance.model.ProviderId.OPENAI_ADMIN, "a", "n");
        OpenAIProvider.mergeCosts(b, MAPPER.readTree(body));
        BalanceSnapshot.MetricBlock cost = b.metricOf(ProviderCapability.COST);
        assertNotNull(cost);
        assertEquals(0.12, cost.amount(), 1e-9);
        assertEquals("usd", cost.currency());
        boolean hasChat = b.build().details().stream()
                .anyMatch(d -> d.label().startsWith("费用项 Chat"));
        boolean hasProject = b.build().details().stream()
                .anyMatch(d -> d.label().startsWith("项目 proj_a"));
        assertEquals(true, hasChat);
        assertEquals(true, hasProject);
    }

    @Test
    void mergeCostsEmptyDataProducesZeroMetric() throws Exception {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(
                com.aibalance.model.ProviderId.OPENAI_ADMIN, "a", "n");
        OpenAIProvider.mergeCosts(b, MAPPER.readTree("{\"data\": []}"));
        BalanceSnapshot.MetricBlock cost = b.metricOf(ProviderCapability.COST);
        assertNotNull(cost);
        assertEquals(0.0, cost.amount(), 1e-9);
    }

    @Test
    void mergeUsageSumsTokensByModel() throws Exception {
        String body = """
                {"object":"page","has_more":false,"next_page":null,
                 "data":[
                   {"object":"bucket","start_time":1730419200,"end_time":1730505600,
                    "results":[
                      {"object":"organization.usage.completions.result","model":"gpt-4o",
                       "input_tokens":100,"output_tokens":50,"input_cached_tokens":10,"num_model_requests":3},
                      {"object":"organization.usage.completions.result","model":"gpt-4o-mini",
                       "input_tokens":200,"output_tokens":80,"input_cached_tokens":0,"num_model_requests":5}
                    ]}
                 ]}
                """;
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(
                com.aibalance.model.ProviderId.OPENAI_ADMIN, "a", "n");
        OpenAIProvider.mergeUsage(b, MAPPER.readTree(body));
        BalanceSnapshot.MetricBlock usage = b.metricOf(ProviderCapability.USAGE);
        assertNotNull(usage);
        assertEquals(430.0, usage.amount(), 1e-9); // 100+50+10+200+80
        boolean hasModel = b.build().details().stream()
                .anyMatch(d -> d.label().startsWith("模型 gpt-4o"));
        assertEquals(true, hasModel);
    }

    @Test
    void mergeUsageEmptyProducesZeroMetric() throws Exception {
        BalanceSnapshot.Builder b = BalanceSnapshot.ok(
                com.aibalance.model.ProviderId.OPENAI_ADMIN, "a", "n");
        OpenAIProvider.mergeUsage(b, MAPPER.readTree("{\"data\": []}"));
        BalanceSnapshot.MetricBlock usage = b.metricOf(ProviderCapability.USAGE);
        assertNotNull(usage);
        assertEquals(0.0, usage.amount(), 1e-9);
    }

    @Test
    void parsesRateLimitHeaders() {
        HttpSupport.Response resp = new HttpSupport.Response(200,
                Map.of("x-ratelimit-limit-tokens", List.of("200000"),
                        "x-ratelimit-remaining-tokens", List.of("150000"),
                        "x-ratelimit-remaining-requests", List.of("2800")),
                "{}");
        assertEquals(200000L, OpenAIProvider.longHeader(resp, "x-ratelimit-limit-tokens"));
        assertEquals(150000L, OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-tokens"));
        assertEquals(2800L, OpenAIProvider.longHeader(resp, "x-ratelimit-remaining-requests"));
        assertNull(OpenAIProvider.longHeader(resp, "x-ratelimit-limit-requests"));
    }
}
