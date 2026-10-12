package com.aibalance.model;

import java.util.EnumSet;

/**
 * 厂商可查询能力维度（5 种 Adapter 能力，取代单一 getBalance() 模型）。
 * 每个 Provider 声明自身具备的能力，UI 按能力自然适配显示区块。
 */
public enum ProviderCapability {
    /** 账户余额（按量现金余额 / 充值余额）。 */
    BALANCE("余额", "#34D399"),
    /** 用量（Tokens 消耗、调用次数等）。 */
    USAGE("用量", "#60A5FA"),
    /** 费用 / 成本（多维成本分解、云账单）。 */
    COST("费用", "#F59E0B"),
    /** 配额（套餐额度窗口 / 限流配额 / 剩余额度）。 */
    QUOTA("配额", "#A78BFA"),
    /** 消费上限 / 预算（spend limit / Google Billing Budget / 阿里云消费额度）。 */
    SPEND_LIMIT("消费上限", "#F87171");

    private final String label;
    private final String color;

    ProviderCapability(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 便捷构造单能力集合。 */
    public static java.util.EnumSet<ProviderCapability> set(ProviderCapability first, ProviderCapability... rest) {
        return EnumSet.of(first, rest);
    }

    /** 空能力集合（厂商无可查询维度时）。 */
    public static java.util.EnumSet<ProviderCapability> set() {
        return EnumSet.noneOf(ProviderCapability.class);
    }
}
