package com.aibalance.ui;

import com.aibalance.model.ProviderId;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 厂商选型弹窗：图标网格（官方 logo + 名称），按 国内 / 海外 / 中转站 分区展示，
 * 中转站置于最下方。选中后返回 ProviderId。
 * <p>
 * 暂不支持新增的入口（GLM / Gemini / 小米 MiMo / 云知声）置灰不可点击，悬浮提示统一说明原因。
 */
final class ProviderPickerDialog {

    /** 暂时不提供新增的厂商入口：官网未公开余额接口或接口不能长期使用。 */
    private static final Set<ProviderId> TEMP_UNSUPPORTED_PICK = Set.of(
            ProviderId.GLM, ProviderId.GEMINI, ProviderId.GEMINI_BILLING,
            ProviderId.XIAOMI, ProviderId.UNISOUND, ProviderId.SILICONFLOW,
            ProviderId.KIMI_CODING, ProviderId.LONGCAT);

    /** 置灰入口的悬浮提示文案（按厂商给出准确原因，不编造）。 */
    private static final String UNSUPPORTED_HINT = "当前厂商不可用，计划在未来加入支持";
    /** 官方余额接口已下线的厂商专用提示。 */
    private static final Map<ProviderId, String> UNSUPPORTED_HINTS = Map.of(
            ProviderId.SILICONFLOW, "官方 /user/info 余额接口已于 2026-08-14 下线，暂无法查询余额");

    private ProviderPickerDialog() {
    }

    static Optional<ProviderId> show(Window owner, ProviderId current, boolean light) {
        Dialog<ProviderId> dialog = build(owner, current, light);
        return dialog.showAndWait();
    }

    /** 非阻塞打开（供无头验证钩子使用，不能在动画回调中 showAndWait）。 */
    static void showNonBlocking(Window owner, ProviderId current, boolean light) {
        Dialog<ProviderId> dialog = build(owner, current, light);
        dialog.show();
    }

    private static Dialog<ProviderId> build(Window owner, ProviderId current, boolean light) {
        Dialog<ProviderId> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("选择服务商");
        dialog.getDialogPane().getStylesheets().add(
                SettingsDialog.class.getResource("/com/aibalance/ui/app.css").toExternalForm());
        if (light) {
            dialog.getDialogPane().getStylesheets().add(
                    SettingsDialog.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm());
        }
        dialog.getDialogPane().getStyleClass().add("picker-pane");

        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(cancelType);

        VBox sections = new VBox(18);
        sections.setPadding(new Insets(16, 18, 10, 18));
        sections.getStyleClass().add("picker-sections");

        Map<ProviderId.Region, List<ProviderId>> byRegion = groupByRegion();
        // 分区顺序：国内 → 海外 → 中转站（最下方）
        ProviderId.Region[] order = {
                ProviderId.Region.DOMESTIC, ProviderId.Region.OVERSEAS, ProviderId.Region.GATEWAY};
        for (ProviderId.Region region : order) {
            List<ProviderId> list = byRegion.getOrDefault(region, List.of());
            if (list.isEmpty()) {
                continue;
            }
            Label title = new Label(region.getLabel());
            title.getStyleClass().add("picker-section-title");
            FlowPane grid = new FlowPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.getStyleClass().add("picker-grid");
            for (ProviderId pid : list) {
                Button tile = tile(pid, current, dialog);
                grid.getChildren().add(tile);
            }
            sections.getChildren().addAll(title, grid);
        }

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportWidth(620);
        scroll.setPrefViewportHeight(430);
        scroll.getStyleClass().add("picker-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        // 宽度上限：防止高 DPI/多显示比例下网格弹窗占满或超出屏幕
        dialog.getDialogPane().setPrefWidth(680);
        dialog.getDialogPane().setMinWidth(560);
        dialog.getDialogPane().setMaxWidth(760);
        return dialog;
    }

    private static Map<ProviderId.Region, List<ProviderId>> groupByRegion() {
        Map<ProviderId.Region, List<ProviderId>> map = new EnumMap<>(ProviderId.Region.class);
        for (ProviderId pid : ProviderId.values()) {
            // 已合并/隐藏的入口（如旧百炼账单/余额）不在网格展示
            if (pid.isHidden()) {
                continue;
            }
            map.computeIfAbsent(pid.getRegion(), k -> new java.util.ArrayList<>()).add(pid);
        }
        return map;
    }

    /** 单张卡片：logo（资源缺失回退品牌色首字母徽标）+ 名称 + 描述 tooltip。 */
    private static Button tile(ProviderId pid, ProviderId current, Dialog<ProviderId> dialog) {
        Button b = new Button();
        b.getStyleClass().add("provider-tile");
        if (pid == current) {
            b.getStyleClass().add("provider-tile-selected");
        }
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        box.getChildren().addAll(logoNode(pid), nameNode(pid));
        b.setGraphic(box);
        boolean unsupported = TEMP_UNSUPPORTED_PICK.contains(pid);
        b.setDisable(unsupported);
        if (unsupported) {
            // 置灰不可点击：优先展示厂商专属原因，否则用统一文案
            b.setTooltip(new Tooltip(UNSUPPORTED_HINTS.getOrDefault(pid, UNSUPPORTED_HINT)));
        } else {
            b.setTooltip(new Tooltip(pid.getDescription()));
            b.setOnAction(e -> {
                dialog.setResult(pid);
                dialog.close();
            });
        }
        return b;
    }

    private static Node logoNode(ProviderId pid) {
        Image img = LogoCache.load(pid);
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane();
        holder.setPrefSize(64, 52);
        holder.setMinSize(64, 52);
        holder.setMaxSize(64, 52);
        holder.setAlignment(Pos.CENTER);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(80);
            iv.setFitHeight(50);
            iv.setPreserveRatio(true);
            iv.getStyleClass().add("provider-tile-logo");
            holder.getChildren().add(iv);
        } else {
            // 回退：品牌色圆形底 + 名称首字符
            Circle bg = new Circle(26, 26, 26);
            bg.setStyle("-fx-fill: " + pid.getBrandColor() + ";");
            Label ch = new Label(initial(pid));
            ch.getStyleClass().add("provider-tile-fallback");
            holder.getChildren().addAll(bg, ch);
        }
        return holder;
    }

