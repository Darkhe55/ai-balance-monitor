package com.aibalance.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个待监控的账户配置。JSON 持久化到本地配置文件。
 */
public final class AccountConfig {

    private String id;
    private ProviderId providerId;
    private String name;
    private String apiKey;
    /** 双凭证厂商的第二个密钥（阿里云/火山/腾讯/百度 Secret，DPAPI 加密落盘） */
    private String secret;
    /** 附加参数（如 xAI 的 team_id=xxx），非敏感，随配置持久化。 */
    private String extraParams;

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }

    /** 解析附加参数串（"key=value; key=value"，分隔符支持 ; 与；），返回小写 key 映射。 */
    public java.util.Map<String, String> parseExtraParams() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (extraParams == null || extraParams.isBlank()) {
            return map;
        }
        for (String part : extraParams.split("[;；]")) {
            int eq = part.indexOf('=');
            if (eq > 0 && eq < part.length() - 1) {
                map.put(part.substring(0, eq).trim().toLowerCase(),
                        part.substring(eq + 1).trim());
            }
        }
        return map;
    }
    private String baseUrl;
    /** 计费形态（BillingMode 枚举名）；为 null 时按厂商默认（双计费厂商默认自动识别） */
    private String billingMode;
    /** 卡片展示条目（有序的条目 key 列表，key = "能力名|标签"，如 "QUOTA|模型 gpt-4o 配额"）；
     *  null / 空 = 展示该账户最近快照的全部条目（顺序按快照返回）。 */
    private java.util.List<String> displayEntries;
    private boolean enabled = true;

    public AccountConfig() {
        // Jackson 反序列化用
    }

    public AccountConfig(ProviderId providerId, String name, String apiKey, String baseUrl) {
        this.id = UUID.randomUUID().toString();
        this.providerId = providerId;
        this.name = name;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public AccountConfig(ProviderId providerId, String name, String apiKey, String secret, String baseUrl) {
        this(providerId, name, apiKey, baseUrl);
        this.secret = secret;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ProviderId getProviderId() {
        return providerId;
    }

    public void setProviderId(ProviderId providerId) {
        this.providerId = providerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** 是否配置了第二凭证（AccessKey Secret 等）。 */
    public boolean hasValidSecret() {
        return secret != null && !secret.strip().isEmpty();
    }

    public String getBillingMode() {
        return billingMode;
    }

    public void setBillingMode(String billingMode) {
        this.billingMode = billingMode;
    }

    /**
     * 实际生效的计费形态：优先配置值；配置缺失/非法时回退到该厂商的默认计费形态
     * （双计费厂商默认自动识别，单一计费厂商为其唯一方式）。
     */
    public BillingMode effectiveBillingMode() {
        if (billingMode != null && !billingMode.isBlank()) {
            try {
                return BillingMode.valueOf(billingMode);
            } catch (IllegalArgumentException ignored) {
                // 配置损坏时回退默认
            }
        }
        return providerId == null ? BillingMode.AUTO : providerId.getDefaultBillingMode();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public java.util.List<String> getDisplayEntries() {
        return displayEntries;
    }

    public void setDisplayEntries(java.util.List<String> displayEntries) {
        this.displayEntries = displayEntries;
    }

    /** 去除首尾空白后是否有效 */
    public boolean hasValidKey() {
        return apiKey != null && !apiKey.strip().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountConfig that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
