package com.aibalance.ui;

import com.aibalance.model.AccountConfig;
import com.aibalance.model.BillingMode;
import com.aibalance.model.ProviderId;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 添加/编辑账户对话框。表单校验：名称必填；Key 必填；网关类服务 Base URL 必填。
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    static Optional<AccountConfig> showNew(Window owner, List<AccountConfig> existing, boolean light) {
        Form f = new Form(existing, null);
        Dialog<AccountConfig> dialog = build(f, owner, light);
        return dialog.showAndWait();
    }

    static Optional<AccountConfig> showEdit(Window owner, AccountConfig cfg, List<AccountConfig> existing, boolean light) {
        Form f = new Form(existing, cfg);
        Dialog<AccountConfig> dialog = build(f, owner, light);
        return dialog.showAndWait();
    }

    /** 无阻塞打开（供无头验证钩子使用）。 */
    static void showNewNonBlocking(Window owner, List<AccountConfig> existing, boolean light) {
        showNewNonBlocking(owner, existing, light, ProviderId.DEEPSEEK);
    }

    /** 无阻塞打开，指定表单默认服务（供无头验证钩子按需选择双计费厂商）。 */
    static void showNewNonBlocking(Window owner, List<AccountConfig> existing, boolean light,
                                   ProviderId defaultProvider) {
        Form f = new Form(existing, null);
        f.providerValue = defaultProvider;
        f.syncProviderButton();
        Dialog<AccountConfig> dialog = build(f, owner, light);
        dialog.show();
    }

    private static Dialog<AccountConfig> build(Form f, Window owner, boolean light) {
        Dialog<AccountConfig> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(f.editing ? "编辑账户" : "添加账户");
        dialog.getDialogPane().getStylesheets().add(
                SettingsDialog.class.getResource("/com/aibalance/ui/app.css").toExternalForm());
        if (light) {
            dialog.getDialogPane().getStylesheets().add(
                    SettingsDialog.class.getResource("/com/aibalance/ui/theme-light.css").toExternalForm());
        }
        dialog.getDialogPane().getStyleClass().add("settings-pane");

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 8, 8, 8));

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(88);
        c0.setPrefWidth(185);
        c0.setMaxWidth(240);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);

        int row = 0;

        Label l1 = new Label("服务");
        l1.getStyleClass().add("form-label");
        f.providerButton.getStyleClass().add("provider-picker");
        f.providerButton.setMaxWidth(Double.MAX_VALUE);
        f.providerButton.setCursor(Cursor.HAND);
        f.providerButton.setTooltip(new Tooltip("从图标网格中选择服务商（国内 / 海外 / 中转站）"));
        f.providerButton.setOnAction(e -> {
            Optional<ProviderId> picked = ProviderPickerDialog.show(
                    f.providerButton.getScene().getWindow(), f.providerValue, light);
            picked.ifPresent(pid -> {
                f.providerValue = pid;
                f.syncProviderButton();
                f.onProviderChanged();
                f.afterProviderPick();
            });
        });
        grid.add(l1, 0, row);
        grid.add(f.providerButton, 1, row++);

        Label l2 = new Label("名称");
        l2.getStyleClass().add("form-label");
        f.name.setPromptText("例如：" + (f.providerValue == null ? "" : f.providerValue.getNamePrompt()));
        grid.add(l2, 0, row);
        grid.add(f.name, 1, row++);

        Label l3 = new Label("API Key");
        l3.getStyleClass().add("form-label");
        f.keyLabel = l3;
        f.pf.setPromptText("sk-…（仅保存在本机）");
        f.pf.setMaxWidth(Double.MAX_VALUE);
        f.tf.setPromptText(f.pf.getPromptText());
        f.tf.setMaxWidth(Double.MAX_VALUE);
        // 默认掩码显示（粘贴/输入后 Key 不直接暴露），点击「显示」可临时查看明文
        f.pf.setVisible(true);
        f.pf.setManaged(true);
        f.tf.setVisible(false);
        f.tf.setManaged(false);
        Button reveal = new Button("显示");
        reveal.getStyleClass().add("button-ghost");
        reveal.setCursor(Cursor.HAND);
        reveal.setTooltip(new Tooltip("切换凭证明文与掩码显示（凭证始终仅保存在本机）"));
        reveal.setOnAction(e -> f.toggleReveal(reveal));
        StackPane keyStack = new StackPane(f.pf, f.tf);
        keyStack.setAlignment(Pos.CENTER_LEFT);
        StackPane.setMargin(f.pf, new Insets(0));
        HBox.setHgrow(keyStack, Priority.ALWAYS);
        keyStack.setMaxWidth(Double.MAX_VALUE);
        HBox keyBox = new HBox(8, keyStack, reveal);
        HBox.setHgrow(keyStack, Priority.ALWAYS);
        keyBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(l3, 0, row);
        grid.add(keyBox, 1, row++);

        // AccessKey Secret：仅需双凭证的厂商（阿里云账单）显示，同样掩码 + 显示/隐藏切换
        Label lSecret = new Label("Secret");
        lSecret.getStyleClass().add("form-label");
        f.spf.setVisible(true);
        f.spf.setManaged(true);
        f.stf.setVisible(false);
        f.stf.setManaged(false);
        Button revealSecret = new Button("显示");
        revealSecret.getStyleClass().add("button-ghost");
        revealSecret.setCursor(Cursor.HAND);
        revealSecret.setTooltip(new Tooltip("切换凭证明文与掩码显示（始终仅保存在本机）"));
        revealSecret.setOnAction(e -> f.toggleRevealSecret(revealSecret));
        StackPane secretStack = new StackPane(f.spf, f.stf);
        secretStack.setAlignment(Pos.CENTER_LEFT);
        secretStack.setMaxWidth(Double.MAX_VALUE);
        HBox secretBox = new HBox(8, secretStack, revealSecret);
        HBox.setHgrow(secretStack, Priority.ALWAYS);
        secretBox.setAlignment(Pos.CENTER_LEFT);
        f.secretLabel = lSecret;
        f.secretBox = secretBox;
        grid.add(lSecret, 0, row);
        grid.add(secretBox, 1, row++);

        Label l4 = new Label("Base URL");
        l4.getStyleClass().add("form-label");
        f.baseUrl.setMaxWidth(Double.MAX_VALUE);
        grid.add(l4, 0, row);
        grid.add(f.baseUrl, 1, row++);
        f.baseUrlRow = row - 1;
        f.baseUrlLabel = l4;

        // 附加参数：仅需额外参数的厂商（xAI 的 team_id=xxx）显示
        Label lExtra = new Label("附加参数");
        lExtra.getStyleClass().add("form-label");
        f.extraParams.setMaxWidth(Double.MAX_VALUE);
        f.extraParams.setPromptText("team_id=xxx; key=value");
        grid.add(lExtra, 0, row);
        grid.add(f.extraParams, 1, row++);
        f.extraLabel = lExtra;
        f.extraParams.setVisible(false);
        f.extraParams.setManaged(false);

        Label lBilling = new Label("计费形式");        lBilling.getStyleClass().add("form-label");
        f.billingModeBox = new ComboBox<>();
        f.billingModeBox.setMaxWidth(Double.MAX_VALUE);
        f.billingModeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(com.aibalance.model.BillingMode m) {
                return m == null ? "" : m.getLabel();
            }

            @Override
            public com.aibalance.model.BillingMode fromString(String s) {
                return null;
            }
        });
        f.billingModeBox.setTooltip(new Tooltip("同一厂商可能同时存在按量付费与订阅套餐两种计费，Key 相互独立；选择与你的 Key 匹配的方式"));
        f.billingModeBox.setOnAction(e -> f.onBillingModeChanged());
        f.billingValue = new Label();
        f.billingValue.getStyleClass().add("form-value");
        f.billingValue.setWrapText(true);
        f.billingValue.setMaxWidth(Double.MAX_VALUE);
        StackPane billingStack = new StackPane(f.billingModeBox, f.billingValue);
        billingStack.setAlignment(Pos.CENTER_LEFT);
        billingStack.setMaxWidth(Double.MAX_VALUE);
        grid.add(lBilling, 0, row);
        grid.add(billingStack, 1, row++);

        Label l5 = new Label("状态");
        l5.getStyleClass().add("form-label");
        f.enabled.setText("启用该账户（参与自动刷新）");
        grid.add(l5, 0, row);
        grid.add(f.enabled, 1, row++);

        Label hint = new Label();
        hint.getStyleClass().add("form-hint");
        hint.setWrapText(true);
        // 描述文本可很长（如云厂商授权说明）：必须限制宽度让 wrap 生效，
        // 否则 Label 的 pref 宽度按“单行完整文本”计算，会把整个对话框撑到占满/超出屏幕
        hint.setMaxWidth(Double.MAX_VALUE);
        f.hint = hint;
        grid.add(hint, 0, row, 2, 1);

        // 表单包进滚动区：限制视口高度，内容超高时在框内滚动，避免小屏/高 DPI 下信息超出屏幕
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(200);
        scroll.setMaxHeight(220);
        scroll.getStyleClass().add("dialog-scroll");

        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        // 宽度上限：防止长内容/高 DPI 下对话框占满或超出屏幕；预设舒适宽度
        dialog.getDialogPane().setPrefWidth(560);
        dialog.getDialogPane().setMinWidth(420);
        dialog.getDialogPane().setMaxWidth(720);
        f.onProviderChanged();
        f.registerValidation(dialog.getDialogPane().lookupButton(saveType));
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveBtn.getStyleClass().add("button-primary");
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        cancelBtn.getStyleClass().add("button-ghost");

        dialog.setResultConverter(bt -> bt == saveType ? f.buildResult() : null);
        return dialog;
    }

    /** 表单状态与校验。 */
    private static final class Form {
        ProviderId providerValue;
        BillingMode billingModeValue;
        ComboBox<BillingMode> billingModeBox;
        final Button providerButton = new Button();
        final TextField name = new TextField();
        final PasswordField pf = new PasswordField();
        final TextField tf = new TextField();
        final PasswordField spf = new PasswordField();
        final TextField stf = new TextField();
        final TextField baseUrl = new TextField();
        final TextField extraParams = new TextField();
        Label extraLabel;
        final CheckBox enabled = new CheckBox();
        Label hint;
        Label billingValue;
        Label secretLabel;
        HBox secretBox;
        Label keyLabel;
        Label baseUrlLabel;
        int baseUrlRow;
        ProviderId prevPid;
        final boolean editing;
        final String editId;
        final List<AccountConfig> existing;
        /** 保存按钮（由 build 注入），picker 选择后需刷新校验状态 */
        Node saveNode;

        Form(List<AccountConfig> existing, AccountConfig editTarget) {
            this.existing = existing;
            this.editing = editTarget != null;
            this.editId = editTarget == null ? null : editTarget.getId();
            if (editing) {
                providerValue = editTarget.getProviderId();
                name.setText(editTarget.getName());
                String key = editTarget.getApiKey();
                pf.setText(key == null ? "" : key);
                tf.setText(key == null ? "" : key);
                String secret = editTarget.getSecret();
                spf.setText(secret == null ? "" : secret);
                stf.setText(secret == null ? "" : secret);
                baseUrl.setText(editTarget.getBaseUrl() == null ? "" : editTarget.getBaseUrl());
                extraParams.setText(editTarget.getExtraParams() == null ? "" : editTarget.getExtraParams());
                enabled.setSelected(editTarget.isEnabled());
                // 编辑时沿用已保存的计费形态；缺失时按厂商默认
                String saved = editTarget.getBillingMode();
                if (saved != null && !saved.isBlank()) {
                    try {
                        billingModeValue = BillingMode.valueOf(saved);
                    } catch (IllegalArgumentException ignored) {
                        billingModeValue = null;
                    }
                }
            } else {
                providerValue = ProviderId.DEEPSEEK;
                enabled.setSelected(true);
            }
            syncProviderButton();
        }

        /** 服务按钮文本与当前值同步。 */
        void syncProviderButton() {
            if (providerValue == null) {
                providerButton.setText("选择服务…");
                return;
            }
            providerButton.setText(providerValue.getDisplayName() + "  ▾");
        }

        /** picker 选择服务后刷新保存按钮可用状态。 */
        void afterProviderPick() {
            if (saveNode != null) {
                saveNode.setDisable(!valid());
            }
        }

        void onProviderChanged() {
            ProviderId pid = providerValue;
            ProviderId prev = prevPid;
            prevPid = pid;
            // 所有服务均显示 Base URL：无官方默认域名的服务必填，其余预填默认、可手动修改。
            // 阿里云百炼·账户余额与账单为 SDK 风格：Base URL 字段语义为「Region（地域）」
            boolean regionStyle = pid == ProviderId.BAILIAN_ACCOUNT;
            if (baseUrlLabel != null) {
                baseUrlLabel.setText(regionStyle ? "Region（地域）" : "Base URL");
            }
            if (pid != null) {
                String text = baseUrl.getText();
                boolean untouched = text == null || text.isBlank()
                        || (prev != null && text.strip().equals(prev.getDefaultBaseUrl()));
                if (untouched) {
                    baseUrl.setText(regionStyle ? "cn-beijing" : pid.getDefaultBaseUrl());
                }
                baseUrl.setPromptText(regionStyle
                        ? "阿里云地域，如 cn-beijing / ap-southeast-1（默认 cn-beijing，国内地域自动使用官方 BssOpenApi 端点）"
                        : (pid.hasDefaultBaseUrl()
                        ? "默认 " + pid.getDefaultBaseUrl() + "，可修改"
                        : "无官方默认域名，必填（https://…）"));
            }
            if (hint != null) {
                hint.setText(pid == null ? "" : pid.getDescription());
            }
            boolean needSecret = pid != null && needsSecret(pid);
            if (secretLabel != null && secretBox != null) {
                // 云签名厂商的 Secret 行标签随厂商切换（AccessKey Secret / SecretKey / Secret Access Key）
                secretLabel.setText(pid == null ? "Secret" : pid.getSecretFieldLabel());
                secretLabel.setVisible(needSecret);
                secretLabel.setManaged(needSecret);
                secretBox.setVisible(needSecret);
                secretBox.setManaged(needSecret);
            }
            // 凭证字段 1 标签随厂商切换（API Key / AccessKey ID / SecretId），避免把云凭证误填为模型调用 Key
            if (keyLabel != null) {
                keyLabel.setText(pid == null ? "API Key" : pid.getKeyFieldLabel());
            }
            if (pid != null) {
                String kp = pid.getKeyPrompt();
                pf.setPromptText(kp);
                tf.setPromptText(kp);
                String sp = pid.getSecretPrompt();
                spf.setPromptText(sp == null ? "" : sp);
                stf.setPromptText(sp == null ? "" : sp);
            }
            boolean needExtra = pid == ProviderId.XAI;
            if (extraLabel != null && extraParams != null) {
                extraLabel.setVisible(needExtra);
                extraLabel.setManaged(needExtra);
                extraParams.setVisible(needExtra);
                extraParams.setManaged(needExtra);
            }
            if (billingValue != null) {
                syncBillingControl();
            }
            if (name.getText() == null || name.getText().isBlank()) {
                name.setPromptText(pid == null ? "" : "例如：" + pid.getNamePrompt());
            }
        }

        /** 按厂商填充计费形态控件：双计费厂商显示下拉，单一计费厂商固定显示标注。 */
        void syncBillingControl() {
            ProviderId pid = providerValue;
            if (pid == null) {
                if (billingModeBox != null) {
                    billingModeBox.getItems().clear();
                    billingModeBox.setVisible(false);
                    billingModeBox.setManaged(false);
                    billingValue.setText("");
                    billingValue.setVisible(true);
                    billingValue.setManaged(true);
                }
                return;
            }
            if (billingModeBox == null) {
                return;
            }
            if (pid.hasDualBilling()) {
                billingValue.setVisible(false);
                billingValue.setManaged(false);
                billingModeBox.setVisible(true);
                billingModeBox.setManaged(true);
                billingModeBox.getItems().setAll(pid.getSupportedBillingModes());
                BillingMode current = billingModeValue;
                boolean contains = false;
                for (BillingMode m : pid.getSupportedBillingModes()) {
                    if (m == current) {
                        contains = true;
                        break;
                    }
                }
                if (!contains) {
                    current = pid.getDefaultBillingMode();
                }
                billingModeValue = current;
                billingModeBox.setValue(current);
                billingModeBox.setPromptText(current == null ? "" : current.getLabel());
            } else {
                billingModeBox.setVisible(false);
                billingModeBox.setManaged(false);
                billingValue.setVisible(true);
                billingValue.setManaged(true);
                billingValue.setText(pid.getBillingModeLabel());
                billingModeValue = pid.getSupportedBillingModes()[0];
            }
        }

        /** 计费形态下拉切换后：记录选择并更新提示。 */
        void onBillingModeChanged() {
            BillingMode m = billingModeBox == null ? null : billingModeBox.getValue();
            if (m != null) {
                billingModeValue = m;
            }
            if (billingModeBox != null && m != null) {
                billingModeBox.setTooltip(new Tooltip(m.getDescription()));
            }
        }

        void registerValidation(Node save) {
            saveNode = save;
            ChangeListener<String> l = (ob, o, n) -> save.setDisable(!valid());
            name.textProperty().addListener(l);
            pf.textProperty().addListener(l);
            tf.textProperty().addListener(l);
            spf.textProperty().addListener(l);
            stf.textProperty().addListener(l);
            baseUrl.textProperty().addListener(l);
            extraParams.textProperty().addListener(l);
        }

        boolean valid() {
            ProviderId pid = providerValue;
            if (pid == null || name.getText() == null || name.getText().isBlank()) {
                return false;
            }
            if (keyText().isBlank()) {
                return false;
            }
            // 双凭证厂商（云签名接口）Secret 必填
            if (pid != null && needsSecret(pid) && secretText().isBlank()) {
                return false;
            }
            // 无官方默认域名的服务（网关/百炼）Base URL 必填；其余允许留空（使用默认）
            if (!pid.hasDefaultBaseUrl()
                    && (baseUrl.getText() == null || baseUrl.getText().isBlank())) {
                return false;
            }
            return true;
        }

        String keyText() {
            return tf.isVisible() ? tf.getText() : pf.getText();
        }

        String secretText() {
            return stf.isVisible() ? stf.getText() : spf.getText();
        }

        void toggleReveal(Button b) {
            boolean reveal = !tf.isVisible();
            tf.setVisible(reveal);
            tf.setManaged(reveal);
            pf.setVisible(!reveal);
            pf.setManaged(!reveal);
            if (reveal) {
                tf.setText(pf.getText());
            } else {
                pf.setText(tf.getText());
            }
            b.setText(reveal ? "隐藏" : "显示");
        }

        void toggleRevealSecret(Button b) {
            boolean reveal = !stf.isVisible();
            stf.setVisible(reveal);
            stf.setManaged(reveal);
            spf.setVisible(!reveal);
            spf.setManaged(!reveal);
            if (reveal) {
                stf.setText(spf.getText());
            } else {
                spf.setText(stf.getText());
            }
            b.setText(reveal ? "隐藏" : "显示");
        }

        AccountConfig buildResult() {
            ProviderId pid = providerValue;
            AccountConfig cfg = editing ? findExisting() : new AccountConfig();
            if (cfg.getId() == null) {
                cfg.setId(UUID.randomUUID().toString());
            }
            cfg.setProviderId(pid);
            cfg.setName(name.getText().strip());
            cfg.setApiKey(keyText().strip());
            cfg.setSecret(needsSecret(pid) ? secretText().strip() : null);
            cfg.setBaseUrl(baseUrl.getText() == null ? "" : baseUrl.getText().strip());
            cfg.setBillingMode(billingModeValue == null ? null : billingModeValue.name());
            cfg.setEnabled(enabled.isSelected());
            cfg.setExtraParams(pid == ProviderId.XAI
                    ? (extraParams.getText() == null ? "" : extraParams.getText().strip()) : null);
            return cfg;
        }

        /** 需要双凭证（云签名接口）的厂商。 */
        @SuppressWarnings("deprecation") // 旧百炼入口枚举仅用于兼容旧配置迁移
        static boolean needsSecret(ProviderId pid) {
            return pid == ProviderId.BAILIAN_ACCOUNT || pid == ProviderId.BAILIAN_BILLING
                    || pid == ProviderId.QWEN_ACCOUNT || pid == ProviderId.BAILIAN_TOKENPLAN
                    || pid == ProviderId.VOLCENGINE || pid == ProviderId.TENCENT || pid == ProviderId.BAIDU;
        }

        private AccountConfig findExisting() {
            for (AccountConfig a : existing) {
                if (a.getId().equals(editId)) {
                    return a;
                }
            }
            return new AccountConfig();
        }
    }
}