    private static Node nameNode(ProviderId pid) {
        Label name = new Label(pid.getDisplayName());
        name.getStyleClass().add("provider-tile-name");
        name.setWrapText(true);
        name.setMaxWidth(96);
        // 名称行高固定（最多两行），保证所有格子在名称区等高对齐
        name.setMinHeight(32);
        name.setMaxHeight(32);
        name.setAlignment(Pos.CENTER);
        return name;
    }

    /** 取展示名首字符（优先中文名），用于 logo 缺失时的徽标。 */
    private static String initial(ProviderId pid) {
        String n = pid.getDisplayName();
        if (n == null || n.isBlank()) {
            return "?";
        }
        // 若名称含空格取首个词首字符，否则取首字符
        String first = n.split("\\s+")[0];
        return first.isEmpty() ? "?" : first.substring(0, 1);
    }

    /** 官方 logo 资源缓存（/com/aibalance/ui/logos/<id>.png）。 */
    static final class LogoCache {
        private static final Map<ProviderId, Image> CACHE = new EnumMap<>(ProviderId.class);

        /** 同厂商不同入口复用同一 logo（国际站/Admin/账单等变体不单独出图）。 */
        private static final Map<ProviderId, String> LOGO_ALIAS = Map.of(
                ProviderId.KIMI_INTL, "moonshot",
                ProviderId.OPENAI_ADMIN, "openai",
                ProviderId.GEMINI_BILLING, "gemini",
                ProviderId.BAILIAN_TOKENPLAN, "bailian",
                ProviderId.BAILIAN_ACCOUNT, "bailian",
                ProviderId.BAILIAN_ACCOUNT_INTL, "bailian",
                ProviderId.MINIMAX_INTL, "minimax");

        private LogoCache() {
        }

        static Image load(ProviderId pid) {
            Image img = CACHE.get(pid);
            if (img != null || CACHE.containsKey(pid)) {
                return img;
            }
            try {
                String logoName = LOGO_ALIAS.getOrDefault(pid, pid.name().toLowerCase());
                String path = "/com/aibalance/ui/logos/" + logoName + ".png";
                var stream = SettingsDialog.class.getResourceAsStream(path);
                if (stream != null) {
                    try (stream) {
                        img = new Image(stream, 104, 104, true, true);
                    }
                }
            } catch (Exception ignore) {
                img = null;
            }
            CACHE.put(pid, img);
            return img;
        }
    }
}
