package dev.daze.worldmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.daze.worldmap.platform.Platform;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Серверная часть: общие метки (хранятся в мире), позиции игроков, пинги, телепорт с правами и перезарядкой.
 * Настройки — config/worldmap-server.json.
 */
public final class ServerMap {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Config {
        /** Кто может телепортироваться по карте: all, op, none. */
        public String teleport = "op";
        public int teleportCooldownSeconds = 10;
        /** Кто может создавать общие метки: all, op. Чужие общие метки удаляют только операторы. */
        public String publicMarks = "all";
        public boolean sharePlayerPositions = true;
        public boolean pings = true;
        public int maxPublicMarks = 500;
    }

    private static Config config = new Config();
    private static final Map<UUID, Mark> MARKS = new LinkedHashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    private static final Map<UUID, Long> PING_TIME = new HashMap<>();
    private static final Map<UUID, Integer> SENT_CONFIG = new HashMap<>();
    /** Игроки, которым надо отправить состояние, как только клиент сообщит о канале (тики ожидания). */
    private static final Map<UUID, Integer> WELCOME = new java.util.concurrent.ConcurrentHashMap<>();
    private static Path marksFile;
    private static int ticks;

    private ServerMap() {}

    // ---------- события (вызывает платформа) ----------

    public static void onPlayerJoin(ServerPlayer p) {
        WELCOME.put(p.getUUID(), 0);
    }

    public static void onPlayerLeave(ServerPlayer p) {
        SENT_CONFIG.remove(p.getUUID());
        WELCOME.remove(p.getUUID());
    }

    public static void onStopping(MinecraftServer server) {
        save();
    }

