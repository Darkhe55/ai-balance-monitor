package com.aibalance.ui;

import com.aibalance.model.BalanceSnapshot;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

/**
 * 悬浮球视图：主窗口隐藏后显示为桌面悬浮球（圆形、可拖动、带按压/回弹弹性动画）；
 * 单击展开为缩略面板。
 *
 * <p>交互：
 * <ul>
 *   <li>球模式：圆形球体，按压微缩、松开回弹（弹性）；拖动时位置钳制在屏幕内
 *       （留 8px 缝隙），拖动超过阈值不会误触发展开；</li>
 *   <li>面板模式：高度按内容动态计算（上限 {@link #MAX_PANEL_HEIGHT}，超出滚动），
 *       展开位置贴屏幕边缘并保留缝隙，不越界；收回时回到展开前球的位置
 *       （贴边则贴回边缘，未贴边则保持原位）；</li>
 *   <li>多额度套餐：每个额度窗口（如 5 小时窗口 / 周窗口 / 模型）单独一行，
 *       每行含名称、剩余进度条、百分比与下一次刷新（重置）时间；</li>
 *   <li>自动收回：开关开启（默认）时，鼠标离开展开面板超过 3 秒自动收起为球。</li>
 * </ul>
 */
public final class FloatingBallView {

    /** 悬浮球直径（像素）。 */
    public static final double BALL_SIZE = 64;
    /** 缩略面板固定宽度（像素）。 */
    public static final double PANEL_WIDTH = 340;
    /** 缩略面板高度上限（像素）：内容超出则滚动查看。 */
    public static final double MAX_PANEL_HEIGHT = 460;
    /** 面板高度下限（像素）。 */
    public static final double MIN_PANEL_HEIGHT = 150;
    /** 面板/球与屏幕边缘的保留缝隙（像素）。 */
    private static final double EDGE_GAP = 8;
    /** 视为拖动的移动阈值（像素）：超过则松开不触发展开。 */
    private static final double DRAG_THRESHOLD = 6;
    /** 鼠标离开展开面板后的自动收回等待时长（秒）。 */
    private static final double AUTO_COLLAPSE_SECONDS = 3;

    private static final DateTimeFormatter RESET_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** 缩略面板中单个额度窗口（不同时间段套餐）的数据。 */
    public static final class QuotaRow {
        private final String label;
        private final Double percent;
        private final String resetTime;

        public QuotaRow(String label, Double percent, String resetTime) {
            this.label = label;
            this.percent = percent;
            this.resetTime = resetTime;
        }

        public String label() {
            return label;
        }

        public Double percent() {
            return percent;
        }

        /** 下一次刷新（窗口重置）时间，如 "20:00"；无则 null。 */
        public String resetTime() {
            return resetTime;
        }
    }

    /** 缩略面板中单行账户的数据。 */
    public static final class BallRow {
        private final String accountName;
        private final String providerDisplay;
        private final String brandColor;
        private final BalanceSnapshot.Status status;
        private final String statusText;
        private final String amountText;
        private final Double percent;
        private final String caption;
        private final List<QuotaRow> quotas;

        public BallRow(String accountName, String providerDisplay, String brandColor,
                       BalanceSnapshot.Status status, String statusText, String amountText,
                       Double percent, String caption, List<QuotaRow> quotas) {
            this.accountName = accountName;
            this.providerDisplay = providerDisplay;
            this.brandColor = brandColor;
            this.status = status;
            this.statusText = statusText;
            this.amountText = amountText;
            this.percent = percent;
            this.caption = caption;
            this.quotas = quotas;
        }

        public String accountName() {
            return accountName;
        }

        public String providerDisplay() {
            return providerDisplay;
        }

        public String brandColor() {
            return brandColor;
        }

        public BalanceSnapshot.Status status() {
            return status;
        }

        public String statusText() {
            return statusText;
        }

        public String amountText() {
            return amountText;
        }

        public Double percent() {
            return percent;
        }

        public String caption() {
            return caption;
        }

        public List<QuotaRow> quotas() {
            return quotas;
        }
    }

    private final Stage ownerStage;
    private final Supplier<List<BallRow>> dataSupplier;
    private final Runnable onRestore;
    private boolean lightTheme;
    /** 悬浮球场景（主题切换需动态挂载/卸载亮色样式表）。 */
    private Scene scene;
    /** 配额行耗尽脉动动画集合（rebuildRows 时统一停止，避免旧节点动画残留）。 */
    private final java.util.List<Timeline> quotaPulses = new java.util.ArrayList<>();

