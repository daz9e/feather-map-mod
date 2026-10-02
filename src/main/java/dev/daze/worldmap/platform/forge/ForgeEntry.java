package dev.daze.worldmap.platform.forge;

import dev.daze.worldmap.Net;
import dev.daze.worldmap.ServerMap;
import dev.daze.worldmap.WorldMapMod;
import dev.daze.worldmap.platform.Platform;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Path;

/** Forge 1.20.1 (его же загружает NeoForge 47.1). */
@Mod(WorldMapMod.MODID)
public class ForgeEntry implements Platform {
    /** Канал необязательный: подключаемся к серверам и клиентам без мода. */
    static final EventNetworkChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(Net.CHANNEL)
            .networkProtocolVersion(() -> "1")
            .clientAcceptedVersions(v -> true)
            .serverAcceptedVersions(v -> true)
            .eventNetworkChannel();

    public ForgeEntry() {
        Platform.set(this);
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent e) -> ServerMap.onStarted(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> ServerMap.onStopping(e.getServer()));
        bus.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) ServerMap.onTick(ServerLifecycleHooks.getCurrentServer());
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) ServerMap.onPlayerJoin(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) ServerMap.onPlayerLeave(p);
        });
        bus.addListener((RegisterCommandsEvent e) -> ServerMap.registerCommands(e.getDispatcher()));
        CHANNEL.addListener((NetworkEvent e) -> {
            if (e.getPayload() == null) return;
            NetworkEvent.Context ctx = e.getSource().get();
            ServerPlayer sender = ctx.getSender();
            if (sender != null) ServerMap.receive(sender.server, sender, bytes(e.getPayload()));
            else if (FMLEnvironment.dist == Dist.CLIENT) ForgeClientEntry.receive(bytes(e.getPayload()));
            ctx.setPacketHandled(true);
        });
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeClientEntry.init(FMLJavaModLoadingContext.get().getModEventBus());
    }

    static byte[] bytes(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] data) {
        player.connection.send(new ClientboundCustomPayloadPacket(Net.CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(data))));
    }

    @Override
    public boolean canSend(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }
}
