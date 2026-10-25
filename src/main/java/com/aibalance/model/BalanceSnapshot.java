package com.aibalance.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次余额/配额查询的结果快照（不可变）。
 */
public final class BalanceSnapshot {

    /** 查询状态 */
    public enum Status {
        /** 查询成功 */
        OK,
        /** 服务端返回 401/403，Key 无效或无权限 */
        UNAUTHORIZED,
        /** 网络超时或连接失败 */
        TIMEOUT,
        /** 服务端错误或解析失败 */
        ERROR,
        /** 尚未配置 Key */
        NOT_CONFIGURED
    }

    private final ProviderId providerId;
    private final String accountId;
    private final String accountName;
    private final Status status;

    /** 主金额（余额/配额），可为 null */
    private final Double primaryAmount;
    /** 主金额单位/标签，例如 "CNY"、"USD" */
    private final String primaryCurrency;
    /** 主金额含义，例如 "可用余额"、"剩余令牌配额" */
    private final String primaryLabel;
    /** 剩余比例（0-100，可 null；用于进度条展示，如套餐剩余额度/配额剩余） */
    private final Double percent;

    private final List<DetailLine> details;
    private final String message;
    private final Instant fetchedAt;

    /** 本次快照实际覆盖的能力维度（厂商声明能力 ∩ 本次成功返回）。 */
    private final java.util.EnumSet<ProviderCapability> capabilities;
    /** 能力区块（balance/usage/cost/quota/spendLimit 各维度数据，UI 按能力渲染）。 */
    private final List<MetricBlock> metrics;

    public static final class DetailLine {
        private final String label;
        private final String value;

        public DetailLine(String label, String value) {
            this.label = label;
            this.value = value;
        }

        public String label() {
            return label;
        }

        public String value() {
            return value;
        }

        /**
         * 明细行条目标识（"detail|标签"），与能力区块条目（"能力|标签"）一起参与「展示条目」勾选/排序。
         */
        public String key() {
            return "detail|" + (label == null ? "" : label);
        }
    }

    /** 能力区块：一个可独立渲染的指标（如「当月费用 12.4 USD」「预算剩余 68%」）。 */
    public static final class MetricBlock {
        private final ProviderCapability capability;
        private final String label;
        private final Double amount;
        private final String currency;
        private final Double percent;
        private final String note;
        private final List<DetailLine> details;

        public MetricBlock(ProviderCapability capability, String label, Double amount,
                           String currency, Double percent, String note, List<DetailLine> details) {
            this.capability = capability;
            this.label = label;
            this.amount = amount;
            this.currency = currency;
            this.percent = percent;
            this.note = note;
            this.details = List.copyOf(details);
        }

        public ProviderCapability capability() {
            return capability;
        }

        public String label() {
            return label;
        }

        public Double amount() {
            return amount;
        }

        public String currency() {
            return currency;
        }

        public Double percent() {
            return percent;
        }

        public String note() {
            return note;
        }

        public List<DetailLine> details() {
            return details;
        }

        /**
         * 条目稳定标识（能力名 + 标签），用于「展示条目」配置：勾选/排序以具体数据条目为单位，
         * 不同厂商因返回结构不同自然拥有不同的条目集合（如阿里百炼的每个模型配额、MiniMax 的每个套餐窗口）。
         */
        public String key() {
            return capability.name() + "|" + (label == null ? "" : label);
        }
    }

    private BalanceSnapshot(Builder builder) {
        this.providerId = builder.providerId;
        this.accountId = builder.accountId;
        this.accountName = builder.accountName;
        this.status = builder.status;
        this.primaryAmount = builder.primaryAmount;
        this.primaryCurrency = builder.primaryCurrency;
        this.primaryLabel = builder.primaryLabel;
        this.percent = builder.percent;
        this.details = List.copyOf(builder.details);
        this.message = builder.message;
        this.fetchedAt = builder.fetchedAt != null ? builder.fetchedAt : Instant.now();
        this.capabilities = builder.capabilities == null
                ? java.util.EnumSet.noneOf(ProviderCapability.class)
                : java.util.EnumSet.copyOf(builder.capabilities);
        this.metrics = List.copyOf(builder.metrics);
    }

    public static Builder ok(ProviderId providerId, String accountId, String accountName) {
        return new Builder(providerId, accountId, accountName, Status.OK);
    }

