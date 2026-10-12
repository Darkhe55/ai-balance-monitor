package com.aibalance.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Windows 系统托盘常驻：悬浮球模式下主窗口隐藏后，在托盘区显示图标，
 * 右键菜单提供「显示完整界面」「关闭软件」。托盘不可用（无托盘环境）时静默跳过。
 */
final class TrayManager {

    private TrayIcon trayIcon;
    private boolean installed;

    /**
     * 安装托盘图标。
     *
     * @param iconImage 托盘图标（JavaFX Image，自动转 AWT BufferedImage）
     * @param onShow    点击「显示完整界面」
     * @param onExit    点击「关闭软件」
     * @return 是否安装成功（无托盘环境返回 false）
     */
    boolean install(Image iconImage, Runnable onShow, Runnable onExit) {
        try {
            if (!SystemTray.isSupported()) {
                return false;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("显示完整界面");
            show.addActionListener(e -> Platform.runLater(onShow));
            MenuItem exit = new MenuItem("关闭软件");
            exit.addActionListener(e -> Platform.runLater(onExit));
            menu.add(show);
            menu.addSeparator();
            menu.add(exit);

            trayIcon = new TrayIcon(SvgIcon.toBufferedImage(iconImage), "AI 余额监控", menu);
            trayIcon.setImageAutoSize(true);
            // 左键单击托盘图标：与"显示完整界面"同义（常见惯例，便于快速恢复）
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        Platform.runLater(onShow);
                    }
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
            installed = true;
            return true;
        } catch (Exception ex) {
            // 托盘不可用（极简桌面/远程会话等）不影响主流程
            installed = false;
            return false;
        }
    }

    /** 移除托盘图标（应用退出前调用）。 */
    void remove() {
        if (installed && trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
                // 忽略移除失败
            }
            installed = false;
            trayIcon = null;
        }
    }

    boolean isInstalled() {
        return installed;
    }
}
