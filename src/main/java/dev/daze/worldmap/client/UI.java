package dev.daze.worldmap.client;

import dev.daze.worldmap.Mark;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Общие элементы оформления: пергамент, тёмные панели с золотой каймой, ромбы меток, текст с обводкой. */
public final class UI {
    public static final int INK = 0xFF2A1F14, CREAM = 0xFFFFF3D6, GOLD = 0xFFD9B266, BAR = 0xFF2B2219,
            PANEL = 0xF22B2219, TEXT = 0xFFE9DCC0, MUTED = 0xFF9C8B6E, TITLE = 0xFFF1D9A0, HOVER = 0x40FFE08A;

    private UI() {}

    public static float scale(int guiWidth) {
        float fixed = ClientConfig.get().uiScale;
        return fixed > 0 ? fixed : Mth.clamp(guiWidth / 700f, 1f, 2f);
    }

    public static int argb(float a, int rgb) {
        return Math.max(4, Math.min(255, (int) (a * 255))) << 24 | (rgb & 0xFFFFFF);
    }

    public static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static float smoothstep(float a, float b, float x) {
        return smooth((x - a) / (b - a));
    }

    public static float easeOut(float t) {
        float u = 1 - Mth.clamp(t, 0, 1);
        return 1 - u * u * u;
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** Тёмная панель с золотой каймой. */
    public static void panel(Gfx g, int x0, int y0, int x1, int y1) {
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, GOLD);
        g.fill(x0, y0, x1, y1, PANEL);
    }

    /** Текст с тёмной обводкой, по центру cx. */
    public static void outlined(Gfx g, String s, float cx, float y, float scale, float a, int color, int z) {
        if (a < 0.03f) return;
        Font font = font();
        g.push();
        g.translate(cx, y, z);
        g.scale(scale, scale);
        int w = font.width(s), x = -w / 2;
        int oc = argb(a, 0x2A1F14), c = argb(a, color);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0) g.text(s, x + dx, dy, oc, false);
        g.translate(0, 0, 1);
        g.text(s, x, 0, c, false);
        g.pop();
    }

    /** Ромб с «полудиагональю» r. */
    public static void diamondFill(Gfx g, float x, float y, float r, int color) {
        g.push();
        g.translate(x, y, 0);
        g.rotate(45);
        float h = r / 1.41421f;
        g.scale(h, h);
        g.fill(-1, -1, 1, 1, color);
        g.pop();
    }

    public static void diamondRing(Gfx g, float x, float y, float r, int color) {
        g.push();
        g.translate(x, y, 0);
        g.rotate(45);
        int h = Math.round(r / 1.41421f);
        g.fill(-h, -h, h, -h + 1, color);
        g.fill(-h, h - 1, h, h, color);
        g.fill(-h, -h + 1, -h + 1, h - 1, color);
        g.fill(h - 1, -h + 1, h, h - 1, color);
        g.pop();
    }

    /** Ромб-метка: тёмный контур, цветная рамка, тёмная сердцевина и иконка. */
    public static void diamond(Gfx g, float x, float y, float s, float a, ItemStack icon, int color, float hover, float t, int z) {
        g.push();
        g.translate(x, y, z);
        if (hover > 0.01f) {
            float p = (t * 0.9f) % 1f;
            diamondRing(g, 0, 0, (11 + p * 9) * s, argb(hover * (1 - p) * a, 0xFFE08A));
            diamondFill(g, 0, 0, 13 * s, argb(hover * 0.35f * a, 0xFFD877));
        }
        diamondFill(g, 0, 0, 11 * s, argb(a, 0x14201F));
        diamondFill(g, 0, 0, 10 * s, argb(a, hover > 0.5f ? 0xF4E7B0 : color));
        diamondFill(g, 0, 0, 8 * s, argb(a, 0x1A2D31));
        diamondFill(g, 0, 0, 7 * s, argb(a, 0x24434A));
        if (a > 0.6f && icon != null && s > 0.45f) {
            g.push();
            g.translate(0, 0, 10);
            g.scale(0.72f * s, 0.72f * s);
            g.item(icon, -8, -8);
            g.pop();
        }
        g.pop();
    }

    public static ItemStack icon(Mark m) {
        if (m.death) return new ItemStack(Items.SKELETON_SKULL);
        return iconStack(m.icon);
    }

    public static ItemStack iconStack(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        Item item = rl == null ? Items.COMPASS : ClientCompat.item(rl);
        return new ItemStack(item == Items.AIR ? Items.COMPASS : item);
    }

    /** Значок клавиши в подсказках. */
    public static int key(Gfx g, String key, int x, int y) {
        Font f = font();
        int kw = f.width(key) + 6;
        g.fill(x, y - 2, x + kw, y + 9, 0xFF6B5A44);
        g.fill(x + 1, y - 1, x + kw - 1, y + 8, 0xFFE9DCC0);
        g.text(key, x + 3, y, 0xFF2B2219, false);
        return kw;
    }

    public static String distance(double d) {
        return d >= 10000 ? String.format("%.1f км", d / 1000) : (int) d + " м";
    }

    public static Component dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> Component.translatable("worldmap.dim.overworld");
            case "minecraft:the_nether" -> Component.translatable("worldmap.dim.nether");
            case "minecraft:the_end" -> Component.translatable("worldmap.dim.end");
            default -> Component.literal(dim.substring(dim.indexOf(':') + 1));
        };
    }

    public static Component biomeName(String biome) {
        if (biome == null) return Component.empty();
        ResourceLocation rl = ResourceLocation.tryParse(biome);
        return rl == null ? Component.literal(biome) : Component.translatable("biome." + rl.getNamespace() + "." + rl.getPath());
    }
}
