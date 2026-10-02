package dev.daze.worldmap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if <26.1
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
//? if <1.21.6 {
import com.mojang.math.Axis;
//?} else {
/*import net.minecraft.client.renderer.RenderPipelines;
*///?}
//? if >=1.21.2 && <1.21.6 {
/*import net.minecraft.client.renderer.RenderType;
*///?}

/**
 * Обёртка над GuiGraphics: весь рисующий код мода идёт через неё, а различия версий (PoseStack → 2D-матрица,
 * глобальная прозрачность, blit, подсказки, ножницы) спрятаны здесь.
 */
public final class Gfx {
    public final GuiGraphics g;
    private final Font font = Minecraft.getInstance().font;
    private float alpha = 1f;

    public Gfx(GuiGraphics g) {
        this.g = g;
    }

    public int width() {
        return g.guiWidth();
    }

    public int height() {
        return g.guiHeight();
    }

    // ---------- матрица ----------

    public void push() {
        //? if <1.21.6 {
        g.pose().pushPose();
        //?} else
        /*g.pose().pushMatrix();*/
    }

    public void pop() {
        //? if <1.21.6 {
        g.pose().popPose();
        //?} else
        /*g.pose().popMatrix();*/
    }

    public void translate(float x, float y) {
        translate(x, y, 0);
    }

    /** z — только для порядка слоёв в старых версиях; с 1.21.6 интерфейс рисуется по порядку вызовов. */
    public void translate(float x, float y, float z) {
        //? if <1.21.6 {
        g.pose().translate(x, y, z);
        //?} else
        /*g.pose().translate(x, y);*/
    }

    public void scale(float s) {
        scale(s, s);
    }

    public void scale(float sx, float sy) {
        //? if <1.21.6 {
        g.pose().scale(sx, sy, 1);
        //?} else
        /*g.pose().scale(sx, sy);*/
    }

    public void rotate(float degrees) {
        //? if <1.21.6 {
        g.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        //?} else
        /*g.pose().rotate((float) Math.toRadians(degrees));*/
    }

    // ---------- прозрачность ----------

    /** Общая прозрачность для всего, что рисуется дальше (1 — сбросить). */
    public void alpha(float a) {
        alpha = a;
        //? if <1.21.2
        g.setColor(1, 1, 1, a);
    }

    private int c(int argb) {
        //? if <1.21.2 {
        return argb;
        //?} else {
        /*if (alpha >= 0.999f) return argb;
        int a = Math.round((argb >>> 24) * Math.max(0f, alpha));
        return a << 24 | (argb & 0xFFFFFF);
        *///?}
    }

    // ---------- примитивы ----------

    public void fill(int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, c(argb));
    }

    public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        g.fillGradient(x0, y0, x1, y1, c(top), c(bottom));
    }

    public void text(String s, int x, int y, int argb, boolean shadow) {
        //? if <26.1 {
        g.drawString(font, s, x, y, c(argb), shadow);
        //?} else
        /*g.text(font, s, x, y, c(argb), shadow);*/
    }

    public void text(Component s, int x, int y, int argb, boolean shadow) {
        //? if <26.1 {
        g.drawString(font, s, x, y, c(argb), shadow);
        //?} else
        /*g.text(font, s, x, y, c(argb), shadow);*/
    }

    public void centered(Component s, int cx, int y, int argb) {
        //? if <26.1 {
        g.drawCenteredString(font, s, cx, y, c(argb));
        //?} else
        /*g.centeredText(font, s, cx, y, c(argb));*/
    }

    /** Вся текстура texW×texH, растянутая в прямоугольник w×h. */
    public void blit(ResourceLocation tex, int x, int y, int w, int h, int texW, int texH) {
        //? if <1.21.2 {
        g.blit(tex, x, y, w, h, 0f, 0f, texW, texH, texW, texH);
        //?} elif <1.21.6 {
        /*g.blit(RenderType::guiTextured, tex, x, y, 0f, 0f, w, h, texW, texH, texW, texH, c(0xFFFFFFFF));
        *///?} else {
        /*g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, w, h, texW, texH, texW, texH, c(0xFFFFFFFF));
        *///?}
    }

    public void item(ItemStack stack, int x, int y) {
        //? if <26.1 {
        g.renderItem(stack, x, y);
        //?} else
        /*g.item(stack, x, y);*/
    }

    public void tooltip(Component text, int x, int y) {
        //? if <1.21.6 {
        g.renderTooltip(font, text, x, y);
        //?} else
        /*g.setTooltipForNextFrame(font, text, x, y);*/
    }

    /**
     * Ножницы для прямоугольника в текущих координатах, если текущая матрица — только масштаб scale
     * (старые версии принимают экранные координаты, новые сами учитывают матрицу).
     */
    public void scissor(int x0, int y0, int x1, int y1, float scale) {
        //? if <1.21.6 {
        g.enableScissor((int) (x0 * scale), (int) (y0 * scale), (int) (x1 * scale), (int) (y1 * scale));
        //?} else
        /*g.enableScissor(x0, y0, x1, y1);*/
    }

    public void endScissor() {
        g.disableScissor();
    }

    /** Лицо игрока со скина. */
    public void face(PlayerInfo info, int x, int y, int size) {
        //? if <1.20.2 {
        PlayerFaceRenderer.draw(g, info.getSkinLocation(), x, y, size);
        //?} elif <1.21.2 {
        /*PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size);
        *///?} elif <26.1 {
        /*PlayerFaceRenderer.draw(g, info.getSkin(), x, y, size, c(0xFFFFFFFF));
        *///?} else {
        /*net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size, c(0xFFFFFFFF));
        *///?}
    }
}
