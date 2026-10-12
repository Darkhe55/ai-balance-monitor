package com.aibalance.model;

/**
 * 计费形态：账户使用哪一种付费方式。
 * <ul>
 *   <li>{@link #AUTO}：自动识别——先按订阅套餐接口查询，接口明确拒绝（非套餐 Key）时
 *       自动降级为按量付费方式（仅对存在可探测差异的双计费厂商生效）。</li>
 *   <li>{@link #PAY_AS_YOU_GO}：按量付费——使用普通 API Key，按实际用量计费。</li>
 *   <li>{@link #SUBSCRIPTION}：订阅套餐——使用套餐专属 Key，消耗套餐/订阅额度。</li>
 * </ul>
 * 是否可选取决于具体厂商：多数厂商只有一种计费方式，此时表单固定显示唯一方式；
 * 同一厂商同时存在两种计费方式且 Key 相互独立时（如 GLM、MiniMax、小米、云知声），
 * 才提供选择。
 */
public enum BillingMode {

    AUTO("自动识别", "自动识别（优先套餐接口，失败自动按量）"),
    PAY_AS_YOU_GO("按量付费", "普通 API Key，按实际用量计费"),
    SUBSCRIPTION("订阅套餐", "套餐专属 Key，消耗套餐额度");

    private final String label;
    private final String description;

    BillingMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
