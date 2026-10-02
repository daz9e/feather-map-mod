package dev.daze.worldmap.client;

import com.mojang.logging.LogUtils;
import dev.daze.worldmap.Mark;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Демо-сценарии для проверки в игре: -Dworldmap.demo=sp | alice | bob.
 * Прожимают функции карты и снимают кадры в run/screenshots.
 */
public final class Demo {
    private static final String MODE = System.getProperty("worldmap.demo", "");
    private static String pending;
    private static int ticks;

    private Demo() {}

    /** Снимок прошлого кадра: между кадрами основной буфер ещё хранит готовое изображение. */
    private static void grabPending(Minecraft mc) {
        if (pending == null) return;
        LogUtils.getLogger().info("worldmap demo shot {} tick {}", pending, ticks);
        //? if <1.21.6 {
        Screenshot.grab(mc.gameDirectory, pending + ".png", ClientCompat.mainTarget(), msg -> {});
        //?} else
        /*Screenshot.grab(mc.gameDirectory, pending + ".png", ClientCompat.mainTarget(), 1, msg -> {});*/
        pending = null;
    }

    private static void shot(String name) {
        pending = name;
    }

    private static Mark mark(String name, int dx, int dz, String icon, int color) {
        Minecraft mc = Minecraft.getInstance();
        int x = mc.player.getBlockX() + dx, z = mc.player.getBlockZ() + dz;
        int y = Session.current.dim(Actions.currentDim()).data.heightAt(x, z);
        Mark m = Actions.newMark(x, y == Integer.MIN_VALUE ? mc.player.getBlockY() : y, z, Actions.currentDim());
        m.name = name;
        m.icon = icon;
        m.color = color;
        return m;
    }

    private static void look(Minecraft mc, Mark f) {
        double dx = f.x + 0.5 - mc.player.getX(), dz = f.z + 0.5 - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mc.player.setXRot(0);
    }

    private static void day(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000"));
        else mc.player.connection.sendCommand("time set 6000");
    }

    static void tick(Minecraft mc) {
        if (MODE.isEmpty()) return;
        grabPending(mc);
        if (mc.player == null || Session.current == null) return;
        ticks++;
        switch (MODE) {
            case "sp" -> sp(mc);
            case "alice" -> alice(mc);
            case "bob" -> bob(mc);
            default -> {}
        }
    }

    private static ChatScreen chat() {
        //? if <1.21.9 {
        return new ChatScreen("");
        //?} else
        /*return new ChatScreen("", false);*/
    }

    private static MapScreen map(Minecraft mc) {
        return ClientCompat.screen() instanceof MapScreen m ? m : null;
    }

