package dev.daze.worldmap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Различия API Minecraft между версиями (общая/серверная часть). */
public final class Compat {
    private Compat() {}

    public static MinecraftServer server(ServerPlayer p) {
        //? if <1.21.6 {
        return p.server;
        //?} else
        /*return p.level().getServer();*/
    }

    public static ServerLevel level(ServerPlayer p) {
        //? if <1.21.6 {
        return p.serverLevel();
        //?} else
        /*return p.level();*/
    }

    public static String name(Player p) {
        return name(p.getGameProfile());
    }

    public static String name(com.mojang.authlib.GameProfile profile) {
        //? if <1.21.9 {
        return profile.getName();
        //?} else
        /*return profile.name();*/
    }

    /** Права оператора (уровень 2). */
    public static boolean op(Player p) {
        //? if <1.21.11 {
        return p.hasPermissions(2);
        //?} else
        /*return p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);*/
    }

    public static boolean op(CommandSourceStack s) {
        //? if <1.21.11 {
        return s.hasPermission(2);
        //?} else
        /*return s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);*/
    }

    public static long chunkKey(int x, int z) {
        //? if <26.1 {
        return net.minecraft.world.level.ChunkPos.asLong(x, z);
        //?} else
        /*return net.minecraft.world.level.ChunkPos.pack(x, z);*/
    }

    public static long chunkKey(net.minecraft.world.level.ChunkPos p) {
        return chunkKey(p.getMinBlockX() >> 4, p.getMinBlockZ() >> 4);
    }

    /** Сообщение над хотбаром. */
    public static void actionBar(Player p, net.minecraft.network.chat.Component c) {
        //? if <26.1 {
        p.displayClientMessage(c, true);
        //?} else
        /*p.sendOverlayMessage(c);*/
    }

    public static String dimId(Level level) {
        return keyId(level.dimension());
    }

    public static String keyId(net.minecraft.resources.ResourceKey<?> key) {
        //? if <1.21.11 {
        return key.location().toString();
        //?} else
        /*return key.identifier().toString();*/
    }

    public static int minY(net.minecraft.world.level.LevelHeightAccessor level) {
        //? if <1.21.2 {
        return level.getMinBuildHeight();
        //?} else
        /*return level.getMinY();*/
    }

    /** Верхняя граница (не включительно). */
    public static int maxY(net.minecraft.world.level.LevelHeightAccessor level) {
        //? if <1.21.2 {
        return level.getMaxBuildHeight();
        //?} else
        /*return level.getMaxY() + 1;*/
    }

    public static void teleport(ServerPlayer p, ServerLevel level, double x, double y, double z) {
        //? if <1.21.2 {
        p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        //?} else
        /*p.teleportTo(level, x, y, z, java.util.Set.of(), p.getYRot(), p.getXRot(), true);*/
    }
}
