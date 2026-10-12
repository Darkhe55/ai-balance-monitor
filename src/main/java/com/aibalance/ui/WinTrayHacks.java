package com.aibalance.ui;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Windows 专用窗口样式工具：把悬浮球窗口设为工具窗口（WS_EX_TOOLWINDOW），
 * 使其不显示在任务栏与 Alt+Tab 列表（仅系统托盘常驻）；主界面保持正常任务栏条目。
 */
public final class WinTrayHacks {

    /** WS_EX_TOOLWINDOW：工具窗口（不显示在任务栏与 Alt+Tab）。JNA WinUser 未收录，按 WinUser.h 定义。 */
    private static final int WS_EX_TOOLWINDOW = 0x00000080;

    private WinTrayHacks() {
    }

    /** 是否 Windows 平台。 */
    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    /**
     * 按标题查找窗口并追加工具窗口样式；找不到（如窗口尚未创建）返回 false。
     */
    private static boolean applyToolWindow(String windowTitle) {
        HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
        if (hwnd == null) {
            return false;
        }
        com.sun.jna.platform.win32.BaseTSD.LONG_PTR exPtr =
                User32.INSTANCE.GetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE);
        long ex = exPtr.longValue();
        if ((ex & WS_EX_TOOLWINDOW) != 0) {
            return true;
        }
        User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE,
                com.sun.jna.Pointer.createConstant(ex | WS_EX_TOOLWINDOW));
        // 样式变更后需重绘窗口边框/任务栏状态才能生效
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER
                        | WinUser.SWP_FRAMECHANGED | WinUser.SWP_NOACTIVATE);
        return true;
    }

    /**
     * 悬浮球显示后延迟重试设置工具窗口样式（等待窗口创建完成）。
     * 非 Windows 或无窗口环境静默跳过。
     */
    public static void hideBallFromTaskbar(String windowTitle) {
        if (!isWindows()) {
            return;
        }
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));
        wait.setOnFinished(e -> {
            for (int i = 0; i < 6; i++) {
                if (applyToolWindow(windowTitle)) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
        wait.play();
    }
}
