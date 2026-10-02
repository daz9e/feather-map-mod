package dev.daze.worldmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.daze.worldmap.Mark;
import dev.daze.worldmap.Net;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class WorldMapClient implements ClientModInitializer {
    private static final String CAT = "key.categories.worldmap";
    public static final KeyMapping OPEN = new KeyMapping("key.worldmap.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CAT);
    public static final KeyMapping COMPASS = new KeyMapping("key.worldmap.compass", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CAT);
    public static final KeyMapping NEW_MARK = new KeyMapping("key.worldmap.new_mark", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CAT);

    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    private static ClientLevel level;
    private static int ticks;
    private static boolean wasDead;

    @Override
    public void onInitializeClient() {
        for (KeyMapping k : List.of(OPEN, COMPASS, NEW_MARK)) KeyBindingHelper.registerKeyBinding(k);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> {
            if (Session.current != null) Session.current.close();
            Session.current = Session.open();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
            if (Session.current != null) Session.current.close();
            Session.current = null;
            level = null;
            QUEUE.clear();
        }));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> QUEUE.add(chunk.getPos().toLong()));
        ClientTickEvents.END_CLIENT_TICK.register(WorldMapClient::tick);
        HudRenderCallback.EVENT.register(Hud::render);
        // Метки из чата: вместо сырого «[Карта] …» — аккуратное уведомление с кнопкой.
        ClientReceiveMessageEvents.ALLOW_CHAT.register((msg, signed, sender, params, time) -> {
            Mark m = Actions.parseShared(msg.getString());
            if (m == null) return true;
            if (sender != null) m.ownerName = sender.getName();
            announce(m);
            return false;
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> {
            if (overlay) return true;
            Mark m = Actions.parseShared(msg.getString());
            if (m == null) return true;
            announce(m);
            return false;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("wmshow")
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("dim", StringArgumentType.string())
                                                        .then(ClientCommandManager.argument("icon", StringArgumentType.string())
                                                                .then(ClientCommandManager.argument("from", StringArgumentType.word())
                                                                        .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                                                .executes(ctx -> {
                                                                                    Mark m = new Mark();
                                                                                    m.x = IntegerArgumentType.getInteger(ctx, "x");
                                                                                    m.y = IntegerArgumentType.getInteger(ctx, "y");
                                                                                    m.z = IntegerArgumentType.getInteger(ctx, "z");
                                                                                    m.dim = StringArgumentType.getString(ctx, "dim");
                                                                                    m.icon = StringArgumentType.getString(ctx, "icon");
                                                                                    m.ownerName = StringArgumentType.getString(ctx, "from");
                                                                                    m.name = StringArgumentType.getString(ctx, "name");
                                                                                    m.color = Mark.COLORS[1];
                                                                                    Minecraft mc = Minecraft.getInstance();
                                                                                    mc.tell(() -> mc.setScreen(MapScreen.proposal(m)));
                                                                                    return 1;
                                                                                }))))))))));
        registerNetwork();
        Demo.init();
    }

    // ---------- сеть ----------

    private static void registerNetwork() {
        ClientPlayNetworking.registerGlobalReceiver(Net.CONFIG, (mc, h, buf, rs) -> {
            Session.Caps caps = new Session.Caps(buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
            mc.execute(() -> {
                if (Session.current != null) Session.current.caps = caps;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.MARKS, (mc, h, buf, rs) -> {
            int n = buf.readVarInt();
            List<Mark> list = new ArrayList<>();
            for (int i = 0; i < n; i++) list.add(Mark.read(buf));
            mc.execute(() -> {
                Session s = Session.current;
                if (s == null) return;
                s.shared.clear();
                for (Mark m : list) s.shared.put(m.id, m);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.MARK_UPD, (mc, h, buf, rs) -> {
            Mark m = Mark.read(buf);
            mc.execute(() -> {
                Session s = Session.current;
                if (s == null) return;
                s.local.removeIf(o -> o.id.equals(m.id));
                s.shared.put(m.id, m);
                if (s.nav != null && s.nav.id.equals(m.id)) s.nav = m;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.MARK_GONE, (mc, h, buf, rs) -> {
            UUID id = buf.readUUID();
            mc.execute(() -> {
                if (Session.current != null) Session.current.shared.remove(id);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.PLAYERS, (mc, h, buf, rs) -> {
            int n = buf.readVarInt();
            List<Session.Remote> list = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++)
                list.add(new Session.Remote(buf.readUUID(), buf.readUtf(32), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), now));
            mc.execute(() -> {
                Session s = Session.current;
                if (s == null) return;
                s.remote.clear();
                for (Session.Remote r : list) s.remote.put(r.id(), r);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.PINGED, (mc, h, buf, rs) -> {
            String from = buf.readUtf(32);
            int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
            String dim = buf.readUtf();
            mc.execute(() -> {
                Actions.addPing(from, x, y, z, dim);
                Actions.toast(Component.translatable("worldmap.pinged", from, x, z).withStyle(ChatFormatting.GOLD));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.TP_RESULT, (mc, h, buf, rs) -> {
            boolean ok = buf.readBoolean();
            String key = buf.readUtf();
            int arg = buf.readVarInt();
            mc.execute(() -> Hud.teleportResult(ok, Component.translatable(key, arg)));
        });
    }

    // ---------- тик ----------

    private static void tick(Minecraft mc) {
        Session s = Session.current;
        if (s == null || mc.level == null || mc.player == null) return;
        if (mc.level != level) {
            level = mc.level;
            QUEUE.clear();
            ChunkPos p = mc.player.chunkPosition();
            int r = mc.options.getEffectiveRenderDistance();
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) QUEUE.add(ChunkPos.asLong(p.x + dx, p.z + dz));
            s.remote.clear();
        }
        ticks++;
        Session.Dim d = s.dim(Actions.currentDim());
        if (ticks % 40 == 0) {
            ChunkPos p = mc.player.chunkPosition();
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) QUEUE.add(ChunkPos.asLong(p.x + dx, p.z + dz));
        }
        int budget = mc.screen instanceof MapScreen ? 24 : 8;
        while (budget-- > 0 && !QUEUE.isEmpty()) {
            long k = QUEUE.removeFirstLong();
            LevelChunk lc = mc.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), false);
            if (lc != null) d.data.scan(mc.level, lc);
        }
        if (ticks % 1200 == 0) s.saveMaps();
        long now = System.currentTimeMillis();
        s.pings.removeIf(p -> now - p.time() > 15000);
        s.remote.values().removeIf(r -> now - r.seen() > 5000);

        // Точка смерти.
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && ClientConfig.get().deathMarks) {
            Mark m = Actions.newMark(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), Actions.currentDim());
            m.death = true;
            m.icon = "minecraft:skeleton_skull";
            m.color = 0xF07A6A;
            m.name = Component.translatable("worldmap.death", new SimpleDateFormat("HH:mm").format(new Date())).getString();
            List<Mark> deaths = new ArrayList<>(s.local.stream().filter(o -> o.death).toList());
            while (deaths.size() >= 3) s.local.remove(deaths.remove(0));
            s.local.add(m);
            s.saveMarks();
        }
        wasDead = dead;

        // Клавиши.
        while (OPEN.consumeClick()) if (mc.screen == null) mc.setScreen(new MapScreen());
        while (COMPASS.consumeClick()) {
            ClientConfig.get().compass = !ClientConfig.get().compass;
            ClientConfig.get().save();
        }
        while (NEW_MARK.consumeClick()) if (mc.screen == null) {
            Mark m = Actions.newMark(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), Actions.currentDim());
            mc.setScreen(new MarkEditScreen(null, m, true));
        }
    }

    /** Уведомление в чате: «Ник делится точкой «Название»  [Показать на карте]». */
    private static void announce(Mark m) {
        Minecraft mc = Minecraft.getInstance();
        String from = m.ownerName == null || m.ownerName.isEmpty() ? "?" : m.ownerName;
        String cmd = "/wmshow " + m.x + " " + m.y + " " + m.z + " \"" + m.dim + "\" \"" + m.icon + "\" " + from + " " + m.name;
        Component btn = Component.translatable("worldmap.chat.show").withStyle(Style.EMPTY.withColor(0x7AE0FF)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(m.x + ", " + m.y + ", " + m.z + " · " + UI.dimName(m.dim).getString()))));
        Component line = Component.literal("◆ ").withStyle(Style.EMPTY.withColor(0xF4D27A))
                .append(Component.translatable("worldmap.chat.shared", Component.literal(from).withStyle(ChatFormatting.WHITE),
                        Component.literal(m.name).withStyle(Style.EMPTY.withColor(0xF4D27A))).withStyle(ChatFormatting.GRAY))
                .append("  ").append(btn);
        mc.execute(() -> mc.gui.getChat().addMessage(line));
    }
}
