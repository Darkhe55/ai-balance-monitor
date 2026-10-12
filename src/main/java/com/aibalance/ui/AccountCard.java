package com.aibalance.ui;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单个账户的余额卡片（双层嵌套结构：外层壳 + 内层芯）。
 */
public final class AccountCard extends VBox {

    /** 高端质感使用的缓动曲线（对应 cubic-bezier(0.16, 1, 0.3, 1)）。 */
    static final Interpolator SPRING = Interpolator.SPLINE(0.16, 1, 0.3, 1);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AccountConfig account;
    private final Consumer<AccountConfig> onEdit;
    private final Consumer<AccountConfig> onDelete;
    private final Consumer<AccountConfig> onRefresh;
    private final boolean lightTheme;
    /** 展示条目保存后的持久化回调（由 MainView 注入，落盘 + 重绘）。 */
    private Runnable onEntriesSaved;

    private final VBox card = new VBox();
    private final Label providerName = new Label();
    private final Label billingTag = new Label();
    private final Label accountName = new Label();
    private final Label amountValue = new Label();
    private final Label amountCurrency = new Label();
    private final Label amountCaption = new Label();
    private final HBox quotaTrack = new HBox();
    private final Region quotaFill = new Region();
    private final DoubleProperty quotaRatio = new SimpleDoubleProperty(0);
    private final VBox detailsBox = new VBox(6);
    private final VBox metricsBox = new VBox(6);
    private final HBox statusPill = new HBox(6);
    private final Region statusDot = new Region();
    private final Label statusText = new Label();
    private final Label updatedLabel = new Label();
    private final Region brandDot = new Region();

    /** 额度耗尽（剩余 ≤0.5%）时的红色脉动动画。 */
    private Timeline pulse;

    private BalanceSnapshot current;

    public AccountCard(AccountConfig account,
                       Consumer<AccountConfig> onEdit,
                       Consumer<AccountConfig> onDelete,
                       Consumer<AccountConfig> onRefresh,
                       boolean lightTheme) {
        this.account = account;
        this.onEdit = onEdit;
        this.onDelete = onDelete;
        this.onRefresh = onRefresh;
        this.lightTheme = lightTheme;

        // 外层壳：与背景隔离的衬垫层，产生"嵌套机件"的层次感
        getStyleClass().add("card-shell");
        setPrefWidth(330);
        setMinWidth(300);
        setMaxWidth(340);

        // 内层芯：真实内容容器
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);

        // 顶栏：品牌色圆点 + 服务名 + 菜单
        ProviderId pid = account.getProviderId();
        brandDot.getStyleClass().add("brand-dot");
        brandDot.setStyle("-fx-background-color: " + pid.getBrandColor() + ";");
        brandDot.setPrefSize(8, 8);

        providerName.setText(pid.getDisplayName());
        providerName.getStyleClass().add("provider-name");

        billingTag.getStyleClass().add("billing-tag");
        billingTag.setTooltip(new javafx.scene.control.Tooltip(
                "计费形态：" + account.effectiveBillingMode().getLabel()
                        + "（" + account.effectiveBillingMode().getDescription() + "）"));
        billingTag.setText(account.effectiveBillingMode().getLabel());