    /** Пакет от клиента; может прийти не в главном потоке. */
    public static void receive(MinecraftServer server, ServerPlayer player, byte[] data) {
        FriendlyByteBuf buf = Net.decode(data);
        switch (buf.readVarInt()) {
            case Net.TELEPORT -> {
                int x = buf.readVarInt(), z = buf.readVarInt();
                String dim = buf.readUtf(128);
                server.execute(() -> teleport(player, x, z, dim));
            }
            case Net.MARK_PUT -> {
                Mark m = Mark.read(buf);
                server.execute(() -> putMark(server, player, m));
            }
            case Net.MARK_DEL -> {
                UUID id = buf.readUUID();
                server.execute(() -> deleteMark(server, player, id));
            }
            case Net.PING -> {
                int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
                server.execute(() -> ping(server, player, x, y, z));
            }
            default -> {}
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("worldmap").requires(Compat::op)
                .then(Commands.literal("reload").executes(ctx -> {
                    loadConfig();
                    for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) sendConfig(p, true);
                    ctx.getSource().sendSuccess(() -> Component.literal("World Map: настройки перезагружены"), true);
                    return Command.SINGLE_SUCCESS;
                })));
    }

    private static void welcome(ServerPlayer p) {
        sendConfig(p, true);
        Net.toPlayer(p, Net.MARKS, buf -> {
            buf.writeVarInt(MARKS.size());
            for (Mark m : MARKS.values()) m.write(buf);
        });
    }

    // ---------- жизненный цикл ----------

    public static void onStarted(MinecraftServer server) {
        loadConfig();
        marksFile = server.getWorldPath(LevelResource.ROOT).resolve("worldmap_marks.json");
        MARKS.clear();
        try {
            if (Files.exists(marksFile)) {
                List<Mark> list = GSON.fromJson(Files.readString(marksFile), new TypeToken<List<Mark>>() {}.getType());
                if (list != null) for (Mark m : list) {
                    m.pub = true;
                    MARKS.put(m.id, m);
                }
            }
        } catch (Exception e) {
            LOG.warn("worldmap: не удалось прочитать общие метки", e);
        }
    }

    private static void loadConfig() {
        Path file = Platform.get().configDir().resolve("worldmap-server.json");
        try {
            if (Files.exists(file)) config = Objects.requireNonNullElseGet(GSON.fromJson(Files.readString(file), Config.class), Config::new);
            Files.writeString(file, GSON.toJson(config));
        } catch (Exception e) {
            LOG.warn("worldmap: ошибка в {}", file, e);
        }
    }

    private static void save() {
        if (marksFile == null) return;
        try {
            Files.writeString(marksFile, GSON.toJson(new ArrayList<>(MARKS.values())));
        } catch (Exception e) {
            LOG.warn("worldmap: не удалось сохранить общие метки", e);
        }
    }

    public static void onTick(MinecraftServer server) {
        ticks++;
        // Клиент сообщает о своих каналах чуть позже входа — ждём до 10 секунд.
        if (!WELCOME.isEmpty()) for (UUID id : new ArrayList<>(WELCOME.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            int waited = WELCOME.merge(id, 1, Integer::sum);
            if (p == null || waited > 200) WELCOME.remove(id);
            else if (Net.canSend(p)) {
                WELCOME.remove(id);
                welcome(p);
            }
        }
        if (ticks % 20 == 0 && config.sharePlayerPositions) {
            List<ServerPlayer> all = server.getPlayerList().getPlayers();
            for (ServerPlayer to : all) {
                if (!Net.canSend(to)) continue;
                List<ServerPlayer> same = new ArrayList<>();
                for (ServerPlayer p : all)
                    if (p != to && p.level() == to.level() && !p.isSpectator()) same.add(p);
                Net.toPlayer(to, Net.PLAYERS, buf -> {
                    buf.writeVarInt(same.size());
                    for (ServerPlayer p : same) {
                        buf.writeUUID(p.getUUID());
                        buf.writeUtf(Compat.name(p), 32);
                        buf.writeDouble(p.getX());
                        buf.writeDouble(p.getY());
                        buf.writeDouble(p.getZ());
                        buf.writeFloat(p.getYRot());
                    }
                });
            }
        }
        if (ticks % 100 == 0) for (ServerPlayer p : server.getPlayerList().getPlayers()) sendConfig(p, false);
        if (ticks % 6000 == 0) save();
    }

    // ---------- права ----------

    private static boolean canTeleport(ServerPlayer p) {
        return switch (config.teleport) {
            case "all" -> true;
            case "none" -> false;
            default -> Compat.op(p) || !Compat.server(p).isDedicatedServer();
        };
    }

    private static boolean canPublish(ServerPlayer p) {
        return "all".equals(config.publicMarks) || Compat.op(p) || !Compat.server(p).isDedicatedServer();
    }

    private static void sendConfig(ServerPlayer p, boolean force) {
        if (!Net.canSend(p)) return;
        boolean tp = canTeleport(p), pub = canPublish(p), op = Compat.op(p);
        int hash = Objects.hash(tp, pub, op, config.teleportCooldownSeconds, config.pings, config.sharePlayerPositions);
        if (!force && Objects.equals(SENT_CONFIG.get(p.getUUID()), hash)) return;
        SENT_CONFIG.put(p.getUUID(), hash);
        Net.toPlayer(p, Net.CONFIG, buf -> {
            buf.writeBoolean(tp);
            buf.writeVarInt(config.teleportCooldownSeconds);
            buf.writeBoolean(pub);
            buf.writeBoolean(op);
            buf.writeBoolean(config.pings);
            buf.writeBoolean(config.sharePlayerPositions);
        });
    }

    // ---------- действия ----------

    private static void teleport(ServerPlayer player, int x, int z, String dim) {
        if (!canTeleport(player)) {
            tpResult(player, false, "worldmap.tp.denied", 0);
            return;
        }
        long now = System.currentTimeMillis();
        long ready = COOLDOWN.getOrDefault(player.getUUID(), 0L);
        if (now < ready && !Compat.op(player)) {
            tpResult(player, false, "worldmap.tp.cooldown", (int) Math.ceil((ready - now) / 1000.0));
            return;
        }
        ResourceLocation dimId = ResourceLocation.tryParse(dim);
        ServerLevel level = dimId == null ? null : Compat.server(player).getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) level = Compat.level(player);
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
            tpResult(player, false, "worldmap.tp.border", 0);
            return;
        }
        int y = safeY(level, x, z);
        if (y == Integer.MIN_VALUE) {
            tpResult(player, false, "worldmap.tp.unsafe", 0);
            return;
        }
        Compat.teleport(player, level, x + 0.5, y, z + 0.5);
        COOLDOWN.put(player.getUUID(), now + config.teleportCooldownSeconds * 1000L);
        tpResult(player, true, "", 0);
    }

    private static int safeY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (level.dimensionType().hasCeiling()) {
            for (int yy = level.getLogicalHeight() - 3; yy > Compat.minY(level); yy--)
                if (level.getBlockState(p.set(x, yy, z)).isSolid()
                        && level.getBlockState(p.set(x, yy + 1, z)).isAir()
                        && level.getBlockState(p.set(x, yy + 2, z)).isAir()) return yy + 1;
            return Integer.MIN_VALUE;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= Compat.minY(level)) return level.dimension() == Level.END ? Integer.MIN_VALUE : level.getSeaLevel();
        return y;
    }

    private static void tpResult(ServerPlayer p, boolean ok, String key, int arg) {
        if (!Net.canSend(p)) return;
        Net.toPlayer(p, Net.TP_RESULT, buf -> {
            buf.writeBoolean(ok);
            buf.writeUtf(key);
            buf.writeVarInt(arg);
        });
    }

    private static void putMark(MinecraftServer server, ServerPlayer player, Mark m) {
        Mark old = MARKS.get(m.id);
        if (!canPublish(player)) return;
        if (old != null && !Objects.equals(old.owner, player.getUUID()) && !Compat.op(player)) {
            Compat.actionBar(player, Component.translatable("worldmap.pub.not_owner").withStyle(ChatFormatting.RED));
            return;
        }
        if (old == null && MARKS.size() >= config.maxPublicMarks) return;
        m.name = m.name.length() > 40 ? m.name.substring(0, 40) : m.name;
        m.owner = old != null ? old.owner : player.getUUID();
        m.ownerName = old != null ? old.ownerName : Compat.name(player);
        m.pub = true;
        MARKS.put(m.id, m);
        broadcast(server, Net.MARK_UPD, m::write);
        save();
    }

    private static void deleteMark(MinecraftServer server, ServerPlayer player, UUID id) {
        Mark old = MARKS.get(id);
        if (old == null) return;
        if (!Objects.equals(old.owner, player.getUUID()) && !Compat.op(player)) {
            Compat.actionBar(player, Component.translatable("worldmap.pub.not_owner").withStyle(ChatFormatting.RED));
            return;
        }
        MARKS.remove(id);
        broadcast(server, Net.MARK_GONE, buf -> buf.writeUUID(id));
        save();
    }

    private static void ping(MinecraftServer server, ServerPlayer from, int x, int y, int z) {
        if (!config.pings) return;
        long now = System.currentTimeMillis();
        if (now - PING_TIME.getOrDefault(from.getUUID(), 0L) < 1000) return;
        PING_TIME.put(from.getUUID(), now);
        String dim = Compat.dimId(from.level());
        String name = Compat.name(from);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != from.level() || !Net.canSend(p)) continue;
            Net.toPlayer(p, Net.PINGED, buf -> {
                buf.writeUtf(name, 32);
                buf.writeVarInt(x);
                buf.writeVarInt(y);
                buf.writeVarInt(z);
                buf.writeUtf(dim);
            });
        }
    }

    private static void broadcast(MinecraftServer server, int type, java.util.function.Consumer<FriendlyByteBuf> body) {
        byte[] data = Net.encode(type, body);
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (Net.canSend(p)) Platform.get().sendToPlayer(p, data);
    }
}
