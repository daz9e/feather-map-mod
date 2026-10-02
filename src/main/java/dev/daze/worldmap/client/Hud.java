package dev.daze.worldmap.client;

import dev.daze.worldmap.Mark;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/** Компас вверху экрана, переходы после закрытия карты и титр прибытия. */
public final class Hud {
    private static final int CW = 182, CH = 11;
    /** Сколько градусов видно на полосе компаса в каждую сторону. */
    private static final float FOV = 90;

    private static long exitStart = -1;
    private static boolean exitWhite;
    private static String arrivalName;
    /** null — ждём ответ сервера; true — показать титр; false — отменено. */
    private static Boolean arrivalOk;

    private Hud() {}

    static void startExit(boolean white, String name) {
        exitStart = System.nanoTime();
        exitWhite = white;
        arrivalName = name;
        arrivalOk = white && Session.current != null && Session.current.serverMod() ? null : Boolean.TRUE;
    }

    static void teleportResult(boolean ok, Component reason) {
        arrivalOk = ok;
        if (!ok) {
            arrivalName = null;
            Actions.toast(reason.copy().withStyle(ChatFormatting.RED));
            Actions.sound(SoundEvents.VILLAGER_NO, 1f);
        }
    }

    public static void render(GuiGraphics gg, float pt) {
        Gfx g = new Gfx(gg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Session.current == null) return;
        checkArrival(mc);
        boolean hidden = ClientCompat.hudHidden() || ClientCompat.debugShown() || ClientCompat.screen() instanceof MapScreen;
        if (!hidden && ClientConfig.get().compass) compass(g, mc, pt);
        transition(g, mc);
    }

    // ---------- переходы ----------

