package com.aibalance.ui;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.service.BalanceMonitor;
import com.aibalance.service.ConfigStore;
import com.aibalance.service.HttpSupport;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 主仪表盘：头部工具栏 + 账户卡片流 + 底部状态栏。
 */
public final class MainView extends BorderPane {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ConfigStore configStore;
    private final BalanceMonitor monitor;
    private final HttpSupport http;

    private final FlowPane cardsPane = new FlowPane();
    private final Map<String, AccountCard> cards = new HashMap<>();
    private final Label statusLabel = new Label();
    private final Label footerRight = new Label();
    private final Button refreshButton = new Button("立即刷新");
    private final SpinnerIcon spinner = new SpinnerIcon();
    private final Button settingsButton = new Button("设置");
    private final Button addButton = new Button("+ 添加账户");
    private final Button ballButton = new Button("悬浮球");
    private final Button themeToggle = new Button();
    private final VBox emptyState = new VBox();
    private final Label refreshTimeLabel = new Label();

    private FloatingBallView ballView;

    private boolean lightTheme;

    private final StackPane refreshProgress = new StackPane();
    private final Region progressTrack = new Region();
    private final Region progressBar = new Region();
    private final Timeline progressAnim = new Timeline();
    private final FadeTransition cardsDim = new FadeTransition();

    private final Timeline countdown = new Timeline();
    private long nextRefreshEpoch = 0;

    public MainView(ConfigStore configStore, BalanceMonitor monitor, HttpSupport http, boolean lightTheme) {
        this.configStore = configStore;
        this.monitor = monitor;
        this.http = http;
        this.lightTheme = lightTheme;
        this.cachedAccounts = new ArrayList<>(configStore.load().getAccounts());
        this.themeToggle.setText(lightTheme ? "浅色" : "深色");

        buildHeader();
        buildBody();
        buildFooter();
        applyPrefsToControls();
        updateEmptyState();
        refreshStatusBar();
        startCountdown();
    }

    /** 当前是否为亮色主题（供对话框、卡片菜单同步主题）。 */
    public boolean isLightTheme() {
        return lightTheme;
    }

    /** 快照验证用：向内存账户列表追加种子账户（不持久化）。 */
    void seedSnapshotAccounts(java.util.List<AccountConfig> seeds) {
        cachedAccounts.addAll(seeds);
        monitor.setAccounts(cachedAccounts);
    }

    /**
     * 快照验证钩子（--snapshot-quota）：把全部启用卡片覆盖为指定的剩余额度百分比快照，
     * 用于验证进度条填充与额度耗尽特殊样式。不写配置、不发请求。
     */
    void seedQuotaSnapshot(double pct) {
        for (AccountConfig acc : monitorAccounts()) {
            if (!acc.isEnabled()) {
                continue;
            }
            AccountCard card = cards.get(acc.getId());
            if (card == null) {
                continue;
            }
            BalanceSnapshot s = BalanceSnapshot.ok(acc.getProviderId(), acc.getId(), acc.getName())
                    .label("套餐额度").currency("%")
                    .amount(pct).percent(pct)
                    .message(String.format("剩余 %.0f%%", pct))
                    .build();
            card.update(s);
        }
    }

    /** 注入悬浮球视图（由 App 装配；悬浮球按钮与恢复回调由此联动）。 */
    public void setBallView(FloatingBallView ballView) {
        this.ballView = ballView;
    }

    /** 收集悬浮球缩略面板所需的账户行数据（名称/金额/剩余比例/状态）。 */
    public List<FloatingBallView.BallRow> collectBallRows() {
        Map<String, BalanceSnapshot> snaps = monitor.snapshotView();
        List<FloatingBallView.BallRow> out = new ArrayList<>();
        for (AccountConfig acc : monitorAccounts()) {
            ProviderId pid = acc.getProviderId();
            BalanceSnapshot s = snaps.get(acc.getId());
            BalanceSnapshot.Status status;
            String statusText;
            Double percent;
            if (s == null) {
                status = BalanceSnapshot.Status.NOT_CONFIGURED;
                statusText = "未配置";
                percent = null;
            } else {
                status = s.status();
                percent = s.percent();
                statusText = switch (s.status()) {
                    case OK -> "在线";
                    case UNAUTHORIZED -> "Key 无效";
                    case TIMEOUT -> "请求超时";
                    case ERROR -> "查询失败";
                    default -> "未配置";
                };
            }
            String amountText;
            if (s == null || s.primaryAmount() == null) {
                amountText = status == BalanceSnapshot.Status.NOT_CONFIGURED ? "未配置" : "未获取";
            } else if ("tokens".equalsIgnoreCase(s.primaryCurrency())) {
                amountText = String.format("%,d", s.primaryAmount().longValue()) + " tokens";
            } else {
                String cur = s.primaryCurrency();
                amountText = String.format("%.2f", s.primaryAmount()) + (cur == null || cur.isBlank() ? "" : " " + cur);
            }
            String caption = s == null ? null : ballCaption(s);
            out.add(new FloatingBallView.BallRow(acc.getName(), pid.getDisplayName(),
                    pid.getBrandColor(), status, statusText, amountText, percent, caption,
                    s == null ? java.util.List.of() : extractQuotaRows(s)));
        }
        return out;
    }

