package dev.daze.worldmap.platform;

// Пакет канала worldmap:net — просто байты; разбирают их Net/ServerMap/WorldMapClient.

//? if >=1.20.5 {
/*import dev.daze.worldmap.Net;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RawPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<RawPayload> TYPE = new Type<>(Net.CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, RawPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.data), RawPayload::read);

    @Override
    public Type<RawPayload> type() {
        return TYPE;
    }

    public static RawPayload read(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return new RawPayload(b);
    }
}
*///?} elif >=1.20.2 && neoforge {
/*import dev.daze.worldmap.Net;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RawPayload(byte[] data) implements CustomPacketPayload {
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public ResourceLocation id() {
        return Net.CHANNEL;
    }

    public static RawPayload read(FriendlyByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return new RawPayload(b);
    }
}
*///?}
