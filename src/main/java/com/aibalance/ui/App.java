package com.aibalance.ui;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderId;
import com.aibalance.provider.AliyunAccountProvider;
import com.aibalance.provider.ProviderRegistry;
import com.aibalance.service.BalanceMonitor;
import com.aibalance.service.ConfigStore;
import com.aibalance.service.HttpSupport;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * 应用主类：装配服务、构建主界面。
 * 支持命令行参数：
 *   --snapshot=文件.png        启动后渲染主界面并导出 PNG 后自动退出（用于无头验证）
 *   --snapshot-dialog=文件.png 启动后打开"添加账户"对话框并导出 PNG 后自动退出
 *   --theme=dark|light         仅本次运行强制指定主题（不写入配置）
 */
public final class App extends Application {

    public App() {
    }

    private BalanceMonitor monitor;
    private ConfigStore configStore;
    private MainView mainView;

    @Override
    public void start(Stage stage) {
        loadFonts();

        List<String> args = getParameters().getRaw();
        String snapshot = args.stream()
                .filter(a -> a.startsWith("--snapshot="))
                .map(a -> a.substring("--snapshot=".length()))
                .findFirst().orElse(null);
        String snapshotDialog = args.stream()
                .filter(a -> a.startsWith("--snapshot-dialog="))
                .map(a -> a.substring("--snapshot-dialog=".length()))
                .findFirst().orElse(null);
        String snapshotMenu = args.stream()
                .filter(a -> a.startsWith("--snapshot-menu="))
                .map(a -> a.substring("--snapshot-menu=".length()))
                .findFirst().orElse(null);
        String snapshotBall = args.stream()
                .filter(a -> a.startsWith("--snapshot-ball="))
                .map(a -> a.substring("--snapshot-ball=".length()))
                .findFirst().orElse(null);
        String snapshotPrefs = args.stream()
                .filter(a -> a.startsWith("--snapshot-prefs="))
                .map(a -> a.substring("--snapshot-prefs=".length()))
                .findFirst().orElse(null);
        String snapshotEntries = args.stream()
                .filter(a -> a.startsWith("--snapshot-entries="))
                .map(a -> a.substring("--snapshot-entries=".length()))
                .findFirst().orElse(null);
        String snapshotError = args.stream()
                .filter(a -> a.startsWith("--snapshot-error="))
                .map(a -> a.substring("--snapshot-error=".length()))
                .findFirst().orElse(null);
        // 悬浮球面板滚动验证：向内存注入 N 个真实厂商的"未配置 Key"账户（不写入配置、不编造余额），
        // 仅用于验证面板动态高度与上限滚动；默认 0。
        int snapshotSeed = 0;
        String seedArg = args.stream()
                .filter(a -> a.startsWith("--snapshot-seed="))
                .map(a -> a.substring("--snapshot-seed=".length()))
                .findFirst().orElse(null);
        if (seedArg != null) {
            try {
                snapshotSeed = Math.max(0, Math.min(30, Integer.parseInt(seedArg)));
            } catch (NumberFormatException ignored) {
                System.err.println("SNAPSHOT_SEED_INVALID=" + seedArg);
            }
        }
        // 快照验证钩子：把全部卡片注入指定的剩余额度百分比（0-100，仅用于验证进度条与耗尽样式，
        // 不写入配置、不触发网络请求）。默认 -1 表示不注入。
        double snapshotQuota = -1;
        String quotaArg = args.stream()
                .filter(a -> a.startsWith("--snapshot-quota="))
                .map(a -> a.substring("--snapshot-quota=".length()))
                .findFirst().orElse(null);
        if (quotaArg != null) {
            try {
                snapshotQuota = Math.max(0, Math.min(100, Double.parseDouble(quotaArg)));
            } catch (NumberFormatException ignored) {
                System.err.println("SNAPSHOT_QUOTA_INVALID=" + quotaArg);
            }
        }
        String themeArg = args.stream()
                .filter(a -> a.startsWith("--theme="))
                .map(a -> a.substring("--theme=".length()))
                .findFirst().orElse(null);
        // 白边实测钩子：启动后自动进入悬浮球模式（球默认在屏幕右下角），供桌面全屏截图验证透明合成
        boolean enterBall = args.stream().anyMatch(a -> a.equals("--enter-ball"));
        // 球初始位置钩子：--ball-pos=100,100 指定球出现在屏幕指定坐标（默认右下角），
        // 用于把球放到桌面无遮挡区域做透明合成验证
        int[] ballPos = null;
        String posArg = args.stream()
                .filter(a -> a.startsWith("--ball-pos="))
                .map(a -> a.substring("--ball-pos=".length()))
                .findFirst().orElse(null);
        if (posArg != null && posArg.contains(",")) {
            try {
                String[] parts = posArg.split(",");
                ballPos = new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            } catch (NumberFormatException ignored) {
                System.err.println("BALL_POS_INVALID=" + posArg);
            }
        }
        int[] ballPosFinal = ballPos;
        String delayArg = args.stream()
                .filter(a -> a.startsWith("--snapshot-delay-ms="))
                .map(a -> a.substring("--snapshot-delay-ms=".length()))
                .findFirst().orElse(null);
        long snapshotDelayMs = delayArg == null ? 20000L : Long.parseLong(delayArg);
        // 快照钩子可选：添加账户表单默认选中的服务（默认 DeepSeek；双计费厂商用于验证计费形态下拉）
        ProviderId snapshotProvider = null;
        String providerArg = args.stream()
                .filter(a -> a.startsWith("--snapshot-provider="))
                .map(a -> a.substring("--snapshot-provider=".length()))
                .findFirst().orElse(null);
        if (providerArg != null && !providerArg.isBlank()) {
            try {
                snapshotProvider = ProviderId.valueOf(providerArg.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                System.err.println("SNAPSHOT_PROVIDER_INVALID=" + providerArg);
            }
        }
        final ProviderId snapshotProviderF = snapshotProvider;

        ConfigStore configStore = new ConfigStore();
        ConfigStore.AppConfig cfg = configStore.load();
        final boolean configUnreadable = configStore.unreadable();

        // 百炼旧入口迁移：
        // 1) 已移除 / 已合并的入口（模型限流配额 BAILIAN、官方账单 BAILIAN_BILLING、
        //    账户余额 QWEN_ACCOUNT）→「阿里云百炼·账户余额与账单」入口；
        // 2) 配置里指向国际站（非 cn- 地域、-intl 域名或 ap-southeast 端点）的账户 → 国际站入口。
        // 迁移后清空 Base URL，让两个入口各自使用官方 endpoint；自定义 / 代理地址原样保留。
        // 注意：原「模型配额」入口填的是 DashScope API Key（非 AccessKey），迁移后凭证字段语义
        // 变为阿里云 AccessKey ID/Secret，卡片会提示补填 Secret，需用户自行替换或删除该账户。
        boolean migratedAliyun = false;
        for (AccountConfig acc : cfg.getAccounts()) {
            ProviderId p = acc.getProviderId();
            boolean legacyMerged = p.isHidden();
            if (!legacyMerged && p != ProviderId.BAILIAN_ACCOUNT) {
                continue;
            }
            String base = acc.getBaseUrl() == null ? "" : acc.getBaseUrl().strip();
            boolean international = AliyunAccountProvider.looksInternational(base);
            boolean customEndpoint = base.contains("://")
                    && !base.toLowerCase(java.util.Locale.ROOT).contains("aliyuncs.com");
            if (legacyMerged || international) {
                acc.setProviderId(international
                        ? ProviderId.BAILIAN_ACCOUNT_INTL : ProviderId.BAILIAN_ACCOUNT);
                if (!customEndpoint) {
                    acc.setBaseUrl("");
                }
                migratedAliyun = true;
            }
        }
        if (migratedAliyun) {
            configStore.save(cfg);
            System.out.println("MIGRATED_BAILIAN_ACCOUNTS=true");
        }

        // 启动自检：用与查询相同的 HTTP 通道直连官方端点，验证 TLS 链路。
        // 结果写入 stdout（--snapshot 模式可捕获），不阻塞 UI。
        try {
            HttpSupport selfTest = new HttpSupport(cfg.getProxyMode());
            HttpSupport.Response r = selfTest.get("https://api.deepseek.com/user/balance",
                    java.util.Map.of());
            System.out.println("STARTUP_SELFTEST status=" + r.status());
        } catch (Exception e) {
            System.out.println("STARTUP_SELFTEST FAIL " + e);
        }

        HttpSupport http = new HttpSupport(cfg.getProxyMode());
        ProviderRegistry registry = new ProviderRegistry(http);

        // 移除历史遗留的演示账户（apiKey 为 "demo"）：演示内容已下线，保留将产生无效状态
        List<AccountConfig> stale = cfg.getAccounts().stream()
                .filter(a -> a.getApiKey() != null && a.getApiKey().strip().equals("demo"))
                .toList();
        if (!stale.isEmpty()) {
            List<AccountConfig> cleaned = cfg.getAccounts().stream()
                    .filter(a -> stale.stream().noneMatch(s -> s.getId().equals(a.getId())))
                    .toList();
            cfg.setAccounts(cleaned);
            configStore.save(cfg);
            System.out.println("CLEANED_DEMO_ACCOUNTS=" + stale.size());
        }

        monitor = new BalanceMonitor(registry);
        List<AccountConfig> monitorAccounts = new java.util.ArrayList<>(cfg.getAccounts());
        if (snapshotSeed > 0) {
            // 快照验证：追加未配置 Key 的真实厂商账户（内存态，不持久化，不显示任何余额）
            ProviderId[] pids = ProviderId.values();
            for (int i = 0; i < snapshotSeed; i++) {
                AccountConfig seed = new AccountConfig(pids[i % pids.length],
                        "验证账户 " + (i + 1), null, null);
                seed.setEnabled(true);
                monitorAccounts.add(seed);
            }
            System.out.println("SNAPSHOT_SEEDED=" + snapshotSeed);
        }
        monitor.setAccounts(monitorAccounts);
        monitor.setRefreshInterval(cfg.getRefreshIntervalSeconds());

        final boolean light;
        if (themeArg != null) {
            light = "light".equals(themeArg);
        } else {
            light = "light".equals(cfg.getTheme());
        }

        MainView mainView = new MainView(configStore, monitor, http, light);
        this.mainView = mainView;
        if (snapshotSeed > 0) {
            // 快照验证：把种子账户同步给 MainView 数据源（内存态）
            List<AccountConfig> seeds = monitorAccounts.subList(
                    Math.min(cfg.getAccounts().size(), monitorAccounts.size()), monitorAccounts.size());
            mainView.seedSnapshotAccounts(seeds);
        }
        Scene scene = new Scene(mainView, 1120, 720);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/aibalance/ui/app.css")).toExternalForm());
        if (light) {
            scene.getStylesheets().add(Objects.requireNonNull(
                    getClass().getResource("/com/aibalance/ui/theme-light.css")).toExternalForm());
        }

        // 悬浮球：主窗口隐藏后以桌面悬浮球显示，点击展开缩略面板
        FloatingBallView ballView = new FloatingBallView(stage,
                mainView::collectBallRows,
                () -> {
                    stage.show();
                    stage.toFront();
                },
                light);
        mainView.setBallView(ballView);
        ballView.setAutoCollapseEnabled(cfg.isAutoCollapseBall());

        // 自动刷新完成后：同时更新主界面卡片和悬浮球面板
        monitor.setRefreshCompleteListener(() -> javafx.application.Platform.runLater(() -> {
            mainView.updateCards();
            mainView.refreshStatusBar();
            ballView.refreshPanelIfOpen();
        }));

        stage.setTitle("AI 余额监控");
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        stage.getIcons().add(SvgIcon.windowIcon());
        stage.setScene(scene);
        final boolean snapshotMode = snapshot != null || snapshotDialog != null
                || snapshotMenu != null || snapshotBall != null || snapshotPrefs != null
                || snapshotEntries != null || snapshotError != null
                || enterBall;
        final TrayManager tray = new TrayManager();
        if (!snapshotMode) {
            installTray(tray, stage, ballView);
        }
        stage.setOnCloseRequest(e -> {
            if (snapshotMode) {
                monitor.shutdown();
                Platform.exit();
                return;
            }
            // 常驻后台挂起：关闭窗口 → 进入悬浮球（系统托盘），真正退出走托盘「关闭软件」
            e.consume();
            ballView.enterFloating();
            if (!tray.isInstalled()) {
                installTray(tray, stage, ballView);
            }
        });
        stage.show();
        applyTitleBarTheme(stage, light);
        if (configUnreadable && !snapshotMode) {
            warnConfigUnreadable(configStore.configFile());
        }

        if (enterBall) {
            // 白边实测钩子：启动 3 秒后自动进入悬浮球模式（给主窗口完整渲染时间，避免 owned 窗口
            // 在 owner 未完成首次绘制时显示白屏），球默认右下角；--ball-pos=x,y 可指定初始位置
            PauseTransition enter = new PauseTransition(Duration.millis(3000));
            enter.setOnFinished(e -> {
                if (ballPosFinal != null) {
                    ballView.setInitPosition(ballPosFinal[0], ballPosFinal[1]);
                }
                ballView.enterFloating();
            });
            enter.play();
        }

        // 启动后立即刷新一次
        mainView.refreshNow();

        if (snapshotError != null) {
            // 错误信息换行验证：构造带超长错误 message 与长明细值的卡片，验证不再被截断
            AccountConfig errCfg = new AccountConfig();
            errCfg.setProviderId(ProviderId.DEEPSEEK);
            errCfg.setName("错误信息换行验证");
            errCfg.setApiKey("sk-err-demo");
            AccountCard errCard = new AccountCard(errCfg, a -> {}, a -> {}, a -> {}, light);
            BalanceSnapshot errSnap = BalanceSnapshot.of(ProviderId.DEEPSEEK, "err-snap-1", "错误信息换行验证",
                            BalanceSnapshot.Status.ERROR)
                    .message("查询失败：请求响应无法解析，请检查 Base URL 是否填写完整、网络是否可达；"
                            + "若出现 TLS 握手失败请先在设置中切换为「直连」网络模式后重试，"
                            + "或稍后再次点击「立即刷新」查看最新结果。")
                    .detail("响应正文",
                            "HTTP 404 Not Found: {\"error\":{\"message\":\"The requested resource was not found "
                                    + "on this server. Please check your Base URL and try again later.\","
                                    + "\"type\":\"invalid_request_error\"}}")
                    .build();
            errCard.update(errSnap);
            javafx.scene.layout.VBox holder = new javafx.scene.layout.VBox(errCard);
            holder.setPadding(new javafx.geometry.Insets(16));
            Stage errStage = new Stage();
            Scene errScene = new Scene(holder, 400, 460);
            errScene.getStylesheets().add(Objects.requireNonNull(
                    getClass().getResource("/com/aibalance/ui/app.css")).toExternalForm());
            if (light) {
                errScene.getStylesheets().add(Objects.requireNonNull(
                        getClass().getResource("/com/aibalance/ui/theme-light.css")).toExternalForm());
            }
            errStage.setScene(errScene);
            errStage.show();
            PauseTransition errSettle = new PauseTransition(Duration.millis(700));
            errSettle.setOnFinished(e -> {
                try {
                    WritableImage img = errScene.snapshot(null);
                    File errOut = new File(snapshotError);
                    writePng(img, errOut);
                    System.out.println("SNAPSHOT_ERROR_SAVED=" + errOut.getAbsolutePath());
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_ERROR_FAILED: " + ex);
                } finally {
                    errStage.close();
                    monitor.shutdown();
                    Platform.exit();
                }
            });
            errSettle.play();
            return;
        }

        if (snapshotDialog != null) {
            PauseTransition open = new PauseTransition(Duration.millis(1000));
            open.setOnFinished(e -> mainView.openAddDialogNonBlocking(snapshotProviderF));
            open.play();
            PauseTransition shoot = new PauseTransition(Duration.millis(2400));
            shoot.setOnFinished(e -> {
                try {
                    Window dialogWin = Window.getWindows().stream()
                            .filter(w -> w.isShowing() && w != stage)
                            .findFirst().orElse(null);
                    if (dialogWin == null || dialogWin.getScene() == null) {
                        System.err.println("SNAPSHOT_DIALOG_NOT_FOUND");
                    } else {
                        // 打开服务选型弹窗（非阻塞：快照钩子在动画回调中，showAndWait 非法）
                        final boolean snapLight = light;
                        Node picker = dialogWin.getScene().getRoot().lookup(".provider-picker");
                        if (picker instanceof javafx.scene.control.Button pickerBtn) {
                            ProviderPickerDialog.showNonBlocking(dialogWin, ProviderId.DEEPSEEK, snapLight);
                        }
                        // 等弹窗动画后截图（主对话框场景 + 选型弹窗场景各存一张）
                        PauseTransition settle = new PauseTransition(Duration.millis(900));
                        settle.setOnFinished(ev -> {
                            try {
                                WritableImage dialogImg = dialogWin.getScene().snapshot(null);
                                File dialogOut = new File(snapshotDialog + ".dialog.png");
                                writePng(dialogImg, dialogOut);
                                System.out.println("SNAPSHOT_DIALOG_SAVED=" + dialogOut.getAbsolutePath());

                                Window popupWin = Window.getWindows().stream()
                                        .filter(w -> w.isShowing() && w != stage && w != dialogWin)
                                        .findFirst().orElse(null);
                                File out = new File(snapshotDialog);
                                if (popupWin != null && popupWin.getScene() != null) {
                                    writePng(popupWin.getScene().snapshot(null), out);
                                    System.out.println("SNAPSHOT_POPUP_SAVED=" + out.getAbsolutePath());
                                    // 临时拉高视口使全部分区（国内/海外/中转站）一次可见，再截一张
                                    Node sc = popupWin.getScene().getRoot().lookup(".picker-scroll");
                                    if (sc instanceof javafx.scene.control.ScrollPane sp) {
                                        popupWin.setWidth(700);
                                        popupWin.setHeight(960);
                                        sp.setPrefViewportHeight(900);
                                        sp.setVvalue(1.0);
                                        PauseTransition bottom = new PauseTransition(Duration.millis(800));
                                        bottom.setOnFinished(b -> {
                                            try {
                                                File bottomOut = new File(snapshotDialog + ".bottom.png");
                                                writePng(popupWin.getScene().snapshot(null), bottomOut);
                                                System.out.println("SNAPSHOT_POPUP_BOTTOM_SAVED=" + bottomOut.getAbsolutePath());
                                            } catch (Exception ex) {
                                                System.err.println("SNAPSHOT_BOTTOM_FAILED: " + ex);
                                            } finally {
                                                monitor.shutdown();
                                                Platform.exit();
                                            }
                                        });
                                        bottom.play();
                                        return;
                                    }
                                    System.err.println("SNAPSHOT_NO_SCROLL");
                                } else {
                                    writePng(dialogImg, out);
                                    System.out.println("SNAPSHOT_DIALOG_SAVED=" + out.getAbsolutePath());
                                }
                            } catch (Exception ex) {
                                System.err.println("SNAPSHOT_DIALOG_FAILED: " + ex);
                            }
                            monitor.shutdown();
                            Platform.exit();
                        });
                        settle.play();
                        return;
                    }
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_DIALOG_FAILED: " + ex);
                }
                monitor.shutdown();
                Platform.exit();
            });
            shoot.play();
        } else if (snapshotMenu != null) {
            // 等卡片渲染完成后，程序化触发第一个卡片的「⋯」菜单并导出弹层截图
            PauseTransition wait = new PauseTransition(Duration.millis(20000));
            wait.setOnFinished(e -> {
                try {
                    Node btn = mainView.lookupAll(".card-menu").stream().findFirst().orElse(null);
                    if (!(btn instanceof javafx.scene.control.Button menuBtn)) {
                        System.err.println("SNAPSHOT_MENU_NO_BUTTON");
                    } else {
                        menuBtn.fire();
                        PauseTransition settle = new PauseTransition(Duration.millis(900));
                        settle.setOnFinished(ev -> {
                            try {
                                Window popupWin = Window.getWindows().stream()
                                        .filter(w -> w.isShowing() && w != stage)
                                        .findFirst().orElse(null);
                                File out = new File(snapshotMenu);
                                if (popupWin != null && popupWin.getScene() != null) {
                                    writePng(popupWin.getScene().snapshot(null), out);
                                    System.out.println("SNAPSHOT_MENU_SAVED=" + out.getAbsolutePath());
                                } else {
                                    System.err.println("SNAPSHOT_MENU_NOT_FOUND");
                                }
                            } catch (Exception ex) {
                                System.err.println("SNAPSHOT_MENU_FAILED: " + ex);
                            } finally {
                                monitor.shutdown();
                                Platform.exit();
                            }
                        });
                        settle.play();
                        return;
                    }
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_MENU_FAILED: " + ex);
                }
                monitor.shutdown();
                Platform.exit();
            });
            wait.play();
        } else if (snapshotBall != null) {
            // 验证悬浮球：先截球，再展开缩略面板截图
            PauseTransition wait = new PauseTransition(Duration.millis(1500));
            wait.setOnFinished(e -> {
                try {
                    stage.hide();
                    ballView.showBall();
                    PauseTransition ballShot = new PauseTransition(Duration.millis(700));
                    ballShot.setOnFinished(ev -> {
                        try {
                            File ballOut = new File(snapshotBall + ".ball.png");
                            writePng(ballView.scene().snapshot(null), ballOut);
                            System.out.println("SNAPSHOT_BALL_SAVED=" + ballOut.getAbsolutePath());
                            for (FloatingBallView.BallRow br : mainView.collectBallRows()) {
                                System.out.println("BALLROW name=" + br.accountName() + " pct=" + br.percent()
                                        + " quotas=" + br.quotas().stream()
                                        .map(q -> q.label() + "|" + q.percent() + "|" + q.resetTime()).toList());
                            }
                            ballView.openPanelForSnapshot();
                            PauseTransition panelShot = new PauseTransition(Duration.millis(900));
                            panelShot.setOnFinished(e2 -> {
                                try {
                                    File panelOut = new File(snapshotBall + ".panel.png");
                                    writePng(ballView.scene().snapshot(null), panelOut);
                                    System.out.println("SNAPSHOT_PANEL_SAVED=" + panelOut.getAbsolutePath());
                                } catch (Exception ex) {
                                    System.err.println("SNAPSHOT_PANEL_FAILED: " + ex);
                                } finally {
                                    monitor.shutdown();
                                    Platform.exit();
                                }
                            });
                            panelShot.play();
                        } catch (Exception ex) {
                            System.err.println("SNAPSHOT_BALL_FAILED: " + ex);
                            monitor.shutdown();
                            Platform.exit();
                        }
                    });
                    ballShot.play();
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_BALL_FAILED: " + ex);
                    monitor.shutdown();
                    Platform.exit();
                }
            });
            wait.play();
        } else if (snapshotEntries != null) {
            // 验证「展示条目」对话框：构造含多窗口/多分组条目的模拟快照（MiniMax 套餐窗口 + 用量），
            // 非阻塞打开并导出 PNG 后自动退出；条目数超过 12 时隐藏排序按钮（验证大数据量场景）。
            PauseTransition wait = new PauseTransition(Duration.millis(1500));
            wait.setOnFinished(e -> {
                try {
                    com.aibalance.model.AccountConfig acc = new com.aibalance.model.AccountConfig(
                            com.aibalance.model.ProviderId.MINIMAX, "展示条目验证", null, null);
                    com.aibalance.model.BalanceSnapshot.Builder sb =
                            com.aibalance.model.BalanceSnapshot.ok(
                                    com.aibalance.model.ProviderId.MINIMAX, "v", "展示条目验证");
                    int n = 20;
                    for (int i = 0; i < n; i++) {
                        sb.metric(com.aibalance.model.ProviderCapability.QUOTA,
                                "窗口 " + (i + 1) + " 套餐剩余", 100.0 - i, "tokens",
                                100.0 - i, null);
                    }
                    sb.metric(com.aibalance.model.ProviderCapability.USAGE,
                            "本周已用 tokens", 42.0, "tokens", 58.0, null);
                    // 明细组（模拟阿里百炼逐模型配额等场景）
                    for (int i = 0; i < 6; i++) {
                        sb.detail("模型 " + (i + 1) + "-" + (char) ('a' + i), "请求 60 次/分钟 · 用量 128k/分钟");
                    }
                    javafx.scene.control.Dialog<java.util.List<String>> dialog =
                            DisplayEntriesDialog.showNonBlocking(stage, acc, sb.build(), light);
                    if (dialog == null) {
                        System.err.println("SNAPSHOT_ENTRIES_NOT_AVAILABLE");
                        monitor.shutdown();
                        Platform.exit();
                        return;
                    }
                    PauseTransition settle = new PauseTransition(Duration.millis(900));
                    settle.setOnFinished(ev -> {
                        try {
                            // 滚动到底部验证明细组（大数据量时条目在视口外）
                            Node sc = dialog.getDialogPane().getScene().getRoot().lookup(".dialog-scroll");
                            if (sc instanceof javafx.scene.control.ScrollPane sp) {
                                sp.setVvalue(1.0);
                            }
                            PauseTransition bottom = new PauseTransition(Duration.millis(500));
                            bottom.setOnFinished(b -> {
                                try {
                                    File out = new File(snapshotEntries);
                                    writePng(dialog.getDialogPane().getScene().snapshot(null), out);
                                    System.out.println("SNAPSHOT_ENTRIES_SAVED=" + out.getAbsolutePath());
                                } catch (Exception ex) {
                                    System.err.println("SNAPSHOT_ENTRIES_FAILED: " + ex);
                                } finally {
                                    monitor.shutdown();
                                    Platform.exit();
                                }
                            });
                            bottom.play();
                        } catch (Exception ex) {
                            System.err.println("SNAPSHOT_ENTRIES_FAILED: " + ex);
                            monitor.shutdown();
                            Platform.exit();
                        }
                    });
                    settle.play();
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_ENTRIES_FAILED: " + ex);
                    monitor.shutdown();
                    Platform.exit();
                }
            });
            wait.play();
        } else if (snapshotPrefs != null) {            // 验证全局设置对话框：打开设置并导出 PNG 后自动退出
            PauseTransition wait = new PauseTransition(Duration.millis(1500));
            wait.setOnFinished(e -> {
                try {
                    mainView.openPreferencesNonBlocking();
                    PauseTransition settle = new PauseTransition(Duration.millis(1400));
                    settle.setOnFinished(ev -> {
                        try {
                            Window prefWin = Window.getWindows().stream()
                                    .filter(w -> w.isShowing() && w != stage)
                                    .findFirst().orElse(null);
                            File out = new File(snapshotPrefs);
                            if (prefWin != null && prefWin.getScene() != null) {
                                writePng(prefWin.getScene().snapshot(null), out);
                                System.out.println("SNAPSHOT_PREFS_SAVED=" + out.getAbsolutePath());
                            } else {
                                System.err.println("SNAPSHOT_PREFS_NOT_FOUND");
                            }
                        } catch (Exception ex) {
                            System.err.println("SNAPSHOT_PREFS_FAILED: " + ex);
                        } finally {
                            monitor.shutdown();
                            Platform.exit();
                        }
                    });
                    settle.play();
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_PREFS_FAILED: " + ex);
                    monitor.shutdown();
                    Platform.exit();
                }
            });
            wait.play();
        } else if (snapshot != null) {
            // 等入场动画与首次刷新（真实网络请求）完成后再导出；--snapshot-delay-ms 可覆盖等待时长
            final double quotaForSnap = snapshotQuota;
            PauseTransition wait = new PauseTransition(Duration.millis(snapshotDelayMs));
            wait.setOnFinished(e -> {
                try {
                    System.out.println("GEIST_FAMILIES=" + javafx.scene.text.Font.getFamilies().stream()
                            .filter(f -> f.toLowerCase().contains("geist")).toList());
                    javafx.scene.Node title = mainView.lookup(".app-title");
                    if (title instanceof javafx.scene.control.Labeled lab) {
                        System.out.println("TITLE_FONT=" + lab.getFont().getFamily() + " / " + lab.getFont().getName());
                    }
                    javafx.scene.Node amount = mainView.lookupAll(".amount-value").stream()
                            .findFirst().orElse(null);
                    if (amount instanceof javafx.scene.control.Labeled lab) {
                        System.out.println("AMOUNT_FONT=" + lab.getFont().getFamily() + " / " + lab.getFont().getName());
                    }
                    if (quotaForSnap >= 0) {
                        mainView.seedQuotaSnapshot(quotaForSnap);
                    }
                    WritableImage img = scene.snapshot(null);
                    File out = new File(snapshot);
                    writePng(img, out);
                    System.out.println("SNAPSHOT_SAVED=" + out.getAbsolutePath());
                } catch (Exception ex) {
                    System.err.println("SNAPSHOT_FAILED: " + ex);
                } finally {
                    monitor.shutdown();
                    Platform.exit();
                }
            });
            wait.play();
        }
    }

    /**
     * 把系统标题栏切成深/浅色。标题栏由 DWM 绘制、换 stylesheet 影响不到，
     * 需单独通知系统；刚 show 时 HWND 可能尚未就绪，故首帧后再补一次。
     */
    private static void applyTitleBarTheme(Stage stage, boolean light) {
        WinTitleBar.applyDarkMode(stage.getTitle(), !light);
        Platform.runLater(() -> WinTitleBar.applyDarkMode(stage.getTitle(), !light));
    }

    /**
     * 配置解析失败时必须让用户看见。
     *
     * <p>此时应用处于只读保护状态（原文件不会被写回）。不提示的话，用户看到的只是
     * "账户全没了"，却不知道数据还在、也不能安全地新建——这正是本提示要消除的歧义。
     */
    private static void warnConfigUnreadable(java.nio.file.Path file) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("配置无法读取");
        alert.setHeaderText("已停止写入配置，原文件未被修改");
        alert.setContentText("配置文件无法解析：\n" + file
                + "\n\n为避免账户与 API Key 被空配置覆盖，本次运行不会保存任何配置。"
                + "\n原文件已原样保留，同目录下的 config.corrupted-<时间戳>.bak 是同一份内容的备份。"
                + "\n\n请手动检查该文件（修复内容，或改名移走）后重启本程序。");
        alert.showAndWait();
    }