    /**
     * 从明细行提取多额度窗口（不同时间段套餐）：每个"窗口/模型"明细行对应一个配额行，
     * 同名"…重置"行作为下一次刷新时间（重置行可能出现在窗口行之后）。
     */
    static java.util.List<FloatingBallView.QuotaRow> extractQuotaRows(BalanceSnapshot s) {
        java.util.List<FloatingBallView.QuotaRow> out = new java.util.ArrayList<>();
        if (s == null || s.details() == null) {
            return out;
        }
        // 第一遍：收集 <name>重置 → 时间（name = label 去掉"重置"）
        java.util.Map<String, String> resets = new java.util.HashMap<>();
        for (BalanceSnapshot.DetailLine d : s.details()) {
            String lbl = d.label();
            String val = d.value();
            if (lbl == null || val == null || !lbl.endsWith("重置")) {
                continue;
            }
            resets.put(lbl.substring(0, lbl.length() - 2), formatResetTime(val));
        }
        // 第二遍：窗口/模型行（排除"…重置"行），按 name 匹配重置
        for (BalanceSnapshot.DetailLine d : s.details()) {
            String lbl = d.label();
            String val = d.value();
            if (lbl == null || val == null || lbl.endsWith("重置")) {
                continue;
            }
            if (lbl.contains("窗口")) {
                String name = lbl.substring(0, lbl.length() - 2);
                String reset = resets.get(name);
                if (reset == null) {
                    reset = resets.get(name + "窗口");
                }
                out.add(new FloatingBallView.QuotaRow(lbl, parseRemainPercent(val), reset));
            } else if (lbl.startsWith("模型")) {
                out.add(new FloatingBallView.QuotaRow(lbl, parseRemainPercent(val), null));
            }
        }
        return out;
    }

