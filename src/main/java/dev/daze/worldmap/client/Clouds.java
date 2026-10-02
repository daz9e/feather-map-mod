package dev.daze.worldmap.client;

import net.minecraft.client.gui.GuiGraphics;

/** Пиксельные облака-пергамент, которые затягивают экран и расходятся при открытии/закрытии карты. */
public final class Clouds {
    public static final int CELL = 4;
    private static final int LIGHT = 0xF3E9CE, MID = 0xE6D6AE, DARK = 0xD3BE8E;

    private Clouds() {}

    /** cover: 0 — облаков нет, 1 — экран полностью закрыт. */
    public static void draw(GuiGraphics g, int w, int h, float cover, float time, float alpha, float scale) {
        if (cover <= 0.001f || alpha <= 0.01f) return;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        w = (int) Math.ceil(w / scale);
        h = (int) Math.ceil(h / scale);
        int cols = w / CELL + 2, rows = h / CELL + 2;
        float thr = 1f - cover * 1.2f;
        boolean[] cloud = new boolean[cols * rows];
        float[] val = new float[cols * rows];
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                float dx = (x - cols / 2f) / (cols / 2f), dy = (y - rows / 2f) / (rows / 2f);
                float radial = 1f - Math.min(1f, (float) Math.sqrt(dx * dx * 0.8f + dy * dy * 1.2f));
                float v = 0.68f * fbm(x / 16f + time * 0.25f, y / 6f) + 0.32f * radial;
                val[y * cols + x] = v;
                cloud[y * cols + x] = v > thr;
            }
        int a = (int) (alpha * 255) << 24;
        for (int y = 0; y < rows; y++) {
            int runStart = -1, runColor = 0;
            for (int x = 0; x <= cols; x++) {
                int color = -1;
                if (x < cols && cloud[y * cols + x]) {
                    boolean bottom = y + 1 >= rows || !cloud[(y + 1) * cols + x];
                    boolean edge = val[y * cols + x] < thr + 0.05f;
                    color = bottom ? DARK : edge ? MID : LIGHT;
                }
                if (color != runColor || x == cols) {
                    if (runStart >= 0 && runColor != -1)
                        g.fill(runStart * CELL, y * CELL, x * CELL, (y + 1) * CELL, a | runColor);
                    runStart = x;
                    runColor = color;
                }
            }
        }
        g.pose().popPose();
    }

    private static float fbm(float x, float y) {
        return noise(x, y) * 0.55f + noise(x * 2.1f + 17, y * 2.1f + 5) * 0.3f + noise(x * 4.3f + 41, y * 4.3f + 9) * 0.15f;
    }

    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
