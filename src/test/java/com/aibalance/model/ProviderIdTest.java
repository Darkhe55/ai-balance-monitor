package com.aibalance.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // 中国站 / 国际站已拆成两个独立入口，各自预填官方 endpoint（不再使用 Region 字段语义）
        assertEquals("https://business.aliyuncs.com", ProviderId.BAILIAN_ACCOUNT.getDefaultBaseUrl());
        assertEquals("https://business.ap-southeast-1.aliyuncs.com",
                ProviderId.BAILIAN_ACCOUNT_INTL.getDefaultBaseUrl());
        assertEquals(ProviderId.Region.DOMESTIC, ProviderId.BAILIAN_ACCOUNT.getRegion());
        assertEquals(ProviderId.Region.OVERSEAS, ProviderId.BAILIAN_ACCOUNT_INTL.getRegion());
    }

    @Test
    void aliyunEntriesRecommendLeastPrivilegeRamPolicies() {
        // 余额入口：财务只读策略（含 bss:Describe* / Query*，覆盖 QueryAccountBalance 的授权点 bss:DescribeAcccount）
        assertTrue(ProviderId.BAILIAN_ACCOUNT.getDescription()
                .contains("AliyunFinanceConsoleReadOnlyAccess"));
        assertTrue(ProviderId.BAILIAN_ACCOUNT_INTL.getDescription()
                .contains("AliyunFinanceConsoleReadOnlyAccess"));
        // Token Plan 入口：百炼控制面只读策略（含 modelstudio:List* / Get*）
        assertTrue(ProviderId.BAILIAN_TOKENPLAN.getDescription()
                .contains("AliyunModelStudioReadOnlyAccess"));
        // 不再推荐信息量不足的笼统表述
        assertFalse(ProviderId.BAILIAN_ACCOUNT.getDescription().contains("AliyunBSSReadOnlyAccess"));
        assertFalse(ProviderId.BAILIAN_TOKENPLAN.getDescription().contains("仅授权调用本接口所需的最小权限"));
    }

    @Test
    @SuppressWarnings("deprecation") // 旧入口枚举仅用于验证隐藏与兼容
    void removedBailianQuotaEntryIsHidden() {
        // 「阿里百炼·模型配额」入口已移除：官方 /api/v1/models/limits 只返回限流配额（RPM/TPM），
        // 模型 API Key 无法查余额；旧配置启动时迁移到「阿里云百炼·账户余额与账单」
        assertTrue(ProviderId.BAILIAN.isHidden());
        assertTrue(ProviderId.BAILIAN.capabilities().isEmpty());
        assertTrue(ProviderId.BAILIAN.getDescription().contains("已移除"));
    }

    @Test
    void aliyunIntlEntryMatchesChinaEntryShape() {
        // 国际站入口与中国站同形（AccessKey ID/Secret 双凭证 + 余额/账单能力），仅站点不同
        assertEquals("阿里云百炼·账户余额与账单（国际站）", ProviderId.BAILIAN_ACCOUNT_INTL.getDisplayName());
        assertEquals("阿里云 AccessKey ID", ProviderId.BAILIAN_ACCOUNT_INTL.getKeyFieldLabel());
        assertEquals("阿里云 AccessKey Secret", ProviderId.BAILIAN_ACCOUNT_INTL.getSecretFieldLabel());
        assertTrue(ProviderId.BAILIAN_ACCOUNT_INTL.getKeyPrompt().contains("LTAI"));
        assertTrue(ProviderId.BAILIAN_ACCOUNT_INTL.getSecretPrompt().contains("AccessKey Secret"));
        assertTrue(ProviderId.BAILIAN_ACCOUNT_INTL.capabilities().contains(ProviderCapability.BALANCE));
        assertTrue(ProviderId.BAILIAN_ACCOUNT_INTL.capabilities().contains(ProviderCapability.COST));
        assertEquals(false, ProviderId.BAILIAN_ACCOUNT_INTL.isHidden());
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

    @Test
    void zenMuxAndCommandCodeExplainCredentialType() {
        // ZenMux：明确要求平台 Management API Key，而非 PAYG/订阅 Key
        assertTrue(ProviderId.ZENMUX.getDescription().contains("平台 API Key"));
        assertTrue(ProviderId.ZENMUX.getDescription().contains("Management API Key"));
        assertTrue(ProviderId.ZENMUX.getDescription().contains("PAYG Key"));
        assertTrue(ProviderId.ZENMUX.getKeyPrompt().contains("Management API Key"));
        // CommandCode：使用 API 密钥
        assertTrue(ProviderId.COMMANDCODE.getDescription().contains("API 密钥"));
        assertTrue(ProviderId.COMMANDCODE.getKeyPrompt().contains("commandcode.ai"));
    }
}
