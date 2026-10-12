package com.aibalance.ui;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BalanceSnapshot;
import com.aibalance.model.ProviderCapability;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 展示条目配置对话框：基于账户最近一次快照的真实数据列出可展示条目
 * （能力区块每个一行，如 MiniMax 的每个套餐窗口、OpenAI 的费用/用量/上限；
 * 明细行每个一行，如阿里百炼的每个模型配额），按能力/明细分组展示；
 * 勾选要在卡片展示的内容，条目数较少时可用上下按钮调整显示顺序。
 * 入口：账户卡片 ⋯ 菜单 →「展示条目」。保存结果写回 AccountConfig.displayEntries（条目 key 列表）。
 */
final class DisplayEntriesDialog {

    /** 超过该数量时隐藏排序按钮（数百条目的厂商只做勾选，不做手调顺序）。 */
    private static final int MAX_SORTABLE = 12;

    /** 明细行分组的显示名与色点颜色（非能力区块）。 */
    private static final String DETAIL_GROUP_TITLE = "明细";
    private static final String DETAIL_GROUP_COLOR = "#8A8F9E";

    /** 一行可勾选条目的数据。 */
    private record Entry(String key, String label, String value, String group, String color) {
    }

    private DisplayEntriesDialog() {
    }

    /** 打开对话框；返回用户保存的有序条目 key 列表（仅勾选项），取消返回 null。 */
    static Optional<List<String>> show(Window owner, AccountConfig account,
                                       BalanceSnapshot snapshot, boolean light) {
        Dialog<List<String>> dialog = buildDialog(owner, account, snapshot, light);
        return dialog == null ? Optional.empty() : dialog.showAndWait();
    }

    /** 非阻塞打开（供无头快照验证钩子使用）；快照无可配置条目时返回 null。 */
    static Dialog<List<String>> showNonBlocking(Window owner, AccountConfig account,
                                                BalanceSnapshot snapshot, boolean light) {
        return buildDialog(owner, account, snapshot, light);
    }

    private static Dialog<List<String>> buildDialog(Window owner, AccountConfig account,
                                                    BalanceSnapshot snapshot, boolean light) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("展示条目 · " + account.getProviderId().getDisplayName());
        dialog.getDialogPane().getStylesheets().add(
                SettingsDialog.class.getResource("/com/aibalance/ui/app.css").toExternalForm());
        if (light) {
            dialog.getDialogPane().getStylesheets().add(
                    SettingsDialog.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm());
        }
        dialog.getDialogPane().getStyleClass().add("picker-pane");

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

        // 组装条目：能力区块（metrics）在前，明细行（details）在后，均来自最近一次快照
        List<Entry> entries = new ArrayList<>();
        if (snapshot != null) {
            for (BalanceSnapshot.MetricBlock m : snapshot.metrics()) {
                entries.add(new Entry(m.key(), m.label(), formatMetricValue(m),
                        m.capability().getLabel(), m.capability().getColor()));
            }
            for (BalanceSnapshot.DetailLine d : snapshot.details()) {
                entries.add(new Entry(d.key(), d.label(), d.value(), DETAIL_GROUP_TITLE, DETAIL_GROUP_COLOR));
            }
        }
        if (entries.isEmpty()) {
            dialog.setHeaderText("暂无条目数据");
            VBox empty = new VBox(8);
            empty.setPadding(new Insets(14, 10, 10, 10));
            Label msg = new Label("该账户尚未获取到可配置的数据条目。请先在账户卡片上点击「立即刷新」获取数据，再打开「展示条目」进行配置。");
            msg.getStyleClass().add("form-hint");
            msg.setWrapText(true);
            empty.getChildren().add(msg);
            dialog.getDialogPane().setContent(empty);
            Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
            saveBtn.setDisable(true);
            saveBtn.getStyleClass().add("button-primary");
            Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
            cancelBtn.getStyleClass().add("button-ghost");
            return dialog;
        }

        dialog.setHeaderText("勾选要在账户卡片展示的内容（条目来自该账户最近一次查询结果）");

        // 已保存配置：key 集合 → 勾选状态（行顺序以当前快照为准；用户可再调整）
        java.util.Set<String> saved = new java.util.HashSet<>();
        List<String> savedOrder = account.getDisplayEntries();
        if (savedOrder != null) {
            saved.addAll(savedOrder);
        }
        boolean sortable = entries.size() <= MAX_SORTABLE;

        // 有序条目行（key → 勾选框）；勾选状态持久，交换顺序由 swap 回调驱动
        List<Map.Entry<String, CheckBox>> rows = new ArrayList<>();
        for (Entry e : entries) {
            CheckBox cb = new CheckBox();
            cb.setSelected(saved.contains(e.key()));
            cb.setTooltip(new Tooltip("勾选后在卡片展示「" + e.label() + "」"));
            rows.add(Map.entry(e.key(), cb));
        }