    private final Stage stage;
    private final StackPane root = new StackPane();
    /** 球模式：圆形渐变球 + SVG 图标 + 状态点。 */
    private final StackPane ballPane = new StackPane();
    // 球体半径：窗口半宽 - 5，球外保留透明环以呈现发光边缘效果（透明窗口上光晕自然扩散）
    private final Circle ballCircle = new Circle(BALL_SIZE / 2 - 5);
    private final SVGPath ballIcon = new SVGPath();
    private final Region ballStatusDot = new Region();
    /** 面板模式内容。 */
    private final VBox panelBox = new VBox();
    private final VBox rowsBox = new VBox(4);
    private final ScrollPane scroll = new ScrollPane();
    private final Label panelTime = new Label();
    private final Timeline refreshTicker = new Timeline();

    private boolean panelMode;
    private boolean dragged;
    private double dragStartX;
    private double dragStartY;
    private double stageStartX;
    private double stageStartY;
    /** 展开面板前球的位置（收回时恢复：贴边则贴回边缘，未贴边保持原位）。 */
    private double ballAnchorX;
    private double ballAnchorY;
    /** 球按压/回弹弹性动画（scale）。 */
    private Timeline ballAnim;
    /** 球⇄面板尺寸/位置动画（与弹性动画互不干扰）。 */
    private Timeline sizeAnim;
    /** 调试钩子：指定球的初始屏幕位置（-1 表示默认右下角）。 */
    private double initX = -1;
    private double initY = -1;

    /** 设置球的初始屏幕位置（仅调试钩子使用）。 */
    public void setInitPosition(double x, double y) {
        this.initX = x;
        this.initY = y;
    }
    /** 自动收回开关（设置界面控制）。 */
    private volatile boolean autoCollapseEnabled = true;
    /** 鼠标移出面板后的自动收回倒计时（3 秒内鼠标回来则取消）。 */
    private PauseTransition autoCollapseTimer;
    /** 快照验证模式：禁止自动收回，避免截图前面板被收起。 */
    private boolean snapshotNoCollapse;

    public FloatingBallView(Stage ownerStage, Supplier<List<BallRow>> dataSupplier,
                            Runnable onRestore, boolean lightTheme) {
        this.ownerStage = ownerStage;
        this.dataSupplier = dataSupplier;
        this.onRestore = onRestore;
        this.lightTheme = lightTheme;

        stage = new Stage();
        // 真透明窗口：Windows 下 UNDECORATED 不透出桌面背景（会露出白/黑底方块），必须 TRANSPARENT
        stage.initStyle(StageStyle.TRANSPARENT);
        // 注意：不用 initOwner（owned 窗口）。Windows 上 owned + TRANSPARENT 窗口在 owner 隐藏后
        // 存在合成白屏/白边问题（swapchain 与 owner 渲染上下文联动失败）。球窗口改为独立顶层窗口，
        // 任务栏不显示由 WinTrayHacks.hideBallFromTaskbar 剔除，系统托盘常驻不受影响。
        stage.setAlwaysOnTop(true);
        stage.setTitle("AI 余额监控 · 悬浮球");
        stage.getIcons().addAll(SvgIcon.windowIcons());

        buildBall();
        buildPanel();

        root.getChildren().addAll(ballPane, panelBox);
        root.getStyleClass().add("ball-root");

        Scene scene = new Scene(root, BALL_SIZE, BALL_SIZE);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        this.scene = scene;
        scene.getStylesheets().add(
                FloatingBallView.class.getResource("/com/aibalance/ui/app.css").toExternalForm());
        if (lightTheme) {
            applyLightTheme();
        }
        stage.setScene(scene);

        // 自动收回：鼠标移出窗口（即离开面板）超过 3 秒自动收起为球；移回窗口立即取消
        scene.setOnMouseExited(e -> {
            if (panelMode && autoCollapseEnabled && !snapshotNoCollapse) {
                startAutoCollapseTimer();
            }
        });
        scene.setOnMouseEntered(e -> stopAutoCollapseTimer());

        // 面板模式下每 3 秒刷新一次数据（仅面板可见时生效）
        refreshTicker.getKeyFrames().add(
                new KeyFrame(Duration.seconds(3), e -> {
                    if (panelMode && stage.isShowing()) {
                        rebuildRows();
                    }
                }));
        refreshTicker.setCycleCount(Timeline.INDEFINITE);
    }

