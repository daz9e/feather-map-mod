package dev.daze.worldmap.platform.forge;

import dev.daze.worldmap.Net;
import dev.daze.worldmap.client.Hud;
import dev.daze.worldmap.client.WorldMapClient;
import dev.daze.worldmap.platform.Platform;
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Клиентская часть для Forge 1.20.1. */
final class ForgeClientEntry {
    private ForgeClientEntry() {}

    static void init(IEventBus modBus) {
        Platform.setClientSender(data -> {
            var conn = Minecraft.getInstance().getConnection();
            if (conn != null) conn.send(new ServerboundCustomPayloadPacket(Net.CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(data))));
        });
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            for (KeyMapping k : WorldMapClient.KEYS) e.register(k);
        });
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((ResourceManagerReloadListener) rm -> WorldMapClient.onResourcesReloaded()));

        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> WorldMapClient.onJoin());
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> WorldMapClient.onDisconnect());
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide() && e.getChunk() instanceof LevelChunk) WorldMapClient.onChunkLoad(e.getChunk().getPos());
        });
        bus.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) WorldMapClient.onTick();
        });
        bus.addListener((RenderGuiEvent.Post e) -> Hud.render(e.getGuiGraphics(), e.getPartialTick()));
        bus.addListener((ClientChatReceivedEvent e) -> {
            boolean overlay = e instanceof ClientChatReceivedEvent.System s && s.isOverlay();
            if (!WorldMapClient.onChat(e.getMessage(), WorldMapClient.playerName(e.getSender()), overlay)) e.setCanceled(true);
        });
        bus.addListener((RegisterClientCommandsEvent e) -> WorldMapClient.registerCommands(e.getDispatcher()));
    }

    static void receive(byte[] data) {
        WorldMapClient.receive(data);
    }
}
