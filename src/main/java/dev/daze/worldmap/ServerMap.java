package dev.daze.worldmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
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
    /** Игроки, которым надо отправить состояние на ближайшем тике. */
    private static final java.util.Set<UUID> WELCOME = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Path marksFile;
    private static int ticks;

    private ServerMap() {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerMap::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        // Клиент сообщает о своих каналах чуть позже входа — тогда и отправляем состояние.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> WELCOME.add(handler.player.getUUID()));
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(Net.CONFIG)) WELCOME.add(handler.player.getUUID());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT_CONFIG.remove(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(ServerMap::tick);

        ServerPlayNetworking.registerGlobalReceiver(Net.TELEPORT, (server, player, handler, buf, rs) -> {
            int x = buf.readVarInt(), z = buf.readVarInt();
            String dim = buf.readUtf(128);
            server.execute(() -> teleport(player, x, z, dim));
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.MARK_PUT, (server, player, handler, buf, rs) -> {
            Mark m = Mark.read(buf);
            server.execute(() -> putMark(server, player, m));
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.MARK_DEL, (server, player, handler, buf, rs) -> {
            UUID id = buf.readUUID();
            server.execute(() -> deleteMark(server, player, id));
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.PING, (server, player, handler, buf, rs) -> {
            int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
            server.execute(() -> ping(server, player, x, y, z));
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
                Commands.literal("worldmap").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("reload").executes(ctx -> {
                            loadConfig();
                            for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) sendConfig(p, true);
                            ctx.getSource().sendSuccess(() -> Component.literal("World Map: настройки перезагружены"), true);
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    private static void welcome(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, Net.MARKS)) return;
        sendConfig(p, true);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(MARKS.size());
        for (Mark m : MARKS.values()) m.write(buf);
        ServerPlayNetworking.send(p, Net.MARKS, buf);
    }

    // ---------- жизненный цикл ----------

    private static void start(MinecraftServer server) {
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
        Path file = FabricLoader.getInstance().getConfigDir().resolve("worldmap-server.json");
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

    private static void tick(MinecraftServer server) {
        ticks++;
        if (!WELCOME.isEmpty()) for (UUID id : new ArrayList<>(WELCOME)) {
            WELCOME.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) welcome(p);
        }
        if (ticks % 20 == 0 && config.sharePlayerPositions) {
            List<ServerPlayer> all = server.getPlayerList().getPlayers();
            for (ServerPlayer to : all) {
                if (!ServerPlayNetworking.canSend(to, Net.PLAYERS)) continue;
                FriendlyByteBuf buf = PacketByteBufs.create();
                List<ServerPlayer> same = new ArrayList<>();
                for (ServerPlayer p : all)
                    if (p != to && p.level() == to.level() && !p.isSpectator()) same.add(p);
                buf.writeVarInt(same.size());
                for (ServerPlayer p : same) {
                    buf.writeUUID(p.getUUID());
                    buf.writeUtf(p.getGameProfile().getName(), 32);
                    buf.writeDouble(p.getX());
                    buf.writeDouble(p.getY());
                    buf.writeDouble(p.getZ());
                    buf.writeFloat(p.getYRot());
                }
                ServerPlayNetworking.send(to, Net.PLAYERS, buf);
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
            default -> p.hasPermissions(2) || !p.server.isDedicatedServer();
        };
    }

    private static boolean canPublish(ServerPlayer p) {
        return "all".equals(config.publicMarks) || p.hasPermissions(2) || !p.server.isDedicatedServer();
    }

    private static void sendConfig(ServerPlayer p, boolean force) {
        if (!ServerPlayNetworking.canSend(p, Net.CONFIG)) return;
        boolean tp = canTeleport(p), pub = canPublish(p), op = p.hasPermissions(2);
        int hash = Objects.hash(tp, pub, op, config.teleportCooldownSeconds, config.pings, config.sharePlayerPositions);
        if (!force && Objects.equals(SENT_CONFIG.get(p.getUUID()), hash)) return;
        SENT_CONFIG.put(p.getUUID(), hash);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(tp);
        buf.writeVarInt(config.teleportCooldownSeconds);
        buf.writeBoolean(pub);
        buf.writeBoolean(op);
        buf.writeBoolean(config.pings);
        buf.writeBoolean(config.sharePlayerPositions);
        ServerPlayNetworking.send(p, Net.CONFIG, buf);
    }

    // ---------- действия ----------

    private static void teleport(ServerPlayer player, int x, int z, String dim) {
        if (!canTeleport(player)) {
            tpResult(player, false, "worldmap.tp.denied", 0);
            return;
        }
        long now = System.currentTimeMillis();
        long ready = COOLDOWN.getOrDefault(player.getUUID(), 0L);
        if (now < ready && !player.hasPermissions(2)) {
            tpResult(player, false, "worldmap.tp.cooldown", (int) Math.ceil((ready - now) / 1000.0));
            return;
        }
        ResourceLocation dimId = ResourceLocation.tryParse(dim);
        ServerLevel level = dimId == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) level = player.serverLevel();
        if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) {
            tpResult(player, false, "worldmap.tp.border", 0);
            return;
        }
        int y = safeY(level, x, z);
        if (y == Integer.MIN_VALUE) {
            tpResult(player, false, "worldmap.tp.unsafe", 0);
            return;
        }
        player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
        COOLDOWN.put(player.getUUID(), now + config.teleportCooldownSeconds * 1000L);
        tpResult(player, true, "", 0);
    }

    private static int safeY(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (level.dimensionType().hasCeiling()) {
            for (int yy = level.getLogicalHeight() - 3; yy > level.getMinBuildHeight(); yy--)
                if (level.getBlockState(p.set(x, yy, z)).isSolid()
                        && level.getBlockState(p.set(x, yy + 1, z)).isAir()
                        && level.getBlockState(p.set(x, yy + 2, z)).isAir()) return yy + 1;
            return Integer.MIN_VALUE;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight()) return level.dimension() == Level.END ? Integer.MIN_VALUE : level.getSeaLevel();
        return y;
    }

    private static void tpResult(ServerPlayer p, boolean ok, String key, int arg) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(ok);
        buf.writeUtf(key);
        buf.writeVarInt(arg);
        ServerPlayNetworking.send(p, Net.TP_RESULT, buf);
    }

    private static void putMark(MinecraftServer server, ServerPlayer player, Mark m) {
        Mark old = MARKS.get(m.id);
        if (!canPublish(player)) return;
        if (old != null && !Objects.equals(old.owner, player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("worldmap.pub.not_owner").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (old == null && MARKS.size() >= config.maxPublicMarks) return;
        m.name = m.name.length() > 40 ? m.name.substring(0, 40) : m.name;
        m.owner = old != null ? old.owner : player.getUUID();
        m.ownerName = old != null ? old.ownerName : player.getGameProfile().getName();
        m.pub = true;
        MARKS.put(m.id, m);
        broadcast(server, Net.MARK_UPD, buf -> m.write(buf));
        save();
    }

    private static void deleteMark(MinecraftServer server, ServerPlayer player, UUID id) {
        Mark old = MARKS.get(id);
        if (old == null) return;
        if (!Objects.equals(old.owner, player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("worldmap.pub.not_owner").withStyle(ChatFormatting.RED), true);
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
        String dim = from.level().dimension().location().toString();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != from.level() || !ServerPlayNetworking.canSend(p, Net.PINGED)) continue;
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUtf(from.getGameProfile().getName(), 32);
            buf.writeVarInt(x);
            buf.writeVarInt(y);
            buf.writeVarInt(z);
            buf.writeUtf(dim);
            ServerPlayNetworking.send(p, Net.PINGED, buf);
        }
    }

    private static void broadcast(MinecraftServer server, ResourceLocation id, java.util.function.Consumer<FriendlyByteBuf> body) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!ServerPlayNetworking.canSend(p, id)) continue;
            FriendlyByteBuf buf = PacketByteBufs.create();
            body.accept(buf);
            ServerPlayNetworking.send(p, id, buf);
        }
    }
}