    // ------------------------------------------------------------ 球模式

    private void buildBall() {
        // 圆形裁剪：整块球区只有圆内可交互/可见，杜绝"方形感"（clip 使用自身坐标，圆心须居中）
        Circle clip = new Circle(BALL_SIZE / 2, BALL_SIZE / 2, BALL_SIZE / 2);
        ballPane.setClip(clip);

        ballCircle.getStyleClass().add("ball-circle");
        ballCircle.setCursor(Cursor.HAND);

        // 光晕环：不用 -fx-effect (dropshadow 在透明窗口上会渲染成覆盖全窗口的不透明矩形缓冲，
        // 深浅主题的四角都出现"残色方块"——深色为黑色不可见，浅色为灰蓝可见)。
        // 改用普通渐变节点环，alpha 混合走常规路径，四角保持真透明。
        Circle glowRing = new Circle(BALL_SIZE / 2.0 - 1);
        glowRing.getStyleClass().add("ball-glow-ring");
        glowRing.setMouseTransparent(true);

        // SVG 仪表盘图标替代文字（缩放围绕中心，StackPane 自动居中）
        ballIcon.setContent(SvgIcon.GAUGE_PATH);
        ballIcon.getStyleClass().add("ball-icon");
        double iconScale = BALL_SIZE * 0.44 / 24.0;
        ballIcon.setScaleX(iconScale);
        ballIcon.setScaleY(iconScale);
        ballIcon.setCursor(Cursor.HAND);

        ballStatusDot.setPrefSize(12, 12);
        ballStatusDot.getStyleClass().add("ball-status-dot");

        // 注意：StackPane 会把 resizable 子节点拉伸填满自身，必须用 maxSize 锁住容器，
        // 否则状态点会被放大成覆盖右上大块的绿色圆片
        StackPane dotWrap = new StackPane(ballStatusDot);
        dotWrap.setPrefSize(12, 12);
        dotWrap.setMaxSize(12, 12);
        dotWrap.setTranslateX(BALL_SIZE / 2 - 14);
        dotWrap.setTranslateY(-(BALL_SIZE / 2 - 14));
        dotWrap.setMouseTransparent(true);

        ballPane.getChildren().addAll(glowRing, ballCircle, ballIcon, dotWrap);
        ballPane.getStyleClass().add("ball-pane");
        ballPane.setCursor(Cursor.HAND);

        ballPane.setOnMousePressed(e -> {
            if (panelMode) {
                return;
            }
            dragged = false;
            dragStartX = e.getScreenX();
            dragStartY = e.getScreenY();
            stageStartX = stage.getX();
            stageStartY = stage.getY();
            pressBall();
        });
        ballPane.setOnMouseDragged(e -> {
            if (panelMode) {
                return;
            }
            if (Math.hypot(e.getScreenX() - dragStartX, e.getScreenY() - dragStartY) > DRAG_THRESHOLD) {
                dragged = true;
            }
            moveStageClamped(stageStartX + e.getScreenX() - dragStartX,
                    stageStartY + e.getScreenY() - dragStartY);
        });
        ballPane.setOnMouseReleased(e -> {
            if (!panelMode) {
                releaseBall(dragged);
            }
        });
        ballPane.setOnMouseClicked(e -> {
            // 拖动超过阈值不触发展开；仅单击展开
            if (!dragged && e.getClickCount() == 1 && !panelMode) {
                expandPanel();
            }
        });
    }

    /** 按压微缩（弹性反馈）。 */
    private void pressBall() {
        stopBallAnim();
        ballAnim = new Timeline(
                new KeyFrame(Duration.millis(90),
                        new KeyValue(ballPane.scaleXProperty(), 0.92, Interpolator.EASE_OUT),
                        new KeyValue(ballPane.scaleYProperty(), 0.92, Interpolator.EASE_OUT)));
        ballAnim.play();
    }

