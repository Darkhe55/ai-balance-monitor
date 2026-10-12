package com.aibalance.model;

/**
 * 支持的 AI 服务提供方。
 */
public enum ProviderId {

    // ===== 国内厂商 =====
    DEEPSEEK("DeepSeek 深度求索", "#4D6BFE", "https://api.deepseek.com",
            "填 DeepSeek 平台 API Key（platform.deepseek.com → API Keys 创建）。工具调用官方余额接口实时显示账户余额，Key 仅保存在本机。",
            "按量付费 · 官方余额", "DeepSeek 主账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    MOONSHOT("Kimi 月之暗面（中国区）", "#7C6CEC", "https://api.moonshot.cn",
            "填 Kimi 中国区 API Key（platform.moonshot.cn → API Key 管理）。官方余额接口返回现金余额与代金券明细。",
            "按量付费 · 官方余额", "Kimi 中国区账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    KIMI_INTL("Kimi（国际区）", "#7C6CEC", "https://api.kimi.ai",
            "填 Kimi 国际区 API Key（platform.kimi.ai 获取）。与国内 Key 相互独立，官方余额接口返回现金余额与代金券。",
            "按量付费 · 官方余额", "Kimi 国际区账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    SILICONFLOW("硅基流动 SiliconFlow", "#00B8A9", "https://api.siliconflow.cn",
            "官方未提供公开可长期使用的余额查询接口，本入口暂不可用。",
            "按量付费 · 官方余额", "硅基流动账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    GLM("智谱 GLM", "#3B62FF", "https://open.bigmodel.cn",
            "同一厂商两种计费、Key 互不通用：①订阅套餐（Coding Plan，5 小时/周窗口额度）→ 填套餐专用 Key；②按量付费 → 填普通 Key，官方余额接口实时显示余额。国内 open.bigmodel.cn，海外 api.z.ai。",
            "双计费 · 套餐额度 / 按量余额", "GLM 账户", Region.DOMESTIC,
            BillingMode.AUTO, BillingMode.PAY_AS_YOU_GO, BillingMode.SUBSCRIPTION),
    MINIMAX("MiniMax 海螺（中国站）", "#9A5CFE", "https://www.minimaxi.com",
            "同一厂商两种计费、Key 互不通用：①Token Plan 订阅套餐 → 填套餐专用 Key，官方接口 GET /v1/token_plan/remains 显示各套餐窗口剩余额度；②按量付费 → 填普通 Key（钱包余额无公开接口，仅校验 Key 有效）。中国站 minimaxi.com，国际站 minimax.io。",
            "双计费 · 套餐额度 / 按量校验", "MiniMax 中国站账户", Region.DOMESTIC,
            BillingMode.AUTO, BillingMode.PAY_AS_YOU_GO, BillingMode.SUBSCRIPTION),
    MINIMAX_INTL("MiniMax 海螺（国际站）", "#9A5CFE", "https://www.minimax.io",
            "同一厂商两种计费、Key 互不通用：①Token Plan 订阅套餐 → 填套餐专用 Key，官方接口 GET /v1/token_plan/remains 显示各套餐窗口剩余额度；②按量付费 → 填普通 Key（钱包余额无公开接口，仅校验 Key 有效）。中国站 minimaxi.com，国际站 minimax.io。",
            "双计费 · 套餐额度 / 按量校验", "MiniMax 国际站账户", Region.OVERSEAS,
            BillingMode.AUTO, BillingMode.PAY_AS_YOU_GO, BillingMode.SUBSCRIPTION),
    KIMI_CODING("Kimi Coding 套餐", "#6C5CE7", "https://api.kimi.com",
            "填 Kimi Code 订阅套餐专用 Key。官方接口显示 5 小时/周窗口额度。默认国内 api.kimi.com，海外填 api.kimi.ai。",
            "订阅套餐 · Coding 额度", "Kimi Coding 套餐账户", Region.DOMESTIC,
            BillingMode.SUBSCRIPTION),
    BAILIAN("阿里百炼·模型配额", "#FF6A00", "https://dashscope.aliyuncs.com/",
            "填阿里云百炼 DashScope API Key（百炼控制台 → API-KEY 管理创建）。官方接口 GET /api/v1/models/limits 返回的是各模型限流配额（请求/用量频率限制），不是余额也不是套餐额度。注意：该接口域名为 https://{业务空间ID}.cn-beijing.maas.aliyuncs.com，请把 Base URL 改为你的业务空间域名；若要查 Token Plan 套餐 Credits 额度，请改用「阿里云百炼·Token Plan 额度」入口。",
            "按量付费 · 模型限流配额", "百炼配额账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    BAILIAN_TOKENPLAN("阿里云百炼·Token Plan 额度", "#FF6A00", "https://modelstudio.cn-beijing.aliyuncs.com",
            "账号授权方式：填阿里云 AccessKey ID + AccessKey Secret（阿里云控制台右上角头像 → AccessKey 管理创建）。安全建议：创建 RAM 子账号，仅授权调用本接口所需的最小权限，勿用主账号密钥。官方 OpenAPI（ListSubscriptionSharedPackages / GetSubscriptionSeatDetails）返回 Token Plan 各订阅/共享包的 Credits 周期总额与剩余额度（含周期起止时间）；未订阅 Token Plan 时返回无数据。账户现金余额请用「阿里云百炼·账户余额」入口。",
            "订阅套餐 · Token Plan Credits", "百炼 Token Plan 账户", Region.DOMESTIC,
            BillingMode.SUBSCRIPTION),
    /**
     * 阿里云百炼·账户余额与账单（合并入口）：经阿里云官方 SDK 调用 BssOpenApi
     * （QueryAccountBalance + QueryAccountBill），endpoint 按 Service=BssOpenApi + Region 解析。
     * 凭证填 AccessKey ID / AccessKey Secret，Region 填阿里云地域（默认 cn-beijing）。
     */
    BAILIAN_ACCOUNT("阿里云百炼·账户余额与账单", "#FF6A00", "",
            "账号授权方式：填阿里云 AccessKey ID + AccessKey Secret（阿里云控制台右上角头像 → AccessKey 管理创建）。"
                    + "安全建议：创建 RAM 子账号，仅授权 BSS 只读权限（AliyunBSSReadOnlyAccess），勿用主账号密钥。"
                    + "Region 填阿里云地域（默认 cn-beijing；国际站地域如 ap-southeast-1 自动使用国际版端点）。"
                    + "工具经阿里云官方 SDK（BssOpenApi 2017-12-14）调用 QueryAccountBalance 与 QueryAccountBill，"
                    + "合并显示账户余额、可用现金、信控额度与当月账单费用明细。",
            "云账户余额 + 当月账单 · 官方 SDK", "阿里云账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    /** @deprecated 已合并进 {@link #BAILIAN_ACCOUNT}，仅保留枚举值兼容旧配置文件，UI 不再展示。 */
    @Deprecated
    BAILIAN_BILLING("阿里云百炼·官方账单", "#FF6A00", "https://business.aliyuncs.com",
            "账号授权方式：填阿里云 AccessKey ID + AccessKey Secret（阿里云控制台右上角头像 → AccessKey 管理创建）。安全建议：创建 RAM 子账号，仅授权 BSS 只读权限（AliyunBSSReadOnlyAccess），勿用主账号密钥。工具调用官方账单接口查询当月费用与产品明细（含百炼计费项）。",
            "云账单 · 当月费用", "阿里云账单账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    /** @deprecated 已合并进 {@link #BAILIAN_ACCOUNT}，仅保留枚举值兼容旧配置文件，UI 不再展示。 */
    @Deprecated
    QWEN_ACCOUNT("阿里云百炼·账户余额", "#FF6A00", "https://business.aliyuncs.com",
            "账号授权方式：填阿里云 AccessKey ID + AccessKey Secret（阿里云控制台右上角头像 → AccessKey 管理创建）。安全建议：创建 RAM 子账号，仅授权 BSS 只读权限（AliyunBSSReadOnlyAccess），勿用主账号密钥。官方接口返回可用余额、可用现金、信控额度。",
            "云账户余额 · 官方接口", "阿里云账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    VOLCENGINE("火山方舟", "#3370FF", "https://billing.volcengineapi.com",
            "账号授权方式：填火山引擎 AccessKey ID + Secret Access Key（火山引擎控制台右上角头像 → API 访问密钥创建）。安全建议：子账号仅授权 BillingCenterReadOnlyAccess 只读权限。官方接口返回可用余额、现金余额、欠费、冻结、信控额度。",
            "云账户余额 · 官方接口", "火山引擎账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    TENCENT("腾讯混元", "#006EFF", "https://billing.tencentcloudapi.com",
            "账号授权方式：填腾讯云 SecretId + SecretKey（腾讯云控制台 → 访问管理 → API 密钥管理创建，SecretId 形如 AKID…）。安全建议：CAM 子用户仅授权计费只读权限（QcloudBillingReadOnlyAccess）。官方接口返回可用余额、现金余额、信用额度、冻结金额、欠费。",
            "云账户余额 · 官方接口", "腾讯云账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    BAIDU("百度千帆", "#2932E1", "https://billing.baidubce.com",
            "账号授权方式：填百度智能云 Access Key ID + Secret Access Key（百度智能云控制台 → 安全认证 → 访问密钥 AK/SK 创建）。安全建议：子账号仅授权余额查询权限，无需账单权限。官方接口返回现金余额（元）。",
            "云账户余额 · 官方接口", "百度智能云账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    STEPFUN("阶跃星辰 StepFun", "#2E6BE6", "https://api.stepfun.com",
            "填阶跃星辰 API Key（platform.stepfun.com → API Keys 创建）。官方余额接口 GET /v1/accounts 返回可用余额、充值金额与赠送金额。",
            "按量付费 · 官方余额", "StepFun 账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO),
    XIAOMI("小米 MiMo", "#FF6900", "https://api.xiaomimimo.com/v1",
            "官方未开放余额/额度查询接口，仅能校验 Key 有效，本入口暂不可用。",
            "双计费 · 均 Key 校验（无公开余额接口）", "小米 MiMo 账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO, BillingMode.SUBSCRIPTION),
    UNISOUND("云知声 Unisound", "#1470C8", "https://maas-api.unisound.com/v1",
            "官方未开放余额/额度查询接口，仅能校验 Key 有效，本入口暂不可用。",
            "双计费 · 均 Key 校验（无公开余额接口）", "云知声 MaaS 账户", Region.DOMESTIC,
            BillingMode.PAY_AS_YOU_GO, BillingMode.SUBSCRIPTION),

    // ===== 海外厂商 =====
    OPENAI("OpenAI（API Key）", "#10A37F", "https://api.openai.com",
            "填 OpenAI 模型调用 Key（platform.openai.com → API keys 创建）。该 Key 无费用/用量权限，仅校验有效性与速率配额；查费用/用量请改用「OpenAI（Admin Key）」入口。",
            "模型调用 · Key 校验", "OpenAI API Key 账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    OPENAI_ADMIN("OpenAI（Admin Key）", "#10A37F", "https://api.openai.com",
            "填 OpenAI 组织管理员 Key（platform.openai.com → Settings → Organization → API keys 创建，需要 Owner/Admin 角色）。工具调用官方接口 GET /v1/organization/costs（项目/Key/产品多维费用）+ GET /v1/organization/usage/completions（用量）合并显示。",
            "费用 + 用量 · 组织接口", "OpenAI Admin 账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    ANTHROPIC("Anthropic Claude", "#D97757", "https://api.anthropic.com",
            "填 Anthropic API Key（console.anthropic.com → API Keys 创建）。工具读取官方 API 响应头中的速率配额（5 分钟窗口）。",
            "按量付费 · 速率配额", "Claude 账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    GEMINI("Google Gemini（API Key）", "#4285F4", "https://generativelanguage.googleapis.com/v1beta",
            "填 Google AI Studio API Key（aistudio.google.com → API keys 创建）。仅校验有效性；余额/预算请改用「Google Gemini（Cloud 账单）」入口。",
            "模型调用 · Key 校验", "Gemini 账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    GEMINI_BILLING("Google Gemini（Cloud 账单）", "#4285F4", "https://billingbudgets.googleapis.com/v1",
            "账号授权方式：①需有 Google Cloud 项目并开通 Billing Budget API；②把 Base URL 补全为 …/v1/billingAccounts/{账单ID}（账单ID 在 Google Cloud 控制台 → 结算 → 账户管理查看，例 …/billingAccounts/0123-4567-8901）；③凭证字段填 OAuth 访问令牌（在已登录 gcloud 的机器执行 gcloud auth print-access-token 生成，约 1 小时有效，过期需重新获取）。查询预算金额与花费。",
            "预算 · Google Cloud Billing", "Gemini Cloud 账单账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),
    XAI("xAI / Grok", "#9AA0A6", "https://management-api.x.ai",
            "填 xAI Management API Key（console.x.ai → Billing → API Keys 创建，注意不是模型调用 Key）。组织级 Key 还须在「附加参数」填 team_id=xxx（团队 ID 在 console.x.ai 账户设置查看）。官方接口返回预付余额与变动记录。",
            "按量付费 · 预付余额", "Grok 账户", Region.OVERSEAS,
            BillingMode.PAY_AS_YOU_GO),

    // ===== 中转站 / 网关 =====
    ONEAPI("OneAPI / NewAPI 网关", "#F59E0B", "",
            "填你的中转站令牌（OneAPI/NewAPI 管理后台 → 令牌 → 创建令牌）。查询剩余配额。无官方默认域名，Base URL 必填你的网关地址。",
            "网关配额 · 剩余额度", "我的中转站", Region.GATEWAY,
            BillingMode.PAY_AS_YOU_GO),
    OPENROUTER("OpenRouter 中转", "#6466E9", "https://openrouter.ai/api",
            "填 OpenRouter API Key（openrouter.ai → Keys 创建）。显示官方 Key 配额（limit/usage）与账户 Credits 余额。",
            "按量付费 · Key 配额 + Credits", "OpenRouter 账户", Region.GATEWAY,
            BillingMode.PAY_AS_YOU_GO);

    /** 选型弹窗的分区：国内 / 海外 / 中转站。 */
    public enum Region {
        DOMESTIC("国内厂商"),
        OVERSEAS("海外厂商"),
        GATEWAY("中转站");

        private final String label;

        Region(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String displayName;
    private final String brandColor;
    private final String defaultBaseUrl;
    private final String description;
    private final String billingModeLabel;
    private final String namePrompt;
    private final Region region;
    private final BillingMode[] supportedBillingModes;
    private final BillingMode defaultBillingMode;

    ProviderId(String displayName, String brandColor, String defaultBaseUrl,
               String description, String billingModeLabel, String namePrompt, Region region,
               BillingMode... supportedBillingModes) {
        this.displayName = displayName;
        this.brandColor = brandColor;
        this.defaultBaseUrl = defaultBaseUrl;
        this.description = description;
        this.billingModeLabel = billingModeLabel;
        this.namePrompt = namePrompt;
        this.region = region;
        this.supportedBillingModes = supportedBillingModes.length == 0
                ? new BillingMode[]{BillingMode.PAY_AS_YOU_GO}
                : supportedBillingModes;
        BillingMode def = null;
        for (BillingMode m : this.supportedBillingModes) {
            if (m == BillingMode.AUTO) {
                def = BillingMode.AUTO;
                break;
            }
        }
        this.defaultBillingMode = def != null ? def : this.supportedBillingModes[0];
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBrandColor() {
        return brandColor;
    }

    /**
     * 该服务的默认 Base URL；为空的（OneAPI/百炼）表示无官方通用域名，必须手动填写。
     */
    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** 是否有官方默认 Base URL（空默认值的服务必须填写）。 */
    public boolean hasDefaultBaseUrl() {
        return defaultBaseUrl != null && !defaultBaseUrl.isBlank();
    }

    public String getDescription() {
        return description;
    }

    /** 计费形式说明（按量付费 / 订阅配额 / 网关额度 / 双计费），随服务固定标注 */
    public String getBillingModeLabel() {
        return billingModeLabel;
    }

    /** 账户名称输入框的示例提示（随服务切换，避免误导） */
    public String getNamePrompt() {
        return namePrompt;
    }

    /**
     * 凭证字段 1 的显示标签。云签名厂商使用官方参数名并带厂商前缀（阿里云 AccessKey ID /
     * 腾讯云 SecretId 等），避免用户把云凭证误填成模型调用 Key；Gemini 云账单填 OAuth 令牌。
     */
    public String getKeyFieldLabel() {
        return switch (this) {
            case BAILIAN_ACCOUNT, BAILIAN_BILLING, QWEN_ACCOUNT, BAILIAN_TOKENPLAN -> "阿里云 AccessKey ID";
            case VOLCENGINE -> "火山引擎 AccessKey ID";
            case TENCENT -> "腾讯云 SecretId";
            case BAIDU -> "百度智能云 Access Key ID";
            case GEMINI_BILLING -> "OAuth 令牌";
            default -> "API Key";
        };
    }

    /** 凭证字段 2 的显示标签（云签名厂商的 Secret 官方参数名，默认 "Secret"）。 */
    public String getSecretFieldLabel() {
        return switch (this) {
            case BAILIAN_ACCOUNT, BAILIAN_BILLING, QWEN_ACCOUNT, BAILIAN_TOKENPLAN -> "阿里云 AccessKey Secret";
            case VOLCENGINE -> "火山引擎 Secret Access Key";
            case TENCENT -> "腾讯云 SecretKey";
            case BAIDU -> "百度智能云 Secret Access Key";
            default -> "Secret";
        };
    }

    /** 凭证字段 1 的输入占位提示：官方参数名示例 + 获取位置（掩码框内灰字）。 */
    public String getKeyPrompt() {
        return switch (this) {
            case BAILIAN_ACCOUNT, BAILIAN_BILLING, QWEN_ACCOUNT, BAILIAN_TOKENPLAN ->
                    "LTAI…（阿里云控制台右上角头像 → AccessKey 管理；推荐用 RAM 只读子账号密钥）";
            case VOLCENGINE ->
                    "AKLT…（火山引擎控制台右上角头像 → API 访问密钥）";
            case TENCENT ->
                    "AKID…（腾讯云控制台 → 访问管理 → API 密钥管理）";
            case BAIDU ->
                    "b8b4…（百度智能云控制台 → 安全认证 → 访问密钥 AK/SK）";
            case GEMINI_BILLING ->
                    "ya29…（本机执行 gcloud auth print-access-token 生成，约 1 小时有效）";
            case XAI ->
                    "（console.x.ai → Billing → API Keys 创建，注意不是模型调用 Key）";
            default -> "sk-…（仅保存在本机）";
        };
    }

    /** 凭证字段 2（Secret）的输入占位提示；非双凭证厂商返回 null（该行不显示）。 */
    public String getSecretPrompt() {
        return switch (this) {
            case BAILIAN_ACCOUNT, BAILIAN_BILLING, QWEN_ACCOUNT, BAILIAN_TOKENPLAN ->
                    "与上方 AccessKey ID 配对的 AccessKey Secret（创建时仅完整显示一次，妥善保存）";
            case VOLCENGINE ->
                    "与上方 AccessKey ID 配对的 Secret Access Key";
            case TENCENT ->
                    "与上方 SecretId 配对的 SecretKey";
            case BAIDU ->
                    "与上方 Access Key ID 配对的 Secret Access Key";
            default -> null;
        };
    }

    /** 选型弹窗分区（国内 / 海外 / 中转站）。 */
    public Region getRegion() {
        return region;
    }

    /** 该厂商支持的计费形态列表（长度 > 1 表示同一厂商存在多种计费方式）。 */
    public BillingMode[] getSupportedBillingModes() {
        return supportedBillingModes.clone();
    }

    /** 该厂商默认计费形态：双计费厂商默认自动识别，单一计费厂商为其唯一方式。 */
    public BillingMode getDefaultBillingMode() {
        return defaultBillingMode;
    }

    /** 是否存在多种计费方式（按量付费 + 订阅套餐并存且 Key 独立）。 */
    public boolean hasDualBilling() {
        return supportedBillingModes.length > 1;
    }

    /** 该入口是否已在 UI 隐藏（被合并进新入口；保留枚举仅用于兼容旧配置反序列化）。 */
    @SuppressWarnings("deprecation")
    public boolean isHidden() {
        return this == BAILIAN_BILLING || this == QWEN_ACCOUNT;
    }

    /**
     * 该厂商可查询的能力维度（5 种 Adapter 能力）。依据各家官方/实测接口能力声明：
     * <ul>
     *   <li>DeepSeek：balance ✓</li>
     *   <li>Kimi（中国/国际）：balance ✓</li>
     *   <li>OpenAI（Admin Key）：cost ✓ usage ✓ spendLimit ✓</li>
     *   <li>Anthropic：cost ✓ usage ✓（用量来自 usage 响应，费用估算）</li>
     *   <li>Gemini：billing ✓ quota ✓（Google Cloud Billing/Budget）</li>
     *   <li>阿里云百炼：cloudBilling ✓ quota ✓（阿里云官方账单 + 模型配额）</li>
     *   <li>GLM：localUsage ✓（套餐本地用量窗口）</li>
     *   <li>MiniMax：localUsage ✓（Token Plan 本地用量窗口）</li>
     * </ul>
     */
    public java.util.EnumSet<ProviderCapability> capabilities() {
        return switch (this) {
            case DEEPSEEK -> ProviderCapability.set(ProviderCapability.BALANCE);
            case MOONSHOT -> ProviderCapability.set(ProviderCapability.BALANCE);
            case KIMI_INTL -> ProviderCapability.set(ProviderCapability.BALANCE);
            case SILICONFLOW -> ProviderCapability.set(ProviderCapability.BALANCE);
            case STEPFUN -> ProviderCapability.set(ProviderCapability.BALANCE);
            case OPENAI -> ProviderCapability.set();
            case OPENAI_ADMIN -> ProviderCapability.set(
                    ProviderCapability.COST, ProviderCapability.USAGE);
            case ANTHROPIC -> ProviderCapability.set(
                    ProviderCapability.COST, ProviderCapability.USAGE);
            case GEMINI -> ProviderCapability.set();
            case GEMINI_BILLING -> ProviderCapability.set(ProviderCapability.COST);
            case BAILIAN -> ProviderCapability.set(
                    ProviderCapability.COST, ProviderCapability.QUOTA);
            case BAILIAN_TOKENPLAN -> ProviderCapability.set(
                    ProviderCapability.BALANCE, ProviderCapability.QUOTA);
            case BAILIAN_ACCOUNT -> ProviderCapability.set(
                    ProviderCapability.BALANCE, ProviderCapability.COST);
            case BAILIAN_BILLING -> ProviderCapability.set(ProviderCapability.COST);
            case QWEN_ACCOUNT -> ProviderCapability.set(ProviderCapability.BALANCE);
            case VOLCENGINE -> ProviderCapability.set(ProviderCapability.BALANCE);
            case TENCENT -> ProviderCapability.set(ProviderCapability.BALANCE);
            case BAIDU -> ProviderCapability.set(ProviderCapability.BALANCE);
            case XAI -> ProviderCapability.set(ProviderCapability.BALANCE);
            case GLM -> ProviderCapability.set(ProviderCapability.USAGE, ProviderCapability.QUOTA);
            case MINIMAX -> ProviderCapability.set(ProviderCapability.USAGE, ProviderCapability.QUOTA);
            case MINIMAX_INTL -> ProviderCapability.set(ProviderCapability.USAGE, ProviderCapability.QUOTA);
            case KIMI_CODING -> ProviderCapability.set(ProviderCapability.QUOTA);
            case ONEAPI -> ProviderCapability.set(ProviderCapability.QUOTA);
            case OPENROUTER -> ProviderCapability.set(ProviderCapability.BALANCE, ProviderCapability.QUOTA);
            case XIAOMI, UNISOUND -> ProviderCapability.set();
        };
    }
}
