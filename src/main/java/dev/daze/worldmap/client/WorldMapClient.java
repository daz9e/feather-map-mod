package dev.daze.worldmap.client;

import dev.daze.worldmap.Compat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.daze.worldmap.Mark;
import dev.daze.worldmap.Net;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.mojang.blaze3d.platform.InputConstants;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Клиентская логика; события ей передаёт точка входа лоадера. */
public final class WorldMapClient {
    public static final KeyMapping OPEN = ClientCompat.key("key.worldmap.open", InputConstants.KEY_M);
    public static final KeyMapping COMPASS = ClientCompat.key("key.worldmap.compass", InputConstants.KEY_N);
    public static final KeyMapping NEW_MARK = ClientCompat.key("key.worldmap.new_mark", InputConstants.KEY_B);
    public static final List<KeyMapping> KEYS = List.of(OPEN, COMPASS, NEW_MARK);

    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    private static ClientLevel level;
    private static int ticks;
    private static boolean wasDead;

    private WorldMapClient() {}

    // ---------- события (вызывает платформа) ----------

    public static void onJoin() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (Session.current != null) Session.current.close();
            Session.current = Session.open();
        });
    }

    public static void onDisconnect() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (Session.current != null) Session.current.close();
            Session.current = null;
            level = null;
            QUEUE.clear();
        });
    }

    public static void onChunkLoad(ChunkPos pos) {
        QUEUE.add(Compat.chunkKey(pos));
    }

    /** Смена ресурспаков: палитру блоков и тайлы строим заново. */
    public static void onResourcesReloaded() {
        BlockPalette.clear();
        if (Session.current != null) Session.current.rebuildTiles();
    }

    /**
     * Сообщение в чате. Метки из чата показываем аккуратным уведомлением с кнопкой вместо сырого «[Карта] …».
     * @return false — скрыть исходное сообщение
     */
    public static boolean onChat(Component msg, String sender, boolean overlay) {
        if (overlay) return true;
        Mark m = Actions.parseShared(msg.getString());
        if (m == null) return true;
        if (sender != null) m.ownerName = sender;
        announce(m);
        return false;
    }

    /** Ник игрока из списка вкладки (для отправителя сообщения в чате), null — неизвестен. */
    public static String playerName(UUID id) {
        var conn = Minecraft.getInstance().getConnection();
        if (id == null || conn == null) return null;
        PlayerInfo info = conn.getPlayerInfo(id);
        return info == null ? null : Compat.name(info.getProfile());
    }

    /** /wmshow x y z dim icon from name — кнопка «Показать на карте» в уведомлении. */
    public static <S> void registerCommands(CommandDispatcher<S> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<S>literal("wmshow")
                .then(RequiredArgumentBuilder.<S, Integer>argument("x", IntegerArgumentType.integer())
                        .then(RequiredArgumentBuilder.<S, Integer>argument("y", IntegerArgumentType.integer())
                                .then(RequiredArgumentBuilder.<S, Integer>argument("z", IntegerArgumentType.integer())
                                        .then(RequiredArgumentBuilder.<S, String>argument("dim", StringArgumentType.string())
                                                .then(RequiredArgumentBuilder.<S, String>argument("icon", StringArgumentType.string())
                                                        .then(RequiredArgumentBuilder.<S, String>argument("from", StringArgumentType.word())
                                                                .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.greedyString())
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
                                                                            ClientCompat.later(() -> ClientCompat.setScreen(MapScreen.proposal(m)));
                                                                            return 1;
                                                                        })))))))));
    }

    // ---------- сеть ----------

    /** Пакет канала worldmap:net от сервера; может прийти не в главном потоке. */
    public static void receive(byte[] data) {
        Minecraft mc = Minecraft.getInstance();
        FriendlyByteBuf buf = Net.decode(data);
        switch (buf.readVarInt()) {
            case Net.CONFIG -> {
                Session.Caps caps = new Session.Caps(buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
                mc.execute(() -> {
                    if (Session.current != null) Session.current.caps = caps;
                });
            }
            case Net.MARKS -> {
                int n = buf.readVarInt();
                List<Mark> list = new ArrayList<>();
                for (int i = 0; i < n; i++) list.add(Mark.read(buf));
                mc.execute(() -> {
                    Session s = Session.current;
                    if (s == null) return;
                    s.shared.clear();
                    for (Mark m : list) s.shared.put(m.id, m);
                });
            }
            case Net.MARK_UPD -> {
                Mark m = Mark.read(buf);
                mc.execute(() -> {
                    Session s = Session.current;
                    if (s == null) return;
                    s.local.removeIf(o -> o.id.equals(m.id));
                    s.shared.put(m.id, m);
                    if (s.nav != null && s.nav.id.equals(m.id)) s.nav = m;
                });
            }
            case Net.MARK_GONE -> {
                UUID id = buf.readUUID();
                mc.execute(() -> {
                    if (Session.current != null) Session.current.shared.remove(id);
                });
            }
            case Net.PLAYERS -> {
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
            }
            case Net.PINGED -> {
                String from = buf.readUtf(32);
                int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
                String dim = buf.readUtf();
                mc.execute(() -> {
                    Actions.addPing(from, x, y, z, dim);
                    Actions.toast(Component.translatable("worldmap.pinged", from, x, z).withStyle(ChatFormatting.GOLD));
                });
            }
            case Net.TP_RESULT -> {
                boolean ok = buf.readBoolean();
                String key = buf.readUtf();
                int arg = buf.readVarInt();
                mc.execute(() -> Hud.teleportResult(ok, Component.translatable(key, arg)));
            }
            default -> {}
        }
    }

    // ---------- тик ----------

    public static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        Demo.tick(mc);
        Session s = Session.current;
        if (s == null || mc.level == null || mc.player == null) return;
        if (mc.level != level) {
            level = mc.level;
            QUEUE.clear();
            ChunkPos p = mc.player.chunkPosition();
            int r = mc.options.getEffectiveRenderDistance();
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) QUEUE.add(Compat.chunkKey((p.getMinBlockX() >> 4) + dx, (p.getMinBlockZ() >> 4) + dz));
            s.remote.clear();
        }
        ticks++;
        Session.Dim d = s.dim(Actions.currentDim());
        if (ticks % 40 == 0) {
            ChunkPos p = mc.player.chunkPosition();
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) QUEUE.add(Compat.chunkKey((p.getMinBlockX() >> 4) + dx, (p.getMinBlockZ() >> 4) + dz));
        }
        int budget = ClientCompat.screen() instanceof MapScreen ? 24 : 8;
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
        while (OPEN.consumeClick()) if (ClientCompat.screen() == null) ClientCompat.setScreen(new MapScreen());
        while (COMPASS.consumeClick()) {
            ClientConfig.get().compass = !ClientConfig.get().compass;
            ClientConfig.get().save();
        }
        while (NEW_MARK.consumeClick()) if (ClientCompat.screen() == null) {
            Mark m = Actions.newMark(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), Actions.currentDim());
            ClientCompat.setScreen(new MarkEditScreen(null, m, true));
        }
    }

    /** Уведомление в чате: «Ник делится точкой «Название»  [Показать на карте]». */
    private static void announce(Mark m) {
        Minecraft mc = Minecraft.getInstance();
        String from = m.ownerName == null || m.ownerName.isEmpty() ? "?" : m.ownerName;
        String cmd = "/wmshow " + m.x + " " + m.y + " " + m.z + " \"" + m.dim + "\" \"" + m.icon + "\" " + from + " " + m.name;
        Component btn = Component.translatable("worldmap.chat.show").withStyle(ClientCompat.clickToRun(Style.EMPTY.withColor(0x7AE0FF), cmd,
                Component.literal(m.x + ", " + m.y + ", " + m.z + " · " + UI.dimName(m.dim).getString())));
        Component line = Component.literal("◆ ").withStyle(Style.EMPTY.withColor(0xF4D27A))
                .append(Component.translatable("worldmap.chat.shared", Component.literal(from).withStyle(ChatFormatting.WHITE),
                        Component.literal(m.name).withStyle(Style.EMPTY.withColor(0xF4D27A))).withStyle(ChatFormatting.GRAY))
                .append("  ").append(btn);
        mc.execute(() -> ClientCompat.addChat(line));
    }
}