    public static Builder of(ProviderId providerId, String accountId, String accountName, Status status) {
        return new Builder(providerId, accountId, accountName, status);
    }

    public ProviderId providerId() {
        return providerId;
    }

    public String accountId() {
        return accountId;
    }

    public String accountName() {
        return accountName;
    }

    public Status status() {
        return status;
    }

    public Double primaryAmount() {
        return primaryAmount;
    }

    public String primaryCurrency() {
        return primaryCurrency;
    }

    public String primaryLabel() {
        return primaryLabel;
    }

    /** 剩余比例（0-100），无比例数据时为 null。 */
    public Double percent() {
        return percent;
    }

    public List<DetailLine> details() {
        return details;
    }

    /** 本次快照覆盖的能力维度。 */
    public java.util.EnumSet<ProviderCapability> capabilities() {
        return java.util.EnumSet.copyOf(capabilities);
    }

    /** 能力区块列表（各维度独立指标）。 */
    public List<MetricBlock> metrics() {
        return metrics;
    }

    /** 按能力取第一个区块（无则 null）。 */
    public MetricBlock metricOf(ProviderCapability cap) {
        for (MetricBlock m : metrics) {
            if (m.capability() == cap) {
                return m;
            }
        }
        return null;
    }

    public String message() {
        return message;
    }

    public Instant fetchedAt() {
        return fetchedAt;
    }

    public static final class Builder {
        private final ProviderId providerId;
        private final String accountId;
        private final String accountName;
        private Status status;
        private Double primaryAmount;
        private String primaryCurrency;
        private String primaryLabel;
        private Double percent;
        private final List<DetailLine> details = new ArrayList<>();
        private String message;
        private Instant fetchedAt;
        private java.util.EnumSet<ProviderCapability> capabilities;
        private final List<MetricBlock> metrics = new ArrayList<>();

        private Builder(ProviderId providerId, String accountId, String accountName, Status status) {
            this.providerId = providerId;
            this.accountId = accountId;
            this.accountName = accountName;
            this.status = status;
        }

        public Builder status(Status s) {
            this.status = s;
            return this;
        }

        public Builder amount(Double amount) {
            this.primaryAmount = amount;
            return this;
        }

        public Builder currency(String currency) {
            this.primaryCurrency = currency;
            return this;
        }

        public Builder label(String label) {
            this.primaryLabel = label;
            return this;
        }

        public Builder percent(Double p) {
            this.percent = p;
            return this;
        }

        public Builder detail(String label, String value) {
            this.details.add(new DetailLine(label, value));
            return this;
        }

        /** 声明本次快照覆盖的能力维度（通常取厂商声明的能力集合）。 */
        public Builder capabilities(java.util.EnumSet<ProviderCapability> caps) {
            if (caps != null && !caps.isEmpty()) {
                this.capabilities = java.util.EnumSet.copyOf(caps);
            }
            return this;
        }

        /** 追加一个能力区块。 */
        public Builder metric(ProviderCapability cap, String label, Double amount,
                              String currency, Double percent, String note) {
            this.metrics.add(new MetricBlock(cap, label, amount, currency, percent, note,
                    java.util.List.of()));
            if (this.capabilities == null) {
                this.capabilities = java.util.EnumSet.noneOf(ProviderCapability.class);
            }
            this.capabilities.add(cap);
            return this;
        }

        /** 追加带明细的能力区块。 */
        public Builder metricWithDetails(ProviderCapability cap, String label, Double amount,
                                         String currency, Double percent, String note,
                                         List<DetailLine> extra) {
            this.metrics.add(new MetricBlock(cap, label, amount, currency, percent, note, extra));
            if (this.capabilities == null) {
                this.capabilities = java.util.EnumSet.noneOf(ProviderCapability.class);
            }
            this.capabilities.add(cap);
            return this;
        }

        /** 按能力查已追加的区块（无则 null）。 */
        public MetricBlock metricOf(ProviderCapability cap) {
            for (MetricBlock m : metrics) {
                if (m.capability() == cap) {
                    return m;
                }
            }
            return null;
        }

        public Builder message(String msg) {
            this.message = msg;
            return this;
        }

        public Builder fetchedAt(Instant t) {
            this.fetchedAt = t;
            return this;
        }

        public BalanceSnapshot build() {
            return new BalanceSnapshot(this);
        }
    }
}
