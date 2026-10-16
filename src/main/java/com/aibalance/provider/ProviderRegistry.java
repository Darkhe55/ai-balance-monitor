package com.aibalance.provider;

import com.aibalance.model.ProviderId;
import com.aibalance.service.HttpSupport;

import java.util.EnumMap;
import java.util.Map;

/**
 * Provider 注册表：按 ProviderId 路由到具体实现。
 * 新增服务只需实现 BalanceProvider 并在此注册。
 */
@SuppressWarnings("deprecation") // 旧百炼入口枚举（BAILIAN_BILLING/QWEN_ACCOUNT）仅用于兼容迁移前的旧配置
public final class ProviderRegistry {

    private final Map<ProviderId, BalanceProvider> providers = new EnumMap<>(ProviderId.class);

    public ProviderRegistry(HttpSupport http) {
        providers.put(ProviderId.DEEPSEEK, new DeepSeekProvider(http));
        providers.put(ProviderId.MOONSHOT, new MoonshotProvider(http));
        providers.put(ProviderId.KIMI_INTL, new MoonshotProvider(http));
        providers.put(ProviderId.SILICONFLOW, new SiliconFlowProvider(http));
        providers.put(ProviderId.OPENAI, new OpenAIProvider(http));
        providers.put(ProviderId.OPENAI_ADMIN, new OpenAIProvider(http));
        providers.put(ProviderId.ANTHROPIC, new AnthropicProvider(http));
        providers.put(ProviderId.GEMINI, new GeminiProvider(http));
        providers.put(ProviderId.GEMINI_BILLING, new GeminiBillingProvider(http));
        providers.put(ProviderId.ONEAPI, new OneApiProvider(http));
        providers.put(ProviderId.OPENROUTER, new OpenRouterProvider(http));
        providers.put(ProviderId.GLM, new GlmProvider(http));
        providers.put(ProviderId.MINIMAX, new MiniMaxProvider(http));
        providers.put(ProviderId.MINIMAX_INTL, new MiniMaxProvider(http));
        providers.put(ProviderId.KIMI_CODING, new KimiCodingProvider(http));
        providers.put(ProviderId.BAILIAN_TOKENPLAN, new ModelStudioTokenPlanProvider(http));
        // 合并入口：账户余额 + 当月账单（阿里云官方 SDK）
        providers.put(ProviderId.BAILIAN_ACCOUNT, new AliyunAccountProvider(http));
        // 国际站入口：同一实现，endpoint 按入口解析（business.ap-southeast-1.aliyuncs.com）
        providers.put(ProviderId.BAILIAN_ACCOUNT_INTL, new AliyunAccountProvider(http));
        // 旧入口枚举已合并进 BAILIAN_ACCOUNT；仍映射到同一实现，兼容启动迁移前的旧配置
        providers.put(ProviderId.BAILIAN_BILLING, new AliyunAccountProvider(http));
        providers.put(ProviderId.QWEN_ACCOUNT, new AliyunAccountProvider(http));
        providers.put(ProviderId.VOLCENGINE, new VolcengineProvider(http));
        providers.put(ProviderId.TENCENT, new TencentProvider(http));
        providers.put(ProviderId.BAIDU, new BaiduProvider(http));
        providers.put(ProviderId.XAI, new XaiProvider(http));
        providers.put(ProviderId.STEPFUN, new StepFunProvider(http));
        providers.put(ProviderId.QODER_CN, new QoderProvider(http, ProviderId.QODER_CN));
        providers.put(ProviderId.QODER_INTL, new QoderProvider(http, ProviderId.QODER_INTL));
        providers.put(ProviderId.XIAOMI, new XiaomiProvider(http));
        providers.put(ProviderId.UNISOUND, new UnisoundProvider(http));
        providers.put(ProviderId.LONGCAT, new LongCatProvider(http));
        providers.put(ProviderId.MODELSCOPE, new ModelScopeProvider(http));
        providers.put(ProviderId.OPENCODE_GO, new OpenCodeGoProvider(http, ProviderId.OPENCODE_GO));
        providers.put(ProviderId.COMMANDCODE, new CommandCodeProvider(http, ProviderId.COMMANDCODE));
        providers.put(ProviderId.TOAPIS, new ToApisProvider(http, ProviderId.TOAPIS));
        providers.put(ProviderId.ZENMUX, new ZenMuxProvider(http, ProviderId.ZENMUX));
    }

    public BalanceProvider forId(ProviderId id) {
        return providers.get(id);
    }
}
