package com.aibalance.ui;

import com.aibalance.service.HttpSupport;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 全局设置对话框：自动刷新开关、刷新间隔、网络模式（跟随系统/直连）、
 * 悬浮球自动收回开关。保存后立即生效并持久化。
 */
final class PreferencesDialog {

    /** 设置项当前值（对话框输入输出）。 */
    static final class Preferences {
        boolean autoRefresh;
        int intervalSeconds;
        String proxyMode;
        boolean autoCollapseBall;

        Preferences(boolean autoRefresh, int intervalSeconds, String proxyMode, boolean autoCollapseBall) {
            this.autoRefresh = autoRefresh;
            this.intervalSeconds = intervalSeconds;
            this.proxyMode = proxyMode;
            this.autoCollapseBall = autoCollapseBall;
        }
    }

    private PreferencesDialog() {
    }

    /** 打开设置对话框；用户点「保存」时把新值交给 onApply 应用。 */
    static void show(Window owner, Preferences current, boolean light, Consumer<Preferences> onApply) {
        Dialog<Preferences> dialog = buildDialog(owner, current, light, onApply);
        dialog.setResultConverter(bt -> {
            if (bt == null || bt.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return collect(dialog);
        });
        dialog.showAndWait().ifPresent(onApply);
    }

    /** 非阻塞打开设置对话框（供快照验证等不能在动画/布局脉冲中调用 showAndWait 的场景）。 */
    static void showNonBlocking(Window owner, Preferences current, boolean light, Consumer<Preferences> onApply) {
        Dialog<Preferences> dialog = buildDialog(owner, current, light, onApply);
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveTypeKey);
        saveBtn.setOnAction(e -> {
            onApply.accept(collect(dialog));
            dialog.hide();
        });
        dialog.show();
    }

    private static final ButtonType saveTypeKey = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);

    /** 从对话框控件收集设置值。 */
    private static Preferences collect(Dialog<Preferences> dialog) {
        CheckBox autoRefresh = (CheckBox) dialog.getDialogPane().getContent()
                .lookupAll(".pref-auto-refresh").stream().findFirst().orElse(null);
        @SuppressWarnings("unchecked")
        ComboBox<String> interval = (ComboBox<String>) dialog.getDialogPane().getContent()
                .lookupAll(".combo-interval").stream()
                .filter(c -> c instanceof ComboBox && ((ComboBox<?>) c).getItems().contains("15 秒"))
                .findFirst().orElse(null);
        @SuppressWarnings("unchecked")
        ComboBox<String> proxy = (ComboBox<String>) dialog.getDialogPane().getContent()
                .lookupAll(".combo-interval").stream()
                .filter(c -> c instanceof ComboBox && ((ComboBox<?>) c).getItems().contains("跟随系统"))
                .findFirst().orElse(null);
        CheckBox autoCollapse = (CheckBox) dialog.getDialogPane().getContent()
                .lookupAll(".pref-auto-collapse").stream().findFirst().orElse(null);
        int seconds = switch (interval.getValue()) {
            case "15 秒" -> 15;
            case "30 秒" -> 30;
            case "5 分钟" -> 300;
            default -> 60;
        };
        return new Preferences(autoRefresh.isSelected(), seconds,
                "直连".equals(proxy.getValue()) ? HttpSupport.MODE_DIRECT : HttpSupport.MODE_SYSTEM,
                autoCollapse.isSelected());
    }

    /** 构建设置对话框（不含结果转换与展示）。 */
    private static Dialog<Preferences> buildDialog(Window owner, Preferences current, boolean light,
                                                   Consumer<Preferences> onApply) {
        Dialog<Preferences> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("设置");
        dialog.getDialogPane().getStylesheets().add(
                PreferencesDialog.class.getResource("/com/aibalance/ui/app.css").toExternalForm());
        if (light) {
            dialog.getDialogPane().getStylesheets().add(
                    PreferencesDialog.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm());
        }
        dialog.getDialogPane().getStyleClass().add("settings-pane");

        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveTypeKey, cancelType);

        CheckBox autoRefresh = new CheckBox("开启自动刷新（按间隔轮询各账户余额）");
        autoRefresh.getStyleClass().add("pref-auto-refresh");
        autoRefresh.setSelected(current.autoRefresh);

        ComboBox<String> interval = new ComboBox<>();
        interval.getStyleClass().add("combo-interval");
        interval.setItems(FXCollections.observableArrayList("15 秒", "30 秒", "60 秒", "5 分钟"));
        interval.setValue(switch (current.intervalSeconds) {
            case 15 -> "15 秒";
            case 30 -> "30 秒";
            case 300 -> "5 分钟";
            default -> "60 秒";
        });
        interval.setDisable(!current.autoRefresh);
        autoRefresh.selectedProperty().addListener((ob, o, n) -> interval.setDisable(!n));

        ComboBox<String> proxy = new ComboBox<>();
        proxy.getStyleClass().add("combo-interval");
        proxy.setItems(FXCollections.observableArrayList("跟随系统", "直连"));
        proxy.setValue(HttpSupport.MODE_DIRECT.equals(current.proxyMode) ? "直连" : "跟随系统");
        proxy.setTooltip(new Tooltip("网络模式：跟随系统代理 / 直连。出现 TLS 握手失败时可切换「直连」排查"));

        CheckBox autoCollapse = new CheckBox("悬浮球自动收回（鼠标离开缩略面板 3 秒后自动收起为悬浮球）");
        autoCollapse.getStyleClass().add("pref-auto-collapse");
        autoCollapse.setSelected(current.autoCollapseBall);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 12, 8, 12));

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(84);
        c0.setPrefWidth(104);
        c0.setMaxWidth(132);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);

        Label l1 = new Label("自动刷新");
        l1.getStyleClass().add("form-label");
        grid.add(l1, 0, 0);
        grid.add(autoRefresh, 1, 0);

        Label l2 = new Label("刷新间隔");
        l2.getStyleClass().add("form-label");
        interval.setMaxWidth(Double.MAX_VALUE);
        grid.add(l2, 0, 1);
        grid.add(interval, 1, 1);

        Label l3 = new Label("网络模式");
        l3.getStyleClass().add("form-label");
        proxy.setMaxWidth(Double.MAX_VALUE);
        grid.add(l3, 0, 2);
        grid.add(proxy, 1, 2);

        Label l4 = new Label("悬浮球");
        l4.getStyleClass().add("form-label");
        grid.add(l4, 0, 3);
        grid.add(autoCollapse, 1, 3);

        Label hint = new Label("自动刷新关闭后仅手动刷新；间隔以秒计，改动立即生效。");
        hint.getStyleClass().add("form-hint");
        hint.setWrapText(true);
        grid.add(hint, 0, 4, 2, 1);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(180);
        scroll.setMaxHeight(200);
        scroll.getStyleClass().add("dialog-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveTypeKey);
        saveBtn.getStyleClass().add("button-primary");
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        cancelBtn.getStyleClass().add("button-ghost");

        return dialog;
    }
}