    /** 松开回弹：轻微过冲后归位（真实弹性感）。 */
    private void releaseBall(boolean wasDragged) {
        stopBallAnim();
        ballAnim = new Timeline(
                new KeyFrame(Duration.millis(wasDragged ? 80 : 110),
                        new KeyValue(ballPane.scaleXProperty(), 1.06, Interpolator.SPLINE(0.16, 1, 0.3, 1)),
                        new KeyValue(ballPane.scaleYProperty(), 1.06, Interpolator.SPLINE(0.16, 1, 0.3, 1))),
                new KeyFrame(Duration.millis(wasDragged ? 200 : 300),
                        new KeyValue(ballPane.scaleXProperty(), 1.0, Interpolator.SPLINE(0.16, 1, 0.3, 1)),
                        new KeyValue(ballPane.scaleYProperty(), 1.0, Interpolator.SPLINE(0.16, 1, 0.3, 1))));
        ballAnim.play();
    }

    private void stopBallAnim() {
        if (ballAnim != null) {
            ballAnim.stop();
            ballAnim = null;
        }
    }

    /** 移动舞台并钳制在当前屏幕可视范围内（边缘留缝隙）。 */
    private void moveStageClamped(double x, double y) {
        Rectangle2D sb = screenOf(x + BALL_SIZE / 2, y + BALL_SIZE / 2);
        double nx = Math.max(sb.getMinX() + EDGE_GAP,
                Math.min(x, sb.getMaxX() - BALL_SIZE - EDGE_GAP));
        double ny = Math.max(sb.getMinY() + EDGE_GAP,
                Math.min(y, sb.getMaxY() - BALL_SIZE - EDGE_GAP));
        stage.setX(nx);
        stage.setY(ny);
    }

    // ------------------------------------------------------------ 面板模式