        Button menu = new Button("⋯");
        menu.getStyleClass().add("card-menu");
        menu.setCursor(Cursor.HAND);
        menu.setTooltip(new javafx.scene.control.Tooltip("更多操作（立即刷新 / 编辑 / 删除等）"));
        menu.setOnAction(e -> showMenu(menu));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, brandDot, providerName, billingTag, spacer, menu);
        top.setAlignment(Pos.CENTER_LEFT);

        // 账户名
        accountName.setText(account.getName());
        accountName.getStyleClass().add("account-name");

        // 金额行：主值使用中性高亮，不随品牌色跳色
        amountValue.getStyleClass().add("amount-value");
        amountCurrency.getStyleClass().add("amount-currency");
        amountCaption.getStyleClass().add("amount-caption");
        amountCaption.setMaxWidth(280);
        // 错误/说明信息可能较长：必须换行显示完整，否则超出 280px 的部分被省略号截断
        amountCaption.setWrapText(true);
        HBox amountRow = new HBox(8, amountValue, amountCurrency);
        amountRow.setAlignment(Pos.BASELINE_LEFT);
        VBox amountBox = new VBox(1, amountRow, amountCaption);

        // 剩余额度进度条：percent 驱动，额度耗尽（≤0.5%）时红色脉动
        quotaTrack.getStyleClass().add("quota-track");
        quotaTrack.setMinHeight(6);
        quotaTrack.setPrefHeight(6);
        quotaTrack.setMaxHeight(6);
        quotaTrack.setVisible(false);
        quotaTrack.setManaged(false);
        quotaFill.getStyleClass().add("quota-fill");
        quotaFill.prefWidthProperty().bind(quotaTrack.widthProperty().multiply(quotaRatio));
        quotaTrack.getChildren().add(quotaFill);

        // 能力区块（balance/usage/cost/quota/spendLimit 各维度独立指标，按能力自然适配）
        metricsBox.getStyleClass().add("card-metrics");
        metricsBox.setVisible(false);
        metricsBox.setManaged(false);

        // 明细（仅组首一条分隔线，不逐行描边）
        detailsBox.getStyleClass().add("card-details");

        // 页脚
        statusDot.setPrefSize(6, 6);
        statusDot.getStyleClass().add("status-dot");
        statusText.getStyleClass().add("status-text");
        statusPill.getChildren().addAll(statusDot, statusText);
        statusPill.setAlignment(Pos.CENTER_LEFT);
        statusPill.setPadding(new Insets(2.5, 9, 2.5, 9));
        statusPill.getStyleClass().add("status-pill");

        updatedLabel.getStyleClass().add("card-time");
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, statusPill, footSpacer, updatedLabel);
        footer.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(top, accountName, amountBox, metricsBox, quotaTrack, detailsBox, footer);
        card.setSpacing(9);
        getChildren().add(card);

        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                onEdit.accept(account);
            }
        });
    }

    public String accountId() {
        return account.getId();
    }

    /** 注入展示条目保存后的持久化回调（MainView 调用以落盘并重绘卡片）。 */
    public void setOnEntriesSaved(Runnable r) {
        this.onEntriesSaved = r;
    }

    public void update(BalanceSnapshot snap) {
        this.current = snap;

        statusPill.getStyleClass().removeAll("badge-ok", "badge-warn", "badge-err", "badge-muted");
        switch (snap.status()) {
            case OK -> {
                statusPill.getStyleClass().add("badge-ok");
                statusText.setText("在线");
            }
            case UNAUTHORIZED -> {
                statusPill.getStyleClass().add("badge-warn");
                statusText.setText("Key 无效");
            }
            case TIMEOUT -> {
                statusPill.getStyleClass().add("badge-err");
                statusText.setText("请求超时");
            }
            case ERROR -> {
                statusPill.getStyleClass().add("badge-err");
                statusText.setText("查询失败");
            }
            default -> {
                statusPill.getStyleClass().add("badge-muted");
                statusText.setText("未配置");
            }
        }

        if (snap.primaryAmount() == null) {
            amountValue.setText("未获取");
            amountCurrency.setText("");
        } else {
            String cur = snap.primaryCurrency();
            if ("tokens".equalsIgnoreCase(cur)) {
                amountValue.setText(String.format("%,d", snap.primaryAmount().longValue()));
                amountCurrency.setText("tokens");
            } else {
                amountValue.setText(String.format("%.2f", snap.primaryAmount()));
                amountCurrency.setText(cur == null ? "" : cur);
            }
        }
        amountCaption.setText(buildCaption(snap));
        updateQuotaBar(snap);
        updateMetrics(snap);

        detailsBox.getChildren().clear();
        List<BalanceSnapshot.DetailLine> details = filterDetails(snap.details(), account.getDisplayEntries());
        if (details.size() > 5) {
            details = details.subList(0, 5);
        }
        for (BalanceSnapshot.DetailLine line : details) {
            Label k = new Label(line.label());
            k.getStyleClass().add("detail-label");
            Label v = new Label(line.value());
            v.getStyleClass().add("detail-value");
            v.setMaxWidth(200);
            v.setWrapText(true);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox row = new HBox(8, k, sp, v);
            row.setAlignment(Pos.CENTER_LEFT);
            detailsBox.getChildren().add(row);
        }
        if (details.isEmpty()) {
            Label none = new Label("暂无明细");
            none.getStyleClass().add("detail-label");
            detailsBox.getChildren().add(none);
        }

        updatedLabel.setText("更新于 " + TIME_FMT.format(snap.fetchedAt()));

        // 内容更新缓和过渡：明细与金额轻微淡入（不打断刷新节奏）
        javafx.animation.FadeTransition ft =
                new javafx.animation.FadeTransition(Duration.millis(200), detailsBox);
        ft.setFromValue(0.45);
        ft.setToValue(1.0);
        ft.setInterpolator(SPRING);
        ft.play();
    }

    /** 能力区块渲染：每个 MetricBlock 一行（能力色点 + 标签 + 数值），percent 时追加小进度条。
     *  按账户「展示条目」配置（具体数据条目 key 列表）过滤 + 排序；未配置时展示全部，顺序按快照返回。 */
    private void updateMetrics(BalanceSnapshot snap) {
        List<BalanceSnapshot.MetricBlock> metrics = filterMetrics(snap.metrics(), account.getDisplayEntries());
        if (metrics == null || metrics.isEmpty()) {
            metricsBox.setVisible(false);
            metricsBox.setManaged(false);
            metricsBox.getChildren().clear();
            return;
        }
        metricsBox.getChildren().clear();
        for (BalanceSnapshot.MetricBlock m : metrics) {
            VBox block = new VBox(3);
            block.getStyleClass().add("metric-block");

            Region dot = new Region();
            dot.getStyleClass().add("metric-dot");
            dot.setStyle("-fx-background-color: " + m.capability().getColor() + ";");
            dot.setPrefSize(7, 7);

            Label cap = new Label(m.capability().getLabel());
            cap.getStyleClass().add("metric-cap");
            Label lab = new Label(m.label());
            lab.getStyleClass().add("detail-label");
            lab.setMaxWidth(150);
            lab.setWrapText(true);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label val = new Label(formatMetric(m));
            val.getStyleClass().add("detail-value");
            val.setMaxWidth(150);
            val.setWrapText(true);
            HBox head = new HBox(6, dot, cap, lab, sp, val);
            head.setAlignment(Pos.CENTER_LEFT);
            block.getChildren().add(head);

            if (m.percent() != null) {
                HBox track = new HBox();
                track.getStyleClass().add("quota-track");
                track.setMinHeight(5);
                track.setPrefHeight(5);
                track.setMaxHeight(5);
                Region fill = new Region();
                fill.getStyleClass().add("quota-fill");
                double ratio = Math.max(0, Math.min(100, m.percent())) / 100.0;
                fill.prefWidthProperty().bind(track.widthProperty().multiply(ratio));
                boolean exhausted = m.percent() <= 0.5;
                if (exhausted) {
                    track.getStyleClass().add("quota-track-empty");
                    fill.getStyleClass().add("quota-fill-empty");
                }
                track.getChildren().add(fill);
                javafx.scene.control.Tooltip.install(track,
                        new javafx.scene.control.Tooltip(m.label() + " 剩余 "
                                + String.format("%.0f%%", m.percent())));
                block.getChildren().add(track);
            }
            if (m.note() != null && !m.note().isBlank()) {
                Label note = new Label(m.note());
                note.getStyleClass().add("detail-label");
                note.setWrapText(true);
                note.setMaxWidth(280);
                block.getChildren().add(note);
            }
            metricsBox.getChildren().add(block);
        }
        metricsBox.setVisible(true);
        metricsBox.setManaged(true);
    }

    private String formatMetric(BalanceSnapshot.MetricBlock m) {
        if (m.amount() == null) {
            return "未获取";
        }
        String cur = m.currency();
        if ("tokens".equalsIgnoreCase(cur)) {
            return String.format("%,d tokens", m.amount().longValue());
        }
        return String.format("%.2f %s", m.amount(), cur == null ? "" : cur);
    }

    /**
     * 按「展示条目」配置（条目 key 列表）过滤 + 排序能力区块：
     * 未配置返回全部；配置后仅返回勾选条目且保持用户顺序；若配置与实际数据完全脱节（如接口结构变化）回退显示全部。
     * 静态包内方法以便单元测试。
     */
    static List<BalanceSnapshot.MetricBlock> filterMetrics(
            List<BalanceSnapshot.MetricBlock> metrics, java.util.List<String> configuredKeys) {
        if (metrics == null || metrics.isEmpty()) {
            return metrics == null ? java.util.List.of() : metrics;
        }
        if (configuredKeys == null || configuredKeys.isEmpty()) {
            return metrics;
        }
        java.util.Map<String, BalanceSnapshot.MetricBlock> byKey = new java.util.HashMap<>();
        for (BalanceSnapshot.MetricBlock m : metrics) {
            byKey.put(m.key(), m);
        }
        List<BalanceSnapshot.MetricBlock> ordered = new java.util.ArrayList<>();
        for (String k : configuredKeys) {
            if (k == null) {
                continue;
            }
            BalanceSnapshot.MetricBlock m = byKey.get(k.strip());
            if (m != null && !ordered.contains(m)) {
                ordered.add(m);
            }
        }
        return ordered.isEmpty() ? metrics : ordered;
    }

    /**
     * 按「展示条目」配置（条目 key 列表，含 "detail|标签"）过滤 + 排序明细行：
     * 未配置返回全部；配置后仅返回勾选明细且保持用户顺序；配置与实际数据脱节时回退显示全部。
     * 静态包内方法以便单元测试。
     */
    static List<BalanceSnapshot.DetailLine> filterDetails(
            List<BalanceSnapshot.DetailLine> details, java.util.List<String> configuredKeys) {
        if (details == null || details.isEmpty()) {
            return details == null ? java.util.List.of() : details;
        }
        if (configuredKeys == null || configuredKeys.isEmpty()) {
            return details;
        }
        java.util.Map<String, BalanceSnapshot.DetailLine> byKey = new java.util.HashMap<>();
        for (BalanceSnapshot.DetailLine d : details) {
            byKey.put(d.key(), d);
        }
        List<BalanceSnapshot.DetailLine> ordered = new java.util.ArrayList<>();
        for (String k : configuredKeys) {
            if (k == null) {
                continue;
            }
            BalanceSnapshot.DetailLine d = byKey.get(k.strip());
            if (d != null && !ordered.contains(d)) {
                ordered.add(d);
            }
        }
        return ordered.isEmpty() ? details : ordered;
    }

    private String buildCaption(BalanceSnapshot snap) {
        String label = snap.primaryLabel();
        String msg = snap.message();
        if (label != null && !label.isBlank()) {
            return msg == null || msg.isBlank() ? label : label + "，" + msg;
        }
        return msg == null || msg.isBlank() ? "" : msg;
    }

    /** 剩余额度进度条：percent 有值时显示并填充；耗尽（≤0.5%）切换红色脉动样式。 */
    private void updateQuotaBar(BalanceSnapshot snap) {
        Double pct = snap.percent();
        if (pct == null) {
            quotaTrack.setVisible(false);
            quotaTrack.setManaged(false);
            stopPulse();
            return;
        }
        double ratio = Math.max(0, Math.min(100, pct)) / 100.0;
        quotaRatio.set(ratio);
        quotaTrack.setVisible(true);
        quotaTrack.setManaged(true);
        javafx.scene.control.Tooltip.install(quotaTrack,
                new javafx.scene.control.Tooltip(String.format("剩余额度 %.0f%%", pct)));
        boolean exhausted = pct <= 0.5;
        quotaTrack.getStyleClass().remove("quota-track-empty");
        quotaFill.getStyleClass().remove("quota-fill-empty");
        if (exhausted) {
            // 额度耗尽：整条轨道切换红色警示底 + 红色填充 + 脉动（0% 时填充宽度为 0，
            // 依靠轨道底色仍能显示"耗尽"状态）
            quotaTrack.getStyleClass().add("quota-track-empty");
            quotaFill.getStyleClass().add("quota-fill-empty");
            startPulse();
        } else {
            stopPulse();
        }
    }

    private void startPulse() {
        if (pulse != null) {
            return;
        }
        pulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(quotaTrack.opacityProperty(), 0.55, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(700),
                        new KeyValue(quotaTrack.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(quotaTrack.opacityProperty(), 0.55, Interpolator.EASE_BOTH)));
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.stop();
            pulse = null;
            quotaTrack.setOpacity(1.0);
        }
    }

    /** 卡片入场动画：淡入 + 上移（SPLINE 曲线）。 */
    public void playEntrance() {
        setOpacity(0);
        setTranslateY(12);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(opacityProperty(), 0, SPRING),
                        new KeyValue(translateYProperty(), 12, SPRING)),
                new KeyFrame(Duration.millis(440),
                        new KeyValue(opacityProperty(), 1, SPRING),
                        new KeyValue(translateYProperty(), 0, SPRING)));
        tl.play();
    }

    private void showMenu(Button anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem refresh = new MenuItem("立即刷新");
        refresh.setOnAction(e -> onRefresh.accept(account));
        MenuItem entries = new MenuItem("展示条目…");
        entries.setOnAction(e -> showEntriesDialog());
        MenuItem edit = new MenuItem("编辑账户");
        edit.setOnAction(e -> onEdit.accept(account));
        MenuItem del = new MenuItem("删除账户");
        del.setOnAction(e -> onDelete.accept(account));
        MenuItem toggle = new MenuItem(account.isEnabled() ? "停用账户" : "启用账户");
        toggle.setOnAction(e -> {
            account.setEnabled(!account.isEnabled());
            onEdit.accept(account);
        });
        menu.getItems().addAll(refresh, entries, edit, toggle, new SeparatorMenuItem(), del);
        // 弹层显示前注入主题样式（ContextMenu 是独立弹层场景，默认走 Modena 皮肤）
        menu.setOnShowing(e -> applyMenuTheme(menu));
        // 用 Side.BOTTOM 标准锚定而非手工屏幕坐标：任何滚动/缩放/动画状态下都能弹在屏幕内
        menu.show(anchor, Side.BOTTOM, 0, 6);
    }

    /** 「展示条目」：勾选要在卡片展示的具体数据条目（基于最近一次快照，每厂商条目自然不同）+ 调整顺序，
     *  保存后立即持久化并重绘卡片。 */
    private void showEntriesDialog() {
        javafx.scene.Node anchor = this;
        java.util.Optional<java.util.List<String>> result =
                DisplayEntriesDialog.show(anchor.getScene() == null ? null : anchor.getScene().getWindow(),
                        account, current, lightTheme);
        result.ifPresent(keys -> {
            account.setDisplayEntries(keys);
            if (onEntriesSaved != null) {
                onEntriesSaved.run();
            } else {
                onRefresh.accept(account);
            }
        });
    }

    /** 为菜单弹层场景附加与主界面一致的主题样式表。 */
    private void applyMenuTheme(ContextMenu menu) {
        javafx.scene.Scene ms = menu.getScene();
        if (ms == null) {
            return;
        }
        String base = AccountCard.class.getResource("/com/aibalance/ui/app.css").toExternalForm();
        if (!ms.getStylesheets().contains(base)) {
            ms.getStylesheets().add(base);
        }
        if (lightTheme) {
            String light = AccountCard.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm();
            if (!ms.getStylesheets().contains(light)) {
                ms.getStylesheets().add(light);
            }
        }
    }

    public BalanceSnapshot current() {
        return current;
    }
}
