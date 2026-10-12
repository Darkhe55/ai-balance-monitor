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

/**
 * SVG 图标渲染工具：把 SVGPath 路径按 24×24 视图盒渲染为 JavaFX Image，
 * 并提供 AWT BufferedImage 转换（系统托盘 TrayIcon 需要）。
 *
 * <p>图标语义：表盘式「监控仪表」——外圆环 + 内指针，表达"余额/用量实时监测"。
 */
final class SvgIcon {

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