    /** 提取明细值中第一个"剩余 X%"的 X（窗口/模型行的剩余百分比）。 */
    private static Double parseRemainPercent(String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("剩余\\s*([\\d.]+)\\s*%").matcher(value);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 重置时间归一化为 HH:mm（epoch 毫秒 / ISO 时间 / 已格式化文本兼容）。 */
    private static String formatResetTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.matches("\\d{12,13}")) {
                return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(Long.parseLong(raw)));
            }
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.parse(
                                raw.replace(' ', 'T') + (raw.contains("Z") ? "" : "Z")));
            }
        } catch (Exception ignored) {
            // 已是可显示文本则原样使用
        }
        return raw;
    }

    private static String ballCaption(BalanceSnapshot s) {
        String label = s.primaryLabel();
        String msg = s.message();
        String base;
        if (label != null && !label.isBlank()) {
            base = msg == null || msg.isBlank() ? label : label + "，" + msg;
        } else {
            base = msg == null || msg.isBlank() ? "" : msg;
        }
        // 附加能力区块摘要（如 OpenAI Admin：费用 $12.34 · 用量 430,000 tokens）
        StringBuilder sb = new StringBuilder(base);
        for (BalanceSnapshot.MetricBlock m : s.metrics()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(m.label()).append(" ");
            if (m.amount() == null) {
                sb.append("未获取");
            } else if ("tokens".equalsIgnoreCase(m.currency())) {
                sb.append(String.format("%,d tokens", m.amount().longValue()));
            } else {
                sb.append(String.format("%.2f %s", m.amount(),
                        m.currency() == null ? "" : m.currency()));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- header

    private void buildHeader() {
        VBox titleBox = new VBox(2);
        Label title = new Label("AI 余额监控");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("实时掌握主流 AI 服务的 API 余额与用量配额");
        subtitle.getStyleClass().add("app-subtitle");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        spinner.setVisible(false);
        spinner.setManaged(false);
        StackPane spinnerWrap = new StackPane(spinner);
        spinnerWrap.setPadding(new Insets(0, 8, 0, 0));

        refreshButton.getStyleClass().add("button-primary");
        refreshButton.setOnAction(e -> refreshNow());

        addButton.getStyleClass().add("button-outline");
        addButton.setOnAction(e -> openAddDialog());

        ballButton.getStyleClass().add("button-outline");
        ballButton.setTooltip(new Tooltip("最小化主窗口为桌面悬浮球；点击悬浮球展开缩略面板，随时查看余额与额度"));
        ballButton.setOnAction(e -> {
            if (ballView != null) {
                ballView.enterFloating();
            }
        });

        settingsButton.getStyleClass().add("button-ghost");
        settingsButton.setTooltip(new Tooltip("全局设置：自动刷新开关、刷新间隔、网络模式（跟随系统/直连）、悬浮球自动收回"));
        settingsButton.setOnAction(e -> openPreferences());

        themeToggle.getStyleClass().add("theme-toggle");
        themeToggle.setTooltip(new Tooltip("切换明暗模式"));
        themeToggle.setOnAction(e -> toggleTheme());

        HBox controls = new HBox(8, spinnerWrap, settingsButton,
                themeToggle, refreshButton, addButton, ballButton);
        controls.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(14, titleBox, spacer, controls);
        header.setPadding(new Insets(14, 28, 14, 28));
        header.getStyleClass().add("app-header");
        setTop(header);
    }

    // ---------------------------------------------------------------- body

    private void buildBody() {
        cardsPane.setHgap(16);
        cardsPane.setVgap(16);
        cardsPane.setPadding(new Insets(12, 28, 24, 28));

        ScrollPane scroll = new ScrollPane(cardsPane);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("app-scroll");
        cardsPane.prefWrapLengthProperty().bind(
                scroll.widthProperty().subtract(56));

        emptyState.setAlignment(Pos.CENTER);
        emptyState.setSpacing(14);
        emptyState.getChildren().addAll(buildEmptyIllustration(),
                buildEmptyLabel("还没有监控账户", "empty-title"),
                buildEmptyLabel("点击右上角「+ 添加账户」，接入你的 API Key 开始监控", "empty-hint"));

        StackPane body = new StackPane(scroll, emptyState);

        // 刷新进度条：位于内容区顶部，仅刷新时可见
        progressTrack.getStyleClass().add("refresh-track");
        progressBar.getStyleClass().add("refresh-bar");
        refreshProgress.getChildren().addAll(progressTrack, progressBar);
        refreshProgress.setAlignment(Pos.TOP_LEFT);
        refreshProgress.getStyleClass().add("refresh-progress");
        refreshProgress.setVisible(false);
        refreshProgress.setManaged(false);
        progressBar.prefWidthProperty().bind(progressTrack.widthProperty().multiply(0.38));
        progressBar.prefHeightProperty().bind(progressTrack.heightProperty());
        progressBar.maxWidthProperty().bind(progressBar.prefWidthProperty());
        progressBar.maxHeightProperty().bind(progressBar.prefHeightProperty());
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(progressTrack.widthProperty());
        clip.heightProperty().bind(progressTrack.heightProperty());
        refreshProgress.setClip(clip);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox center = new VBox(refreshProgress, body);
        center.getStyleClass().add("app-body");
        setCenter(center);
    }

    private Node buildEmptyIllustration() {
        StackPane illus = new StackPane();
        illus.getStyleClass().add("empty-illus");
        illus.setMaxSize(96, 96);
        Region ringOuter = new Region();
        ringOuter.getStyleClass().add("empty-ring-outer");
        ringOuter.setPrefSize(84, 84);
        ringOuter.setMaxSize(84, 84);
        Region ringMid = new Region();
        ringMid.getStyleClass().add("empty-ring-mid");
        ringMid.setPrefSize(54, 54);
        ringMid.setMaxSize(54, 54);
        Region core = new Region();
        core.getStyleClass().add("empty-core");
        core.setPrefSize(20, 20);
        core.setMaxSize(20, 20);
        illus.getChildren().addAll(ringOuter, ringMid, core);

        // 波动动态：外层圆环缓慢呼吸（缩放），内层圆环随呼吸反向轻微移动，
        // 核心圆点做轻微脉冲（透明度），整体形成"待命呼吸"感。
        Timeline breath = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(ringOuter.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringOuter.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(core.opacityProperty(), 0.62, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1600),
                        new KeyValue(ringOuter.scaleXProperty(), 1.08, Interpolator.EASE_BOTH),
                        new KeyValue(ringOuter.scaleYProperty(), 1.08, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleXProperty(), 0.94, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleYProperty(), 0.94, Interpolator.EASE_BOTH),
                        new KeyValue(core.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(3200),
                        new KeyValue(ringOuter.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringOuter.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(ringMid.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                        new KeyValue(core.opacityProperty(), 0.62, Interpolator.EASE_BOTH)));
        breath.setCycleCount(Timeline.INDEFINITE);

        // 涟漪：外层圆环按 4 秒间隔做一次"扩展淡出"的透明描边脉冲，
        // 与呼吸叠加，形成缓慢的波纹感。
        Region ripple = new Region();
        ripple.getStyleClass().add("empty-ripple");
        ripple.setPrefSize(84, 84);
        ripple.setMaxSize(84, 84);
        ripple.setScaleX(0.82);
        ripple.setScaleY(0.82);
        ripple.setOpacity(0.0);
        illus.getChildren().add(0, ripple);
        Timeline rippleAnim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(ripple.opacityProperty(), 0.0, Interpolator.EASE_OUT),
                        new KeyValue(ripple.scaleXProperty(), 0.82, Interpolator.EASE_OUT),
                        new KeyValue(ripple.scaleYProperty(), 0.82, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(420),
                        new KeyValue(ripple.opacityProperty(), 0.5, Interpolator.EASE_OUT),
                        new KeyValue(ripple.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                        new KeyValue(ripple.scaleYProperty(), 1.0, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(1600),
                        new KeyValue(ripple.opacityProperty(), 0.0, Interpolator.EASE_IN),
                        new KeyValue(ripple.scaleXProperty(), 1.22, Interpolator.EASE_IN),
                        new KeyValue(ripple.scaleYProperty(), 1.22, Interpolator.EASE_IN)));
        rippleAnim.setCycleCount(Timeline.INDEFINITE);
        rippleAnim.play();
        breath.play();
        return illus;
    }

    private Label buildEmptyLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        return l;
    }

    // ---------------------------------------------------------------- footer

    private void buildFooter() {
        statusLabel.getStyleClass().add("footer-status");
        refreshTimeLabel.getStyleClass().add("footer-status");
        footerRight.getStyleClass().add("footer-status");
        HBox.setHgrow(refreshTimeLabel, Priority.ALWAYS);

        HBox footer = new HBox(20, statusLabel, refreshTimeLabel, footerRight);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(11, 28, 12, 28));
        footer.getStyleClass().add("app-footer");
        setBottom(footer);
    }

    // ---------------------------------------------------------- public API

    /** 立即刷新全部账户（UI 触发）。 */
    public void refreshNow() {
        if (monitor.isRefreshing()) {
            return;
        }
        spinner.setVisible(true);
        spinner.setManaged(true);
        refreshButton.setDisable(true);
        dimCards(0.85, 160);
        showProgress();
        monitor.refreshAll(() -> javafx.application.Platform.runLater(() -> {
            spinner.setVisible(false);
            spinner.setManaged(false);
            refreshButton.setDisable(false);
            dimCards(1.0, 260);
            hideProgress();
            updateCards();
            refreshStatusBar();
        }));
    }

    /** 刷新反馈：卡片轻微变暗，完成后恢复。 */
    private void dimCards(double to, long millis) {
        cardsDim.stop();
        cardsDim.setNode(cardsPane);
        cardsDim.setFromValue(cardsPane.getOpacity());
        cardsDim.setToValue(to);
        cardsDim.setDuration(javafx.util.Duration.millis(millis));
        cardsDim.play();
    }

    /** 顶部细进度条：刷新期间往复扫描。 */
    private void showProgress() {
        refreshProgress.setVisible(true);
        refreshProgress.setManaged(true);
        double w = progressTrack.getWidth();
        if (w <= 0) {
            w = 320;
        }
        progressBar.setTranslateX(-w * 0.38);
        progressAnim.stop();
        progressAnim.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(progressBar.translateXProperty(), -w * 0.38, Interpolator.LINEAR)),
                new KeyFrame(Duration.millis(1300),
                        new KeyValue(progressBar.translateXProperty(), w, Interpolator.LINEAR)));
        progressAnim.setCycleCount(Timeline.INDEFINITE);
        progressAnim.play();
    }

    private void hideProgress() {
        progressAnim.stop();
        refreshProgress.setVisible(false);
        refreshProgress.setManaged(false);
    }

    public void updateCards() {
        Map<String, BalanceSnapshot> snaps = monitor.snapshotView();
        List<AccountConfig> accs = monitorAccounts();

        List<String> toRemove = new ArrayList<>(cards.keySet());
        for (AccountConfig acc : accs) {
            if (!acc.isEnabled()) {
                continue;
            }
            toRemove.remove(acc.getId());
            AccountCard card = cards.get(acc.getId());
            BalanceSnapshot snap = snaps.get(acc.getId());
            if (card == null) {
                card = new AccountCard(acc, this::openEditDialog, this::deleteAccount,
                        this::refreshOne, lightTheme);
                card.setOnEntriesSaved(this::persist);
                cards.put(acc.getId(), card);
                cardsPane.getChildren().add(card);
                if (snap != null) {
                    card.update(snap);
                }
                card.playEntrance();
            } else if (snap != null) {
                card.update(snap);
            }
        }
        for (String id : toRemove) {
            AccountCard gone = cards.remove(id);
            if (gone != null) {
                cardsPane.getChildren().remove(gone);
            }
        }
        updateEmptyState();
    }

    /** 只更新指定账户的卡片（单个快照异步返回时调用，先获取先显示）。 */
    public void updateCardForAccount(String accountId) {
        AccountCard card = cards.get(accountId);
        if (card == null) {
            // 卡片还没创建，走完整刷新
            updateCards();
            return;
        }
        BalanceSnapshot snap = monitor.snapshotView().get(accountId);
        if (snap != null) {
            card.update(snap);
        }
    }

    private void updateEmptyState() {
        boolean hasActive = monitorAccounts().stream().anyMatch(AccountConfig::isEnabled);
        boolean show = !hasActive;
        if (emptyState.isVisible() == show) {
            return;
        }
        // 空状态出现/消失使用缓和过渡（淡入淡出 + 轻微上移/下移）
        if (show) {
            emptyState.setVisible(true);
            emptyState.setManaged(true);
            emptyState.setOpacity(0);
            emptyState.setTranslateY(8);
            FadeTransition ft = new FadeTransition(Duration.millis(360), emptyState);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.setInterpolator(Interpolator.EASE_BOTH);
            ft.play();
            javafx.animation.TranslateTransition tt =
                    new javafx.animation.TranslateTransition(Duration.millis(360), emptyState);
            tt.setFromY(8);
            tt.setToY(0);
            tt.setInterpolator(Interpolator.EASE_BOTH);
            tt.play();
        } else {
            FadeTransition ft = new FadeTransition(Duration.millis(200), emptyState);
            ft.setFromValue(emptyState.getOpacity());
            ft.setToValue(0);
            ft.setInterpolator(Interpolator.EASE_BOTH);
            ft.setOnFinished(e -> {
                emptyState.setVisible(false);
                emptyState.setManaged(false);
            });
            ft.play();
        }
    }

    public void refreshStatusBar() {
        Map<String, BalanceSnapshot> snaps = monitor.snapshotView();
        long ok = snaps.values().stream()
                .filter(s -> s.status() == BalanceSnapshot.Status.OK).count();
        long bad = snaps.values().stream()
                .filter(s -> s.status() == BalanceSnapshot.Status.UNAUTHORIZED
                        || s.status() == BalanceSnapshot.Status.ERROR
                        || s.status() == BalanceSnapshot.Status.TIMEOUT).count();
        long unset = snaps.values().stream()
                .filter(s -> s.status() == BalanceSnapshot.Status.NOT_CONFIGURED).count();
        long total = monitorAccounts().stream().filter(AccountConfig::isEnabled).count();
        statusLabel.setText(String.format("共 %d 个账户，正常 %d，异常 %d，未配置 %d",
                total, ok, bad, unset));

        long last = monitor.lastRefreshAt();
        footerRight.setText(last == 0 ? "尚未刷新"
                : "最近刷新 " + TIME_FMT.format(Instant.ofEpochMilli(last)));
    }

    private void startCountdown() {
        countdown.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), e -> {
            long iv = monitor.refreshIntervalSeconds();
            long now = System.currentTimeMillis();
            if (monitor.lastRefreshAt() == 0) {
                refreshTimeLabel.setText("等待首次刷新…");
            } else if (iv <= 0) {
                refreshTimeLabel.setText("自动刷新已关闭（手动模式）");
            } else {
                long next = nextRefreshEpoch;
                long remain = Math.max(0, (next - now) / 1000);
                refreshTimeLabel.setText("下次刷新 " + remain + " 秒后");
            }
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    // ---------------------------------------------------------- account ops

    private List<AccountConfig> monitorAccounts() {
        return new ArrayList<>(monitorAccountsRef());
    }

    private List<AccountConfig> monitorAccountsRef() {
        // BalanceMonitor 内部持有最新列表，这里通过快照视图间接获取。
        // 为保证单一事实来源，MainView 在每次变更后主动调用 syncFromConfig()。
        return cachedAccounts;
    }

    private List<AccountConfig> cachedAccounts = List.of();

    private void syncFromConfig() {
        cachedAccounts = new ArrayList<>(configStore.load().getAccounts());
        monitor.setAccounts(cachedAccounts);
        updateCards();
        refreshStatusBar();
    }

    private void openAddDialog() {
        javafx.stage.Window win = getScene() == null ? null : getScene().getWindow();
        SettingsDialog.showNew(win, cachedAccounts, lightTheme)
                .ifPresent(newCfg -> {
                    cachedAccounts.add(newCfg);
                    persist();
                });
    }

    /** 无阻塞打开添加对话框（供无头验证钩子使用）。 */
    void openAddDialogNonBlocking() {
        openAddDialogNonBlocking(null);
    }

    /** 无阻塞打开添加对话框，可指定默认服务（供无头验证钩子使用）。 */
    void openAddDialogNonBlocking(ProviderId defaultProvider) {
        javafx.stage.Window win = getScene() == null ? null : getScene().getWindow();
        if (defaultProvider == null) {
            SettingsDialog.showNewNonBlocking(win, cachedAccounts, lightTheme);
        } else {
            SettingsDialog.showNewNonBlocking(win, cachedAccounts, lightTheme, defaultProvider);
        }
    }

    private void openEditDialog(AccountConfig cfg) {
        javafx.stage.Window win = getScene() == null ? null : getScene().getWindow();
        SettingsDialog.showEdit(win, cfg, cachedAccounts, lightTheme)
                .ifPresent(updated -> {
                    for (int i = 0; i < cachedAccounts.size(); i++) {
                        if (cachedAccounts.get(i).getId().equals(updated.getId())) {
                            cachedAccounts.set(i, updated);
                            break;
                        }
                    }
                    persist();
                });
    }

    private void deleteAccount(AccountConfig cfg) {
        cachedAccounts.removeIf(a -> a.getId().equals(cfg.getId()));
        persist();
    }

    private void refreshOne(AccountConfig cfg) {
        monitor.refreshOne(cfg, () -> javafx.application.Platform.runLater(this::updateCards));
    }

    private void persist() {
        // 保留磁盘上的主题等字段，账户变更不得改写用户主题选择
        ConfigStore.AppConfig cfg = configStore.load();
        cfg.setRefreshIntervalSeconds((int) monitor.refreshIntervalSeconds());
        cfg.setProxyMode(http.proxyMode());
        cfg.setAccounts(cachedAccounts);
        configStore.save(cfg);
        monitor.setAccounts(cachedAccounts);
        updateCards();
        refreshStatusBar();
    }

    /** 切换明暗主题：更新样式表与持久化配置（悬浮球同步跟随）。 */
    private void toggleTheme() {
        lightTheme = !lightTheme;
        themeToggle.setText(lightTheme ? "浅色" : "深色");
        Scene scene = getScene();
        if (scene != null) {
            String lightCss = Objects.requireNonNull(
                    getClass().getResource("/com/aibalance/ui/theme-light.css")).toExternalForm();
            if (lightTheme) {
                if (!scene.getStylesheets().contains(lightCss)) {
                    scene.getStylesheets().add(lightCss);
                }
            } else {
                scene.getStylesheets().remove(lightCss);
            }
        }
        if (ballView != null) {
            ballView.setLightTheme(lightTheme);
        }
        // 系统标题栏由 DWM 绘制、不随 stylesheet 变化，必须单独通知系统，
        // 否则深色界面会配一条白色标题栏。
        if (scene != null && scene.getWindow() instanceof javafx.stage.Stage st) {
            WinTitleBar.applyDarkMode(st.getTitle(), !lightTheme);
        }
        ConfigStore.AppConfig cfg = configStore.load();
        cfg.setTheme(lightTheme ? "light" : "dark");
        configStore.save(cfg);

        // 主题切换缓和过渡：内容区轻微淡入，避免生硬跳变
        Node body = getCenter();
        if (body != null) {
            FadeTransition ft = new FadeTransition(Duration.millis(220), body);
            ft.setFromValue(0.35);
            ft.setToValue(1.0);
            ft.setInterpolator(Interpolator.EASE_BOTH);
            body.setOpacity(0.35);
            ft.play();
        }
    }

    // ---------------------------------------------------------- interval

    private void applyPrefsToControls() {
        applyInterval(monitor.refreshIntervalSeconds());
    }

    /** 打开全局设置对话框：自动刷新、间隔、网络模式、悬浮球自动收回。 */
    void openPreferences() {
        javafx.stage.Window win = getScene() == null ? null : getScene().getWindow();
        ConfigStore.AppConfig cfg = configStore.load();
        PreferencesDialog.Preferences current = new PreferencesDialog.Preferences(
                monitor.refreshIntervalSeconds() > 0,
                (int) monitor.refreshIntervalSeconds(),
                http.proxyMode(),
                cfg.isAutoCollapseBall());
        PreferencesDialog.show(win, current, lightTheme, this::applyPreferences);
    }

    /** 非阻塞打开设置对话框（供快照验证钩子使用）。 */
    void openPreferencesNonBlocking() {
        javafx.stage.Window win = getScene() == null ? null : getScene().getWindow();
        ConfigStore.AppConfig cfg = configStore.load();
        PreferencesDialog.Preferences current = new PreferencesDialog.Preferences(
                monitor.refreshIntervalSeconds() > 0,
                (int) monitor.refreshIntervalSeconds(),
                http.proxyMode(),
                cfg.isAutoCollapseBall());
        PreferencesDialog.showNonBlocking(win, current, lightTheme, this::applyPreferences);
    }

    /** 应用设置对话框结果：立即生效并持久化。 */
    private void applyPreferences(PreferencesDialog.Preferences p) {
        long seconds = p.autoRefresh ? p.intervalSeconds : 0;
        applyInterval(seconds);
        http.setProxyMode(p.proxyMode);
        if (ballView != null) {
            ballView.setAutoCollapseEnabled(p.autoCollapseBall);
        }
        ConfigStore.AppConfig saved = configStore.load();
        saved.setRefreshIntervalSeconds((int) seconds);
        saved.setProxyMode(p.proxyMode);
        saved.setAutoCollapseBall(p.autoCollapseBall);
        configStore.save(saved);
        refreshNow();
    }

    private void applyInterval(long seconds) {
        monitor.setRefreshInterval(seconds);
        nextRefreshEpoch = System.currentTimeMillis() + seconds * 1000;
        ConfigStore.AppConfig cfg = configStore.load();
        cfg.setRefreshIntervalSeconds((int) seconds);
        configStore.save(cfg);
        refreshStatusBar();
    }

    // ---------------------------------------------------------- spinner

    /** 简易旋转指示器。 */
    private static final class SpinnerIcon extends StackPane {
        SpinnerIcon() {
            javafx.scene.shape.SVGPath arc = new javafx.scene.shape.SVGPath();
            arc.setContent("M 8 1 A 7 7 0 0 1 15 8");
            arc.getStyleClass().add("spinner-arc");
            arc.setStrokeWidth(2.4);
            arc.setFill(null);
            arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            getChildren().add(arc);
            javafx.animation.RotateTransition rt =
                    new javafx.animation.RotateTransition(Duration.millis(700), this);
            rt.setByAngle(360);
            rt.setCycleCount(javafx.animation.Animation.INDEFINITE);
            rt.setInterpolator(javafx.animation.Interpolator.LINEAR);
            rt.play();
        }
    }
}