        // 分组顺序：按首次出现（能力区块在前、明细组在后）
        List<String> groupOrder = new ArrayList<>();
        Map<String, String> groupColor = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!groupOrder.contains(e.group())) {
                groupOrder.add(e.group());
                groupColor.put(e.group(), e.color());
            }
        }

        VBox body = new VBox(4);
        body.getStyleClass().add("display-entries");

        // 交换并重建（勾选状态在 rows 中持久）；AtomicReference 持有自引用 lambda 规避"可能未初始化"
        java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<Integer, Integer>> swapRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        swapRef.set((i, j) -> {
            if (i < 0 || j < 0 || i >= rows.size() || j >= rows.size() || i == j) {
                return;
            }
            Map.Entry<String, CheckBox> tmp = rows.get(i);
            rows.set(i, rows.get(j));
            rows.set(j, tmp);
            rebuildBody(body, entries, groupOrder, groupColor, rows, sortable, swapRef.get());
        });

        rebuildBody(body, entries, groupOrder, groupColor, rows, sortable, swapRef.get());

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(340);
        scroll.setMaxHeight(420);
        scroll.getStyleClass().add("dialog-scroll");
        dialog.getDialogPane().setContent(scroll);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveBtn.getStyleClass().add("button-primary");
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        cancelBtn.getStyleClass().add("button-ghost");

        dialog.setResultConverter(bt -> {
            if (bt != saveType) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, CheckBox> e : rows) {
                if (e.getValue().isSelected()) {
                    out.add(e.getKey());
                }
            }
            return out;
        });
        return dialog;
    }

    /** 重建列表：提示 + 分组头 + 条目行（行复用 rows 中的勾选框，保留用户勾选状态）。 */
    private static void rebuildBody(VBox body,
                                    List<Entry> entries,
                                    List<String> groupOrder,
                                    Map<String, String> groupColor,
                                    List<Map.Entry<String, CheckBox>> rows,
                                    boolean sortable,
                                    java.util.function.BiConsumer<Integer, Integer> swapAndRebuild) {
        body.getChildren().clear();
        Label hint = new Label("提示：取消勾选的条目不会显示在卡片上" + (sortable ? "；上移/下移改变展示顺序。" : "。"));
        hint.getStyleClass().add("form-hint");
        hint.setWrapText(true);
        body.getChildren().add(hint);

        for (String group : groupOrder) {
            // 分组头：色点 + 组名 + 条目数
            List<Entry> items = new ArrayList<>();
            for (Entry e : entries) {
                if (e.group().equals(group)) {
                    items.add(e);
                }
            }
            HBox head = new HBox(7);
            head.setAlignment(Pos.CENTER_LEFT);
            head.setPadding(new Insets(8, 4, 2, 4));
            Region dot = new Region();
            dot.getStyleClass().add("metric-dot");
            dot.setStyle("-fx-background-color: " + groupColor.getOrDefault(group, DETAIL_GROUP_COLOR) + ";");
            dot.setPrefSize(8, 8);
            Label groupName = new Label(group);
            groupName.getStyleClass().add("metric-cap");
            Label count = new Label(items.size() + " 条");
            count.getStyleClass().add("detail-label");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            head.getChildren().addAll(dot, groupName, sp, count);
            body.getChildren().add(head);

            for (Entry e : items) {
                body.getChildren().add(buildRow(e, rows, sortable, swapAndRebuild));
            }
        }
    }

    /** 构建一个条目行：勾选框 + 色点 + 标签 + 当前值 +（可选）排序按钮。 */
    private static HBox buildRow(Entry e,
                                 List<Map.Entry<String, CheckBox>> rows,
                                 boolean sortable,
                                 java.util.function.BiConsumer<Integer, Integer> swapAndRebuild) {
        CheckBox cb = null;
        for (Map.Entry<String, CheckBox> r : rows) {
            if (r.getKey().equals(e.key())) {
                cb = r.getValue();
                break;
            }
        }
        if (cb == null) {
            cb = new CheckBox();
            cb.setSelected(true);
            rows.add(Map.entry(e.key(), cb));
        }

        Region dot = new Region();
        dot.getStyleClass().add("metric-dot");
        dot.setStyle("-fx-background-color: " + e.color() + ";");
        dot.setPrefSize(7, 7);

        Label name = new Label(e.label());
        name.getStyleClass().add("detail-label");
        name.setMaxWidth(170);
        Label value = new Label(e.value());
        value.getStyleClass().add("detail-value");
        value.setMaxWidth(110);
        value.setAlignment(Pos.CENTER_RIGHT);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 4, 3, 4));
        row.getStyleClass().add("display-entry-row");
        row.getChildren().addAll(cb, dot, name, sp, value);
        if (sortable) {
            Button up = new Button("↑");
            up.getStyleClass().add("button-ghost");
            up.setCursor(Cursor.HAND);
            up.setTooltip(new Tooltip("上移（提前展示）"));
            up.setOnAction(ev -> {
                int i = rowIndexOf(rows, e.key());
                swapAndRebuild.accept(i, i - 1);
            });
            Button down = new Button("↓");
            down.getStyleClass().add("button-ghost");
            down.setCursor(Cursor.HAND);
            down.setTooltip(new Tooltip("下移（延后展示）"));
            down.setOnAction(ev -> {
                int i = rowIndexOf(rows, e.key());
                swapAndRebuild.accept(i, i + 1);
            });
            row.getChildren().addAll(up, down);
        }
        return row;
    }

    private static int rowIndexOf(List<Map.Entry<String, CheckBox>> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private static String formatMetricValue(BalanceSnapshot.MetricBlock m) {
        if (m.amount() == null) {
            return m.percent() == null ? "" : String.format("%.0f%%", m.percent());
        }
        String cur = m.currency();
        if ("tokens".equalsIgnoreCase(cur)) {
            return String.format("%,d", m.amount().longValue()) + " tok";
        }
        return String.format("%.2f %s", m.amount(), cur == null ? "" : cur);
    }
}
