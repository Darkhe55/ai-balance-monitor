package com.aibalance.ui;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Windows 系统标题栏明暗控制。
 *
 * <p>标题栏（窗口标题 + 最小化/最大化/关闭按钮那一圈）由 DWM 绘制，不属于 JavaFX 场景，
 * 换 stylesheet 影响不到它——深色主题下它仍是系统默认白底，必须通过 DWM 的
 * {@code DWMWA_USE_IMMERSIVE_DARK_MODE} 属性单独通知系统。jna-platform 未内置该绑定，
 * 故此处自行声明 {@code DwmSetWindowAttribute} 接口。
 *
 * <p>纯外观增强：非 Windows、窗口尚未创建或 DWM 调用失败时一律静默跳过，不影响功能。
 */
final class WinTitleBar {

    /** Win10 2004+ / Win11 使用的属性号。 */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    /** Win10 1809–2004 的旧属性号，部分系统只认这个，两个都下发最稳。 */
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19;

    private interface DwmApi extends Library {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        int DwmSetWindowAttribute(HWND hwnd, int attribute, Pointer value, int size);
    }

    private WinTitleBar() {
    }

    /**
     * 按窗口标题定位 HWND 并设置标题栏明暗。
     *
     * <p>先判断平台再触碰 JNA：非 Windows 上 {@code User32} 根本加载不了，
     * 静态初始化 DwmApi 会直接抛异常。
     */
    static void applyDarkMode(String windowTitle, boolean dark) {
        if (windowTitle == null || !isWindows()) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
            if (hwnd == null) {
                // 窗口尚未创建（首帧前）或标题已改，等下一次调用即可
                return;
            }
            Memory value = new Memory(Integer.BYTES);
            value.setInt(0, dark ? 1 : 0);
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                    value, Integer.BYTES);
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY,
                    value, Integer.BYTES);
        } catch (Throwable t) {
            System.err.println("TITLEBAR_DARK_FAIL: " + t);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }
}