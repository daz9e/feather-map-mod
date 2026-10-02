package dev.daze.worldmap;

import dev.daze.worldmap.platform.Platform;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Один канал worldmap:net, первым varint — тип сообщения. Сервер без мода канала не знает — тогда клиент работает автономно.
 */
public final class Net {
    public static final ResourceLocation CHANNEL = WorldMapMod.id("net");

    // клиент → сервер
    public static final int TELEPORT = 0, MARK_PUT = 1, MARK_DEL = 2, PING = 3;
    // сервер → клиент
    public static final int CONFIG = 10, MARKS = 11, MARK_UPD = 12, MARK_GONE = 13, PLAYERS = 14, PINGED = 15, TP_RESULT = 16;

    private Net() {}

    public static byte[] encode(int type, Consumer<FriendlyByteBuf> body) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(type);
        body.accept(buf);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    public static FriendlyByteBuf decode(byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    public static void toServer(int type, Consumer<FriendlyByteBuf> body) {
        Platform.get().sendToServer(encode(type, body));
    }

    public static void toPlayer(ServerPlayer p, int type, Consumer<FriendlyByteBuf> body) {
        Platform.get().sendToPlayer(p, encode(type, body));
    }

    public static boolean canSend(ServerPlayer p) {
        return Platform.get().canSend(p);
    }
}
