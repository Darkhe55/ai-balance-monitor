package com.aibalance.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderIdTest {

    @Test
    void aliyunEntriesUseAccessKeyLabels() {
        // 合并入口「阿里云百炼·账户余额与账单」使用官方 AccessKey 参数名
        assertEquals("阿里云 AccessKey ID", ProviderId.BAILIAN_ACCOUNT.getKeyFieldLabel());
        assertEquals("阿里云 AccessKey Secret", ProviderId.BAILIAN_ACCOUNT.getSecretFieldLabel());
        assertTrue(ProviderId.BAILIAN_ACCOUNT.getKeyPrompt().contains("LTAI"));
        assertTrue(ProviderId.BAILIAN_ACCOUNT.getKeyPrompt().contains("AccessKey 管理"));
        // Secret 占位符也要给出配对说明
        assertTrue(ProviderId.BAILIAN_ACCOUNT.getSecretPrompt().contains("AccessKey Secret"));
        // 合并入口同时具备余额 + 账单能力
        assertTrue(ProviderId.BAILIAN_ACCOUNT.capabilities().contains(ProviderCapability.BALANCE));
        assertTrue(ProviderId.BAILIAN_ACCOUNT.capabilities().contains(ProviderCapability.COST));
    }

    @Test
    @SuppressWarnings("deprecation") // 旧入口枚举仅用于验证隐藏与兼容
    void mergedAliyunEntryHidesLegacyOnes() {
        // 旧「官方账单 / 账户余额」入口已合并，保留枚举仅用于兼容旧配置，UI 隐藏
        assertTrue(ProviderId.BAILIAN_BILLING.isHidden());
        assertTrue(ProviderId.QWEN_ACCOUNT.isHidden());
        assertEquals(false, ProviderId.BAILIAN_ACCOUNT.isHidden());
        // 合并入口无 URL 默认值（配置为 Region）
        assertEquals(false, ProviderId.BAILIAN_ACCOUNT.hasDefaultBaseUrl());
    }

    @Test
    void tokenPlanEntryUsesAccessKeyLabelsAndCapabilities() {
        assertEquals("阿里云 AccessKey ID", ProviderId.BAILIAN_TOKENPLAN.getKeyFieldLabel());
        assertEquals("阿里云 AccessKey Secret", ProviderId.BAILIAN_TOKENPLAN.getSecretFieldLabel());
        assertTrue(ProviderId.BAILIAN_TOKENPLAN.getKeyPrompt().contains("AccessKey 管理"));
        assertTrue(ProviderId.BAILIAN_TOKENPLAN.getSecretPrompt().contains("AccessKey Secret"));
        assertEquals("https://modelstudio.cn-beijing.aliyuncs.com",
                ProviderId.BAILIAN_TOKENPLAN.getDefaultBaseUrl());
        assertTrue(ProviderId.BAILIAN_TOKENPLAN.capabilities().contains(ProviderCapability.BALANCE));
        assertTrue(ProviderId.BAILIAN_TOKENPLAN.capabilities().contains(ProviderCapability.QUOTA));
        assertEquals(BillingMode.SUBSCRIPTION, ProviderId.BAILIAN_TOKENPLAN.getDefaultBillingMode());
    }

    @Test
    void volcengineAndBaiduUseTheirOwnLabels() {
        assertEquals("火山引擎 AccessKey ID", ProviderId.VOLCENGINE.getKeyFieldLabel());
        assertEquals("火山引擎 Secret Access Key", ProviderId.VOLCENGINE.getSecretFieldLabel());
        assertEquals("百度智能云 Access Key ID", ProviderId.BAIDU.getKeyFieldLabel());
        assertEquals("百度智能云 Secret Access Key", ProviderId.BAIDU.getSecretFieldLabel());
        assertTrue(ProviderId.VOLCENGINE.getKeyPrompt().contains("AKLT"));
        assertTrue(ProviderId.BAIDU.getKeyPrompt().contains("访问密钥 AK/SK"));
    }

    @Test
    void tencentUsesSecretIdSecretKey() {
        assertEquals("腾讯云 SecretId", ProviderId.TENCENT.getKeyFieldLabel());
        assertEquals("腾讯云 SecretKey", ProviderId.TENCENT.getSecretFieldLabel());
        assertTrue(ProviderId.TENCENT.getKeyPrompt().contains("AKID"));
        assertTrue(ProviderId.TENCENT.getKeyPrompt().contains("API 密钥管理"));
    }

    @Test
    void modelKeyProvidersKeepApiKeyLabel() {
        assertEquals("API Key", ProviderId.DEEPSEEK.getKeyFieldLabel());
        assertEquals("Secret", ProviderId.DEEPSEEK.getSecretFieldLabel());
        assertEquals("sk-…（仅保存在本机）", ProviderId.DEEPSEEK.getKeyPrompt());
        assertNull(ProviderId.DEEPSEEK.getSecretPrompt());
        assertEquals("API Key", ProviderId.OPENAI_ADMIN.getKeyFieldLabel());
    }

    @Test
    void xaiAndGeminiBillingHaveClearPrompts() {
        assertTrue(ProviderId.XAI.getKeyPrompt().contains("console.x.ai"));
        assertTrue(ProviderId.XAI.getKeyPrompt().contains("不是模型调用 Key"));
        assertEquals("OAuth 令牌", ProviderId.GEMINI_BILLING.getKeyFieldLabel());
        assertTrue(ProviderId.GEMINI_BILLING.getKeyPrompt().contains("gcloud auth print-access-token"));
    }
}
