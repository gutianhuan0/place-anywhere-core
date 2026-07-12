package com.placeanywhere.core;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 自由方块的网络同步（C/S）—— Forge SimpleChannel 版本。
 *
 * 服务端是权威源：每次 place/remove 后把该 chunk 的完整自由方块 NBT 快照
 * 发给所有正在跟踪该 chunk 的玩家；玩家加入世界时补发附近已加载 chunk 的数据。
 * 客户端收到后直接覆盖写入 ClientLevel 中对应 LevelChunk 的 ChunkFreeData。
 *
 * 这是让"放置可见"的关键——没有它，服务端放下的自由方块永远不会出现在客户端。
 */
public final class FreeBlockNetworking {
    private FreeBlockNetworking() {}

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("placeanywhere", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        // C2S: 自由方块交互
        INSTANCE.registerMessage(id++, FreeBlockInteractPayload.class,
                FreeBlockInteractPayload::encode, FreeBlockInteractPayload::decode,
                FreeBlockInteractPayload::handle);
        // S2C: chunk 自由方块同步
        INSTANCE.registerMessage(id++, SyncPacket.class,
                SyncPacket::encode, SyncPacket::decode,
                SyncPacket::handle);
    }

    /** 把该 chunk 的自由方块数据广播给所有跟踪它的玩家。
     *  即使 data 为空也发送（让客户端清除已移除的方块）。 */
    public static void sendToTrackers(ServerLevel world, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        LevelChunk chunk = world.getChunk(pos.x, pos.z);
        CompoundTag nbt = data.writeNbt();
        SyncPacket packet = new SyncPacket(pos.x, pos.z, nbt);
        INSTANCE.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), packet);
    }

    /** 发送给单个玩家（用于玩家加入世界时的初始同步）。 */
    public static void sendToPlayer(ServerPlayer player, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        CompoundTag nbt = data.writeNbt();
        SyncPacket packet = new SyncPacket(pos.x, pos.z, nbt);
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** S2C 同步包：把一个 chunk 的自由方块 NBT 快照发给客户端。 */
    public record SyncPacket(int chunkX, int chunkZ, CompoundTag nbt) {
        public static void encode(SyncPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.chunkX());
            buf.writeVarInt(msg.chunkZ());
            buf.writeNbt(msg.nbt());
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readVarInt(), buf.readVarInt(), buf.readNbt());
        }

        public static void handle(SyncPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null || msg.nbt() == null) return;
                com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                        "[PA-Net] 收到同步包 chunk=({},{})", msg.chunkX(), msg.chunkZ());
                LevelChunk lc = mc.level.getChunk(msg.chunkX(), msg.chunkZ());
                ChunkFreeData data = ((FreeBlockChunkAccess) lc).placeanywhere_freeData();
                data.readNbt(msg.nbt());
            });
            ctx.setPacketHandled(true);
        }
    }
}
