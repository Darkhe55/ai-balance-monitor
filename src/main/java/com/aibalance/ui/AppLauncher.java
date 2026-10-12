package com.aibalance.ui;

import javafx.application.Application;

/**
 * 入口。独立于 Application 的普通 main 类，
 * 规避 JavaFX 模块加载在部分 JDK 下的启动问题。
 */
public final class AppLauncher {

    private AppLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
