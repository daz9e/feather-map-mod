package dev.daze.worldmap.platform.fabric;

import dev.daze.worldmap.ServerMap;
import dev.daze.worldmap.platform.Platform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
/*import dev.daze.worldmap.platform.RawPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
*///?} else {
import dev.daze.worldmap.Net;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
//?}

import java.nio.file.Path;

public class FabricEntry implements ModInitializer, Platform {
    @Override
    public void onInitialize() {
        Platform.set(this);
        ServerLifecycleEvents.SERVER_STARTED.register(ServerMap::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerMap::onStopping);
        ServerTickEvents.END_SERVER_TICK.register(ServerMap::onTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerMap.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerMap.onPlayerLeave(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> ServerMap.registerCommands(dispatcher));

        //? if >=1.20.5 {
        /*registerPayloads();
        ServerPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, ctx) ->
                ServerMap.receive(ctx.player().level().getServer(), ctx.player(), payload.data()));
        *///?} else {
        ServerPlayNetworking.registerGlobalReceiver(Net.CHANNEL, (server, player, handler, buf, sender) ->
                ServerMap.receive(server, player, bytes(buf)));
        //?}
    }

    //? if >=1.20.5 {
    /*private static void registerPayloads() {
        //? if <26.1 {
        PayloadTypeRegistry.playC2S().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RawPayload.TYPE, RawPayload.CODEC);
        //?} else {
        /^PayloadTypeRegistry.serverboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        ^///?}
    }
    *///?}

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] data) {
        //? if >=1.20.5 {
        /*ServerPlayNetworking.send(player, new RawPayload(data));
        *///?} else
        ServerPlayNetworking.send(player, Net.CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    @Override
    public boolean canSend(ServerPlayer player) {
        //? if >=1.20.5 {
        /*return ServerPlayNetworking.canSend(player, RawPayload.TYPE);
        *///?} else
        return ServerPlayNetworking.canSend(player, Net.CHANNEL);
    }

    //? if <1.20.5 {
    static byte[] bytes(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return b;
    }
    //?}
}