    private static void transition(Gfx g, Minecraft mc) {
        if (exitStart < 0) return;
        float t = (System.nanoTime() - exitStart) / 1e9f;
        int w = g.width(), h = g.height();
        g.push();
        g.translate(0, 0, 900);
        if (exitWhite) {
            float a = t < 0.25f ? 1f : 1f - Mth.clamp((t - 0.25f) / 0.8f, 0f, 1f);
            if (arrivalName != null && Boolean.TRUE.equals(arrivalOk) && t >= 0.3f) {
                Actions.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f);
                arrivalName = null;
            }
            g.fill(0, 0, w, h, UI.argb(a, 0xFFFDF5));
            if ((a <= 0 && arrivalName == null) || t > 4) exitStart = -1;
        } else if (ClientConfig.get().fastAnimations) {
            g.fill(0, 0, w, h, UI.argb(1 - Mth.clamp(t / 0.15f, 0, 1), 0xEBDDB6));
            if (t > 0.15f) exitStart = -1;
        } else {
            float p = Mth.clamp(t / 0.45f, 0f, 1f);
            Clouds.draw(g, w, h, 1f - UI.easeOut(p), t, 1f - p * p, UI.scale(w));
            if (p >= 1) exitStart = -1;
        }
        g.pop();
    }

    // ---------- компас ----------

    /** Азимут в системе поворота игрока (0 — юг, 90 — запад, 180 — север). */
    private static float bearing(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static void compass(Gfx g, Minecraft mc, float pt) {
        Session s = Session.current;
        float u = UI.scale(g.width());
        int uw = (int) (g.width() / u);
        int bosses = ClientCompat.bossBars();
        int y0 = bosses == 0 ? 3 : 12 + 19 * bosses - 4;
        int cx = uw / 2, x0 = cx - CW / 2;
        float yaw = mc.player.getViewYRot(pt);
        float t = (System.currentTimeMillis() % 100000) / 1000f;
        Font f = mc.font;

        g.push();
        g.scale(u, u);
        // Фон — мягкая полоса, тающая к краям.
        int seg = 26;
        for (int i = 0; i < seg; i++) {
            float k = (i + 0.5f) / seg * 2 - 1;
            float a = 1 - k * k;
            int sx0 = x0 + i * CW / seg, sx1 = x0 + (i + 1) * CW / seg;
            g.fill(sx0, y0, sx1, y0 + CH, UI.argb(0.32f * a, 0x1A1410));
            g.fill(sx0, y0 + CH, sx1, y0 + CH + 1, UI.argb(0.5f * a, 0xD9B266));
        }
        // Деления и стороны света.
        String[] names = {"worldmap.dir.s", "worldmap.dir.w", "worldmap.dir.n", "worldmap.dir.e"};
        for (int b = 0; b < 360; b += 15) {
            float rel = Mth.wrapDegrees(b - yaw);
            if (Math.abs(rel) > FOV) continue;
            float x = cx + rel / FOV * (CW / 2f);
            float fade = 1 - (rel / FOV) * (rel / FOV);
            if (b % 90 == 0) {
                String letter = Component.translatable(names[b / 90]).getString();
                int color = b == 180 ? 0xF0907A : 0xF3E6C8;
                g.push();
                g.translate(x, y0 + 2, 0);
                g.scale(0.8f, 0.8f);
                g.text(letter, -f.width(letter) / 2, 0, UI.argb(0.95f * fade, color), false);
                g.pop();
            } else if (b % 45 == 0) {
                g.fill((int) x, y0 + 4, (int) x + 1, y0 + CH - 3, UI.argb(0.55f * fade, 0xF3E6C8));
            } else {
                g.fill((int) x, y0 + 5, (int) x + 1, y0 + CH - 4, UI.argb(0.3f * fade, 0xF3E6C8));
            }
        }
        // Метки и пинги.
        String dim = Actions.currentDim();
        Mark focus = null;
        float focusRel = 6;
        java.util.List<Mark> marks = s.allMarks();
        if (s.nav != null && marks.stream().noneMatch(o -> o.id.equals(s.nav.id))) marks.add(s.nav);
        for (Mark m : marks) {
            if (!m.dim.equals(dim)) continue;
            boolean nav = s.nav != null && s.nav.id.equals(m.id);
            float rel = Mth.wrapDegrees(bearing(m.x + 0.5 - mc.player.getX(), m.z + 0.5 - mc.player.getZ()) - yaw);
            if (Math.abs(rel) > FOV) continue;
            float x = cx + rel / FOV * (CW / 2f), fade = 1 - (rel / FOV) * (rel / FOV);
            int color = m.death ? 0xF07A6A : m.color;
            UI.diamondFill(g, x, y0 + CH / 2f, 3.6f, UI.argb((nav ? 0.95f : 0.6f) * fade, 0x14201F));
            UI.diamondFill(g, x, y0 + CH / 2f, 2.6f, UI.argb((nav ? 1f : 0.7f) * fade, color));
            if (nav) UI.diamondRing(g, x, y0 + CH / 2f, 5 + Mth.sin(t * 4) * 0.8f, UI.argb(0.7f * fade, 0x7AE0FF));
            if (Math.abs(rel) < focusRel) {
                focusRel = Math.abs(rel);
                focus = m;
            }
        }
        long now = System.currentTimeMillis();
        for (Session.Ping p : s.pings) {
            if (!p.dim().equals(dim)) continue;
            float rel = Mth.wrapDegrees(bearing(p.x() + 0.5 - mc.player.getX(), p.z() + 0.5 - mc.player.getZ()) - yaw);
            if (Math.abs(rel) > FOV) continue;
            float x = cx + rel / FOV * (CW / 2f), fade = 1 - (rel / FOV) * (rel / FOV);
            float age = (now - p.time()) / 1000f, ph = (age * 0.9f) % 1f;
            UI.diamondFill(g, x, y0 + CH / 2f, 2.6f, UI.argb(fade, 0xFF5A3C));
            UI.diamondRing(g, x, y0 + CH / 2f, 3 + ph * 5, UI.argb(fade * (1 - ph), 0xFF5A3C));
        }
        // Центр — маленький золотой указатель.
        g.fill(cx, y0 - 1, cx + 1, y0 + 2, 0xCCD9B266);

        // Подпись: цель навигации (стрелкой к краю, если она за спиной) или метка под взглядом.
        Mark nav = s.nav != null && s.nav.dim.equals(dim) ? s.nav : null;
        Mark label = nav != null ? nav : focus;
        if (nav != null) {
            float rel = Mth.wrapDegrees(bearing(nav.x + 0.5 - mc.player.getX(), nav.z + 0.5 - mc.player.getZ()) - yaw);
            if (Math.abs(rel) > FOV) {
                String arrow = rel > 0 ? "›" : "‹";
                int ax = rel > 0 ? x0 + CW + 2 : x0 - 2 - f.width(arrow);
                g.text(arrow, ax, y0 + 2, UI.argb(0.6f + 0.3f * Mth.sin(t * 5), 0x7AE0FF), false);
            }
        }
        if (label != null) {
            double dist = Math.hypot(label.x + 0.5 - mc.player.getX(), label.z + 0.5 - mc.player.getZ());
            String text = label.name + "  " + UI.distance(dist);
            g.push();
            g.translate(cx, y0 + CH + 3, 0);
            g.scale(0.75f, 0.75f);
            g.text(text, -f.width(text) / 2, 0, UI.argb(label == nav ? 0.9f : 0.65f, label == nav ? 0xBFEFFF : 0xF3E6C8), true);
            g.pop();
        }
        g.pop();
    }

    /** Прибытие к цели навигации. */
    private static void checkArrival(Minecraft mc) {
        Session s = Session.current;
        Mark m = s.nav;
        if (m == null || !m.dim.equals(Actions.currentDim())) return;
        if (Math.hypot(m.x + 0.5 - mc.player.getX(), m.z + 0.5 - mc.player.getZ()) < 4) {
            s.nav = null;
            Actions.toast(Component.translatable("worldmap.nav.arrived", m.name).withStyle(ChatFormatting.GREEN));
            Actions.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f);
        }
    }
}
