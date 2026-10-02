package dev.daze.worldmap.platform.fabric;

import dev.daze.worldmap.Compat;
import dev.daze.worldmap.client.Hud;
import dev.daze.worldmap.client.WorldMapClient;
import dev.daze.worldmap.platform.Platform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if <26.1 {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?} else
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;*/
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
//? if >=1.21.6 {
/*import dev.daze.worldmap.WorldMapMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
*///?} else
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if >=1.20.5 {
/*import dev.daze.worldmap.platform.RawPayload;
*///?} else {
import dev.daze.worldmap.Net;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//?}

public class FabricClientEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if <26.1 {
        for (KeyMapping k : WorldMapClient.KEYS) KeyBindingHelper.registerKeyBinding(k);
        //?} else
        /*for (KeyMapping k : WorldMapClient.KEYS) KeyMappingHelper.registerKeyMapping(k);*/

        //? if >=1.20.5 {
        /*Platform.setClientSender(data -> ClientPlayNetworking.send(new RawPayload(data)));
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, ctx) -> WorldMapClient.receive(payload.data()));
        *///?} else {
        Platform.setClientSender(data -> ClientPlayNetworking.send(Net.CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(data))));
        ClientPlayNetworking.registerGlobalReceiver(Net.CHANNEL, (mc, handler, buf, sender) -> WorldMapClient.receive(FabricEntry.bytes(buf)));
        //?}

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> WorldMapClient.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> WorldMapClient.onDisconnect());
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> WorldMapClient.onChunkLoad(chunk.getPos()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> WorldMapClient.onTick());

        //? if >=1.21.6 {
        /*HudElementRegistry.addLast(WorldMapMod.id("hud"), (g, dt) -> Hud.render(g, dt.getGameTimeDeltaPartialTick(false)));
        *///?} elif >=1.21 {
        /*HudRenderCallback.EVENT.register((g, dt) -> Hud.render(g, dt.getGameTimeDeltaPartialTick(false)));
        *///?} else
        HudRenderCallback.EVENT.register(Hud::render);

        ClientReceiveMessageEvents.ALLOW_CHAT.register((msg, signed, sender, params, time) ->
                WorldMapClient.onChat(msg, sender != null ? Compat.name(sender) : null, false));
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> WorldMapClient.onChat(msg, null, overlay));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> WorldMapClient.registerCommands(dispatcher));
    }
}