    /** 安装系统托盘（悬浮球模式下常驻后台挂起；托盘不可用时静默跳过）。 */
    private void installTray(TrayManager tray, Stage stage, FloatingBallView ballView) {
        if (tray.isInstalled()) {
            return;
        }
        try {
            tray.install(SvgIcon.render(32, javafx.scene.paint.Color.web("#E07BA8")),
                    () -> showMainFromBall(stage, ballView),
                    () -> exitApp(stage, ballView, tray));
        } catch (Throwable t) {
            System.err.println("TRAY_INSTALL_FAIL: " + t);
        }
    }

    /** 托盘「显示完整界面」：悬浮球可见则收起恢复主窗口，否则直接显示/置前。 */
    private void showMainFromBall(Stage stage, FloatingBallView ballView) {
        if (ballView.isShowing()) {
            ballView.restoreMain();
        } else {
            stage.show();
            stage.toFront();
        }
        // 切回主界面时触发一次刷新，确保数据最新
        mainView.refreshNow();
    }

    /** 托盘「关闭软件」：清理悬浮球/托盘/监控线程后退出。 */
    private void exitApp(Stage stage, FloatingBallView ballView, TrayManager tray) {
        try {
            ballView.hide();
        } catch (Exception ignored) {
            // 忽略悬浮球清理异常
        }
        tray.remove();
        monitor.shutdown();
        Platform.exit();
        System.exit(0);
    }

    /** 将 JavaFX 图像逐像素写入 PNG（不依赖 javafx.swing 模块）。 */    private static void writePng(WritableImage img, File out) throws java.io.IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        ImageIO.write(bi, "png", out);
    }

    /** 注册内嵌 Geist Sans 字重（JavaFX 按家族名+字重匹配）。 */
    private static void loadFonts() {
        String[] weights = {"400", "500", "600", "700", "800"};
        for (String w : weights) {
            try {
                javafx.scene.text.Font.loadFont(
                        App.class.getResource("/com/aibalance/ui/fonts/geist-sans-latin-"
                                + w + "-normal.woff").toExternalForm(), 14);
            } catch (Exception ex) {
                System.err.println("FONT_LOAD_FAIL " + w + ": " + ex);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
