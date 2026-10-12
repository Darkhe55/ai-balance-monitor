package com.aibalance.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderUrlNormalizeTest {

    @Test
    void deepSeekBlankFallsBackToOfficial() {
        assertEquals("https://api.deepseek.com", DeepSeekProvider.normalize(null));
        assertEquals("https://api.deepseek.com", DeepSeekProvider.normalize("   "));
    }

    @Test
    void deepSeekStripsTrailingSlash() {
        assertEquals("https://api.deepseek.com", DeepSeekProvider.normalize("https://api.deepseek.com/"));
        assertEquals("https://my-proxy.example.com",
                DeepSeekProvider.normalize("https://my-proxy.example.com///"));
    }

    @Test
    void moonshotCustomBasePreserved() {
        assertEquals("https://moonshot-proxy.example.com",
                MoonshotProvider.normalize("https://moonshot-proxy.example.com"));
        assertEquals("https://api.moonshot.cn", MoonshotProvider.normalize(""));
    }

    @Test
    void openAiAnthropicGeminiDefaults() {
        assertEquals("https://api.openai.com", OpenAIProvider.normalize(null));
        assertEquals("https://api.anthropic.com", AnthropicProvider.normalize(""));
        assertEquals("https://generativelanguage.googleapis.com/v1beta",
                GeminiProvider.normalize("https://generativelanguage.googleapis.com/v1beta/"));
    }
}