    private static void sp(Minecraft mc) {
        int S = 220;
        Session s = Session.current;
        ClientConfig c = ClientConfig.get();
        if (ticks == 30) {
            day(mc);
            c.compass = true;
            c.sidebar = true;
            c.fastAnimations = false;
        }
        if (ticks == S - 10) {
            s.local.clear();
            s.local.add(mark("Лагерь", 18, -12, "minecraft:campfire", Mark.COLORS[1]));
            s.local.add(mark("Рыбак", -55, 35, "minecraft:fishing_rod", Mark.COLORS[0]));
            s.local.add(mark("Шахта", 60, 50, "minecraft:iron_pickaxe", Mark.COLORS[4]));
            s.local.add(mark("Ферма", -40, -50, "minecraft:wheat", Mark.COLORS[3]));
            s.nav = s.local.get(2);
            look(mc, s.local.get(0));
        }
        if (ticks == S) shot("sp_hud");
        if (ticks == S + 10) ClientCompat.setScreen(new MapScreen());
        if (ticks > S + 10 && ticks <= S + 32 && ticks % 4 == 0) shot(String.format("sp_open_%02d", ticks - S - 10));
        if (ticks == S + 60) shot("sp_map");
        if (ticks == S + 65 && map(mc) != null) map(mc).demoSelect(s.local.get(1));
        if (ticks == S + 80) shot("sp_card");
        if (ticks == S + 85 && map(mc) != null) {
            map(mc).demoSelect(null);
            map(mc).demoMenu(mc.getWindow().getGuiScaledWidth() / 3, mc.getWindow().getGuiScaledHeight() / 3);
        }
        if (ticks == S + 95) shot("sp_menu");
        if (ticks == S + 98 && map(mc) != null) map(mc).demoCloseMenu();
        if (ticks == S + 100 && map(mc) != null) ClientCompat.setScreen(new MarkEditScreen(map(mc), mark("", 5, 5, "minecraft:compass", Mark.COLORS[0]), true));
        if (ticks == S + 115) shot("sp_editor");
        if (ticks == S + 120 && ClientCompat.screen() instanceof MarkEditScreen e) e.onClose();
        if (ticks == S + 125 && map(mc) != null) ClientCompat.setScreen(new SettingsScreen(map(mc)));
        if (ticks == S + 140) shot("sp_settings");
        if (ticks == S + 145 && ClientCompat.screen() instanceof SettingsScreen st) st.onClose();
        if (ticks == S + 215 && map(mc) != null) map(mc).demoTravel(s.local.get(1));
        if (ticks > S + 215 && ticks <= S + 265 && ticks % 6 == 0) shot(String.format("sp_tp_%02d", ticks - S - 215));
        // Метка в мире: смотрим на лагерь.
        if (ticks == S + 290) {
            day(mc);
            s.nav = s.local.get(0);
            look(mc, s.local.get(0));
        }
        if (ticks == S + 300) shot("sp_world");
        // Обмен через чат: отправить свою точку и открыть предложение по кнопке.
        if (ticks == S + 305) Actions.share(s.local.get(3));
        if (ticks == S + 315) ClientCompat.setScreen(chat());
        if (ticks == S + 325) shot("sp_chat");
        if (ticks == S + 330) {
            ClientCompat.setScreen(null);
            Mark f = s.local.get(3);
            mc.player.connection.sendCommand("wmshow " + f.x + " " + f.y + " " + f.z + " \"" + f.dim + "\" \"" + f.icon + "\" Steve " + f.name);
        }
        if (ticks == S + 370) shot("sp_proposal");
        if (ticks == S + 380 && map(mc) != null) map(mc).onClose();
        if (ticks > S + 380 && ticks <= S + 400 && ticks % 4 == 0) shot(String.format("sp_close_%02d", ticks - S - 380));
        if (ticks == S + 420) mc.stop();
    }

    private static void alice(Minecraft mc) {
        int S = 300;
        Session s = Session.current;
        if (ticks == 40) {
            day(mc);
            ClientConfig.get().sidebar = true;
        }
        if (ticks == S - 100 && s.caps != null) {
            Mark m = mark("Общий склад", 20, 10, "minecraft:chest", Mark.COLORS[1]);
            m.pub = true;
            Actions.save(m);
        }
        if (ticks == S - 60) Actions.share(mark("Алмазы", -30, 25, "minecraft:diamond", Mark.COLORS[4]));
        if (ticks == S) Actions.ping(mc.player.getBlockX() - 15, mc.player.getBlockY(), mc.player.getBlockZ() - 20);
        if (ticks == S + 10) ClientCompat.setScreen(new MapScreen());
        if (ticks == S + 60) shot("alice_map");
        if (ticks == S + 400) mc.stop();
    }

    private static void bob(Minecraft mc) {
        int S = 300;
        if (ticks == 40) ClientConfig.get().sidebar = true;
        if (ticks == S - 30) ClientCompat.setScreen(chat());
        if (ticks == S - 20) shot("bob_chat");
        if (ticks == S - 15) ClientCompat.setScreen(null);
        if (ticks == S + 20) ClientCompat.setScreen(new MapScreen());
        if (ticks == S + 70) shot("bob_map");
        if (ticks == S + 75 && map(mc) != null) map(mc).onClose();
        if (ticks == S + 100) shot("bob_hud");
        if (ticks == S + 130) mc.stop();
    }
}
