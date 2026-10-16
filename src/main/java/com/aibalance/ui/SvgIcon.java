package com.aibalance.ui;

import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * SVG 图标渲染工具：把 SVGPath 路径按 24×24 视图盒渲染为 JavaFX Image，
 * 并提供 AWT BufferedImage 转换（系统托盘 TrayIcon 需要）。
 *
 * <p>图标语义：表盘式「监控仪表」——外圆环 + 内指针，表达"余额/用量实时监测"。
 */
final class SvgIcon {

    /** 窗口图标位图：暗色圆角底 + 蓝色钱包与用量柱（与 exe 图标同一套设计）。 */
    private static final String WINDOW_ICON_RESOURCE = "/com/aibalance/ui/app-icon.png";

    private SvgIcon() {
    }

    /** 24×24 视图盒内的仪表盘路径（外环 + 内环 + 指针，EVEN_ODD 挖空成环带）。 */
    static final String GAUGE_PATH = """
            M12 1.6a10.4 10.4 0 1 0 0 20.8 10.4 10.4 0 0 0 0-20.8z
            M12 4.6a7.4 7.4 0 1 1 0 14.8 7.4 7.4 0 0 1 0-14.8z
            M12 6.8l5 7.2-5-1.7-5 1.7 5-7.2z
            M12 14.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2z
            """;

    /**
     * 渲染 SVG 图标为 JavaFX Image。
     *
     * @param size  输出像素边长（正方形）
     * @param color 图标颜色
     */
    static Image render(double size, Color color) {
        return render(size, color, GAUGE_PATH);
    }

    static Image render(double size, Color color, String pathData) {
        SVGPath sp = new SVGPath();
        sp.setContent(pathData);
        sp.setFill(color);
        sp.setStroke(null);
        Group g = new Group(sp);
        double scale = size / 24.0;
        g.getTransforms().add(new Scale(scale, scale));
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage img = new WritableImage((int) Math.ceil(size), (int) Math.ceil(size));
        g.snapshot(params, img);
        return img;
    }

    /**
     * 窗口图标：优先用位图 {@code app-icon.png}（与 exe 图标同一套设计）。
     *
     * <p>位图资源缺失或解码失败时回退到单色表盘，保证源码可独立编译运行。
     * 托盘图标不走这里——托盘只有 16–32 px，位图在该尺寸下不可读，仍用矢量表盘。
     */
    static Image windowIcon() {
        try (InputStream in = SvgIcon.class.getResourceAsStream(WINDOW_ICON_RESOURCE)) {
            if (in != null) {
                Image img = new Image(in);
                if (!img.isError() && img.getWidth() > 0 && img.getHeight() > 0) {
                    return img;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("WINDOW_ICON_FALLBACK: " + e);
        }
        return render(32, Color.web("#E07BA8"));
    }

    /**
     * 窗口图标多尺寸列表（16/32/48/256）：Windows 任务栏/标题栏按当前 DPI 选择最合适的尺寸，
     * 避免只提供大图时任务栏图标回退为默认 Java 图标。
     */
    static java.util.List<Image> windowIcons() {
        Image base = windowIcon();
        java.util.List<Image> out = new java.util.ArrayList<>();
        for (int size : new int[]{16, 32, 48, 256}) {
            out.add(size == (int) base.getWidth() ? base : scaled(base, size));
        }
        return out;
    }

    /** 将图片等比缩放到指定边长（平滑采样）。 */
    private static Image scaled(Image src, int size) {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size);
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(true);
        gc.drawImage(src, 0, 0, size, size);
        return canvas.snapshot(new SnapshotParameters(), null);
    }

    /** JavaFX Image → AWT BufferedImage（托盘图标需要）。 */
    static BufferedImage toBufferedImage(Image img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        return bi;
    }
}
