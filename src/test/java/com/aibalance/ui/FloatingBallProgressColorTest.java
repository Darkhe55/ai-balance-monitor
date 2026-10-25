package com.aibalance.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 悬浮球进度条颜色：剩余 100%（满额）应为绿、50% 应为黄、接近 0% 应为红，
 * 中间为平滑过渡色（绿→黄→红），不得出现颜色倒挂。
 */
class FloatingBallProgressColorTest {

    @Test
    void fullIsGreen() {
        assertEquals("#34d399", FloatingBallView.progressColor(100));
    }

    @Test
    void halfIsYellow() {
        assertEquals("#fbbf24", FloatingBallView.progressColor(50));
    }

    @Test
    void exhaustedIsRed() {
        assertEquals("#f87171", FloatingBallView.progressColor(0));
    }

    @Test
    void threeQuarterTransitionsGreenToYellow() {
        // 剩 75%：绿(52,211,153) 与 黄(251,191,36) 的中点
        assertEquals("#98c95f", FloatingBallView.progressColor(75));
    }

    @Test
    void quarterTransitionsYellowToRed() {
        // 剩 25%：黄(251,191,36) 与 红(248,113,113) 的中点
        assertEquals("#fa984b", FloatingBallView.progressColor(25));
    }
}