    private void buildPanel() {
        panelBox.getStyleClass().add("ball-panel");
        panelBox.setVisible(false);
        panelBox.setManaged(false);

        Label title = new Label("AI 余额监控");
        title.getStyleClass().add("ball-panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button restore = new Button("恢复主界面");
        restore.getStyleClass().add("ball-panel-btn");
        restore.setOnAction(e -> restoreMain());
        Button collapse = new Button("收起");
        collapse.getStyleClass().add("ball-panel-btn");
        collapse.setOnAction(e -> collapseToBall());

        HBox header = new HBox(6, title, spacer, collapse, restore);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("ball-panel-header");
        header.setPadding(new Insets(8, 10, 8, 12));

        rowsBox.getStyleClass().add("ball-rows");

        scroll.setContent(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("ball-scroll");
        scroll.setPannable(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        panelTime.getStyleClass().add("ball-panel-time");
        panelTime.setPadding(new Insets(6, 12, 8, 12));

        panelBox.getChildren().addAll(header, scroll, panelTime);
    }

    /** 展开为缩略面板：按行数动态计算高度，位置钳制贴屏幕边缘留缝隙；记录球位置供收回恢复。 */
    private void expandPanel() {
        ballAnchorX = stage.getX();
        ballAnchorY = stage.getY();
        stopAutoCollapseTimer();
        rebuildRows();
        panelBox.setVisible(true);
        panelBox.setManaged(true);
        ballPane.setVisible(false);
        ballPane.setManaged(false);

        // 强制布局后再取内容高度，避免刚挂载子节点时 prefHeight 未含配额行
        rowsBox.applyCss();
        rowsBox.layout();
        double contentH = rowsBox.prefHeight(-1);
        if (System.getProperty("aib.ball.debug") != null) {
            System.out.println("PANEL_CONTENT_H=" + contentH + " cells=" + rowsBox.getChildren().size());
        }
        double panelH = Math.min(MAX_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, contentH + 76));
        boolean scrolling = contentH + 76 > MAX_PANEL_HEIGHT;
        scroll.setPrefViewportHeight(scrolling ? panelH - 76 : contentH);
        panelMode = true;

        // 球中心锚定，再钳制到屏幕内（边缘留 EDGE_GAP 缝隙）
        Rectangle2D sb = screenOf(stage.getX() + BALL_SIZE / 2, stage.getY() + BALL_SIZE / 2);
        double cx = stage.getX() + BALL_SIZE / 2;
        double cy = stage.getY() + BALL_SIZE / 2;
        double x = Math.max(sb.getMinX() + EDGE_GAP,
                Math.min(cx - PANEL_WIDTH / 2, sb.getMaxX() - PANEL_WIDTH - EDGE_GAP));
        double y = Math.max(sb.getMinY() + EDGE_GAP,
                Math.min(cy - panelH / 2, sb.getMaxY() - panelH - EDGE_GAP));
        animateSize(PANEL_WIDTH, panelH, x, y);

        refreshTicker.playFromStart();
        panelTime.setText("更新于 " + TIME_FMT.format(Instant.now()));
    }

    /** 如果面板处于展开状态，立即刷新一次数据（供外部自动刷新回调调用）。 */
    public void refreshPanelIfOpen() {
        if (panelMode && stage.isShowing()) {
            rebuildRows();
        }
    }

    /** 收起为悬浮球：回到展开前球的位置（贴边则贴回边缘，未贴边保持原位）。 */
    private void collapseToBall() {
        panelMode = false;
        stopAutoCollapseTimer();
        refreshTicker.stop();
        ballPane.setVisible(true);
        ballPane.setManaged(true);
        panelBox.setVisible(false);
        panelBox.setManaged(false);
        // 目标球 x/y 直接按"球左上角"钳制（上下界对称）：左/右贴边均回到边缘，中间保持原位。
        // 注意不能用"球中心"做钳制对象再减半径——右侧会多减一个半径导致无法贴边。
        Rectangle2D sb = screenOf(ballAnchorX + BALL_SIZE / 2, ballAnchorY + BALL_SIZE / 2);
        double tx = Math.max(sb.getMinX() + EDGE_GAP,
                Math.min(ballAnchorX, sb.getMaxX() - BALL_SIZE - EDGE_GAP));
        double ty = Math.max(sb.getMinY() + EDGE_GAP,
                Math.min(ballAnchorY, sb.getMaxY() - BALL_SIZE - EDGE_GAP));
        animateSize(BALL_SIZE, BALL_SIZE, tx, ty);
        releaseBall(false);
    }

    /** 恢复主窗口并隐藏悬浮球。 */
    public void restoreMain() {
        stopAutoCollapseTimer();
        refreshTicker.stop();
        stage.hide();
        onRestore.run();
    }

    /** 球⇄面板尺寸/位置动画（含贴边钳制位置；Stage 宽高属性只读，经中间属性驱动）。 */
    private void animateSize(double w, double h, double x, double y) {
        stopSizeAnim();
        javafx.beans.property.DoubleProperty sw = new javafx.beans.property.SimpleDoubleProperty(stage.getWidth());
        javafx.beans.property.DoubleProperty sh = new javafx.beans.property.SimpleDoubleProperty(stage.getHeight());
        javafx.beans.property.DoubleProperty sx = new javafx.beans.property.SimpleDoubleProperty(stage.getX());
        javafx.beans.property.DoubleProperty sy = new javafx.beans.property.SimpleDoubleProperty(stage.getY());
        sw.addListener((o, a, b) -> stage.setWidth(b.doubleValue()));
        sh.addListener((o, a, b) -> stage.setHeight(b.doubleValue()));
        sx.addListener((o, a, b) -> stage.setX(b.doubleValue()));
        sy.addListener((o, a, b) -> stage.setY(b.doubleValue()));
        sizeAnim = new Timeline(
                new KeyFrame(Duration.millis(180),
                        new KeyValue(sw, w, Interpolator.SPLINE(0.16, 1, 0.3, 1)),
                        new KeyValue(sh, h, Interpolator.SPLINE(0.16, 1, 0.3, 1)),
                        new KeyValue(sx, x, Interpolator.SPLINE(0.16, 1, 0.3, 1)),
                        new KeyValue(sy, y, Interpolator.SPLINE(0.16, 1, 0.3, 1))));
        sizeAnim.setOnFinished(e -> {
            stage.setWidth(w);
            stage.setHeight(h);
            stage.setX(x);
            stage.setY(y);
            sizeAnim = null;
        });
        sizeAnim.play();
    }

    private void stopSizeAnim() {
        if (sizeAnim != null) {
            sizeAnim.stop();
            sizeAnim = null;
        }
    }

    // ------------------------------------------------------------ 数据

    /** 重建面板行。 */
    private void rebuildRows() {
        // 先停掉上一轮耗尽脉动，避免旧节点动画残留
        for (Timeline t : quotaPulses) {
            t.stop();
        }
        quotaPulses.clear();
        List<BallRow> rows = dataSupplier.get();
        rowsBox.getChildren().clear();
        if (rows.isEmpty()) {
            Label none = new Label("暂无账户，请回到主界面添加");
            none.getStyleClass().add("ball-row-none");
            none.setPadding(new Insets(10, 12, 10, 12));
            rowsBox.getChildren().add(none);
            return;
        }
        for (BallRow r : rows) {
            rowsBox.getChildren().add(buildCell(r));
        }
        panelTime.setText("更新于 " + TIME_FMT.format(Instant.now()));
    }

    /** 账户单元格：账户主行 + （如有）各额度窗口子行。 */
    private VBox buildCell(BallRow r) {
        VBox cell = new VBox(0);
        cell.getStyleClass().add("ball-row");

        // 账户主行：品牌点 + 名称/厂商 | 金额/状态（右对齐）
        Region dot = new Region();
        dot.setPrefSize(8, 8);
        dot.setStyle("-fx-background-color: " + r.brandColor() + ";");
        StackPane dotWrap = new StackPane(dot);
        dotWrap.setAlignment(Pos.TOP_LEFT);
        dotWrap.setPadding(new Insets(5, 0, 0, 0));
        dotWrap.setMinWidth(12);
        dotWrap.setMaxSize(12, 13); // 锁尺寸，防止 StackPane 拉伸品牌点

        Label name = new Label(r.accountName());
        name.getStyleClass().add("ball-row-name");
        name.setMaxWidth(150);
        Label prov = new Label(r.providerDisplay());
        prov.getStyleClass().add("ball-row-prov");
        VBox idBox = new VBox(1, name, prov);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label amount = new Label(r.amountText());
        amount.getStyleClass().add("ball-row-amount");
        amount.setMaxWidth(120);
        Region statusDot = new Region();
        statusDot.setPrefSize(6, 6);
        statusDot.getStyleClass().addAll("status-dot",
                switch (r.status()) {
                    case OK -> "badge-ok";
                    case UNAUTHORIZED -> "badge-warn";
                    case TIMEOUT, ERROR -> "badge-err";
                    default -> "badge-muted";
                });
        Label statusText = new Label(r.statusText());
        statusText.getStyleClass().add("ball-row-status");
        HBox statusBox = new HBox(4, statusDot, statusText);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        VBox amountBox = new VBox(1, amount, statusBox);
        amountBox.setAlignment(Pos.CENTER_RIGHT);
        amountBox.setMinWidth(0);

        HBox top = new HBox(6, dotWrap, idBox, spacer, amountBox);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(6, 10, 2, 10));
        cell.getChildren().add(top);

        // 账户级剩余进度条（有比例数据时显示，全宽统一）
        if (r.percent() != null) {
            cell.getChildren().add(buildAccountBar(r.percent()));
        }

        // 多额度窗口子行：每个窗口独立进度条 + 下次刷新时间
        if (r.quotas() != null) {
            for (QuotaRow q : r.quotas()) {
                cell.getChildren().add(buildQuotaRow(q));
            }
        }

        if (r.caption() != null && !r.caption().isBlank()) {
            Tooltip.install(cell, new Tooltip(r.caption()));
        }
        return cell;
    }

    /** 账户主进度条：全宽轨道 + 剩余百分比（耗尽时红色脉动）。 */
    private HBox buildAccountBar(double percent) {
        double pct = Math.max(0, Math.min(100, percent));
        Region bar = new Region();
        bar.getStyleClass().add("ball-progress-track");
        bar.setPrefHeight(6);
        Region fill = makeProgressFill(pct, bar);
        StackPane track = new StackPane(bar, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        HBox.setHgrow(track, Priority.ALWAYS);
        Label pctLabel = new Label(String.format("%.0f%%", pct));
        pctLabel.getStyleClass().add("ball-row-pct");
        if (pct <= 0) {
            startExhaustPulse(fill);
        }
        HBox row = new HBox(6, track, pctLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 10, 4, 10));
        row.setMinWidth(0);
        return row;
    }

    /** 单个额度窗口子行：窗口名 + 独立进度条 + 百分比 + 下次刷新时间（耗尽时红色脉动）。 */
    private HBox buildQuotaRow(QuotaRow q) {
        Label lbl = new Label(q.label());
        lbl.getStyleClass().add("ball-quota-label");
        lbl.setMaxWidth(76);

        double pct = q.percent() == null ? -1 : Math.max(0, Math.min(100, q.percent()));
        Region bar = new Region();
        bar.getStyleClass().add("ball-progress-track");
        bar.setPrefHeight(6);
        Region fill = makeProgressFill(Math.max(0, pct), bar);
        StackPane track = new StackPane(bar, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        HBox.setHgrow(track, Priority.ALWAYS);

        Label pctLabel = new Label(q.percent() == null ? "--" : String.format("%.0f%%", pct));
        pctLabel.getStyleClass().add("ball-row-pct");
        pctLabel.setMinWidth(34);
        pctLabel.setMaxWidth(34);

        Label reset = new Label(q.resetTime() == null ? "" : "下次刷新 " + q.resetTime());
        reset.getStyleClass().add("ball-quota-reset");
        reset.setMaxWidth(100);

        if (pct <= 0) {
            startExhaustPulse(fill);
        }

        HBox row = new HBox(6, lbl, track, pctLabel, reset);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 10, 4, 10));
        row.setMinWidth(0);
        return row;
    }

    /**
     * 构建进度条填充块：宽度按 pct 百分比随轨道宽度变化。
     * <p>必须同时钳制 min/pref/max 宽度——StackPane 会把 resizable 子节点（Region）
     * 拉伸填满自身，仅绑 prefWidth 会被布局覆盖导致恒满格（旧 bug 根因）。
     */
    private Region makeProgressFill(double pct, Region track) {
        Region fill = new Region();
        fill.getStyleClass().add("ball-progress-fill");
        fill.setStyle("-fx-background-color: " + progressColor(pct) + ";");
        fill.minWidthProperty().bind(track.widthProperty().multiply(pct / 100.0));
        fill.prefWidthProperty().bind(track.widthProperty().multiply(pct / 100.0));
        fill.maxWidthProperty().bind(track.widthProperty().multiply(pct / 100.0));
        return fill;
    }

    /** 额度耗尽特效：红色填充条透明度脉动（更醒目）。 */
    private void startExhaustPulse(Region fill) {
        fill.setStyle("-fx-background-color: #f87171;");
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(fill.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(650), new KeyValue(fill.opacityProperty(), 0.4)),
                new KeyFrame(Duration.millis(1300), new KeyValue(fill.opacityProperty(), 1.0)));
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
        quotaPulses.add(pulse);
    }

    /**
     * 剩余比例 → 平滑渐变色（100%→0%：绿 → 黄 → 红）。
     * 以「已用比例」u = (100-pct)/100 驱动：u=0（满额）为绿、u=0.5（剩 50%）为黄、
     * u=1（耗尽）为红；两段线性插值避免分段阈值造成的颜色跳变（"突然变红"）。
     */
    static String progressColor(double pct) {
        // 已用比例：0=满额，1=耗尽（修正旧版直接拿剩余比例导致颜色倒挂：100% 变黄、50% 变绿）
        double u = (100.0 - Math.max(0, Math.min(100, pct))) / 100.0;
        int r;
        int g;
        int b;
        if (u > 0.5) {
            // 已用过半（剩余 <50%）：黄(251,191,36) → 红(248,113,113)
            double k = (u - 0.5) * 2.0;
            r = (int) Math.round(251 + (248 - 251) * k);
            g = (int) Math.round(191 + (113 - 191) * k);
            b = (int) Math.round(36 + (113 - 36) * k);
        } else {
            // 剩余充足（≥50%）：绿(52,211,153) → 黄(251,191,36)
            double k = u * 2.0;
            r = (int) Math.round(52 + (251 - 52) * k);
            g = (int) Math.round(211 + (191 - 211) * k);
            b = (int) Math.round(153 + (36 - 153) * k);
        }
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** 当前时间（HH:mm:ss）。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ------------------------------------------------------------ 对外

    /** 跟随主界面明暗主题切换（动态挂载/卸载亮色样式表，面板与球体渐变随之变化）。 */
    public void setLightTheme(boolean light) {
        this.lightTheme = light;
        if (scene == null) {
            return;
        }
        if (light) {
            applyLightTheme();
        } else {
            scene.getStylesheets().removeIf(s -> s.contains("theme-light"));
        }
    }

    private void applyLightTheme() {
        if (scene == null) {
            return;
        }
        String lightCss = FloatingBallView.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm();
        if (!scene.getStylesheets().contains(lightCss)) {
            scene.getStylesheets().add(lightCss);
        }
    }

    /** 显示悬浮球（隐藏主窗口由调用方负责）。 */
    public void showBall() {
        refreshTicker.stop();
        panelMode = false;
        ballPane.setVisible(true);
        ballPane.setManaged(true);
        panelBox.setVisible(false);
        panelBox.setManaged(false);

        if (!stage.isShowing()) {
            if (initX >= 0 && initY >= 0) {
                stage.setX(initX);
                stage.setY(initY);
            } else {
                Rectangle2D sb = Screen.getPrimary().getVisualBounds();
                stage.setX(sb.getMaxX() - BALL_SIZE - EDGE_GAP - 16);
                stage.setY(sb.getMaxY() - BALL_SIZE - EDGE_GAP - 40);
            }
        }
        stage.setWidth(BALL_SIZE);
        stage.setHeight(BALL_SIZE);
        stage.show();
        stage.toFront();
        // 悬浮球不进任务栏（工具窗口样式；Windows 下 owned 窗口仍可能占任务栏，需显式剔除）
        WinTrayHacks.hideBallFromTaskbar(stage.getTitle());
        updateBallStatusDot();
        releaseBall(false);
    }

    /** 主窗口隐藏到悬浮球（供 MainView 按钮调用）。 */
    public void enterFloating() {
        ownerStage.hide();
        showBall();
    }

    /** 隐藏悬浮球（恢复主界面时调用）。 */
    public void hide() {
        stopAutoCollapseTimer();
        refreshTicker.stop();
        stage.hide();
    }

    /** 设置「鼠标离开展开面板 3 秒自动收回」开关（设置界面控制）。 */
    public void setAutoCollapseEnabled(boolean enabled) {
        this.autoCollapseEnabled = enabled;
        if (!enabled) {
            stopAutoCollapseTimer();
        }
    }

    /** 鼠标移出面板：启动 3 秒自动收回倒计时（移回窗口则取消）。 */
    private void startAutoCollapseTimer() {
        stopAutoCollapseTimer();
        autoCollapseTimer = new PauseTransition(Duration.seconds(AUTO_COLLAPSE_SECONDS));
        autoCollapseTimer.setOnFinished(e -> {
            if (panelMode && stage.isShowing()) {
                collapseToBall();
            }
        });
        autoCollapseTimer.play();
    }

    private void stopAutoCollapseTimer() {
        if (autoCollapseTimer != null) {
            autoCollapseTimer.stop();
            autoCollapseTimer = null;
        }
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /** 供无头快照钩子：直接打开缩略面板（快照模式禁用自动收回，避免截图前面板被收起）。 */
    void openPanelForSnapshot() {
        snapshotNoCollapse = true;
        expandPanel();
    }

    /** 供无头快照钩子：获取悬浮球场景（球或面板）。 */
    javafx.scene.Scene scene() {
        return stage.getScene();
    }

    /** 根据聚合状态更新悬浮球右下角状态点。 */
    private void updateBallStatusDot() {
        List<BallRow> rows = dataSupplier.get();
        boolean hasErr = rows.stream().anyMatch(r ->
                r.status() == BalanceSnapshot.Status.TIMEOUT || r.status() == BalanceSnapshot.Status.ERROR);
        boolean hasWarn = rows.stream().anyMatch(r -> r.status() == BalanceSnapshot.Status.UNAUTHORIZED);
        ballStatusDot.getStyleClass().removeAll("ball-status-ok", "ball-status-warn", "ball-status-err");
        if (rows.isEmpty() || rows.stream().allMatch(r -> r.status() == BalanceSnapshot.Status.NOT_CONFIGURED)) {
            ballStatusDot.getStyleClass().add("ball-status-ok");
        } else if (hasErr) {
            ballStatusDot.getStyleClass().add("ball-status-err");
        } else if (hasWarn) {
            ballStatusDot.getStyleClass().add("ball-status-warn");
        } else {
            ballStatusDot.getStyleClass().add("ball-status-ok");
        }
    }

    /** 取包含给定点（或最近）的屏幕可视边界。 */
    private static Rectangle2D screenOf(double x, double y) {
        for (Screen s : Screen.getScreens()) {
            Rectangle2D b = s.getVisualBounds();
            if (b.contains(x, y)) {
                return b;
            }
        }
        Rectangle2D best = Screen.getPrimary().getVisualBounds();
        double bestD = Double.MAX_VALUE;
        for (Screen s : Screen.getScreens()) {
            Rectangle2D b = s.getVisualBounds();
            double dx = Math.max(0, Math.max(b.getMinX() - x, x - b.getMaxX()));
            double dy = Math.max(0, Math.max(b.getMinY() - y, y - b.getMaxY()));
            double d = Math.hypot(dx, dy);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }
}
