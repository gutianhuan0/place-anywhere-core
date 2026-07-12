package com.placeanywhere.core;

import com.placeanywhere.PlaceAnywhereMod;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 自由方块的网络同步（C/S）—— NeoForge 1.20.6 CustomPacketPayload 版本。
 *
 * 服务端是权威源：每次 place/remove 后把该 chunk 的完整自由方块 NBT 快照
 * 发给所有正在跟踪该 chunk 的玩家；玩家加入世界时补发附近已加载 chunk 的数据。
 * 客户端收到后直接覆盖写入 ClientLevel 中对应 LevelChunk 的 ChunkFreeData。
 *
 * 这是让"放置可见"的关键——没有它，服务端放下的自由方块永远不会出现在客户端。
 */
@EventBusSubscriber(modid = PlaceAnywhereMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class FreeBlockNetworking {
    private FreeBlockNetworking() {}

    @SubscribeEvent
    public static void onRegisterPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(FreeBlockInteractPayload.TYPE, FreeBlockInteractPayload.STREAM_CODEC,
                        FreeBlockInteractPayload::handle)
                .playToClient(SyncPacket.TYPE, SyncPacket.STREAM_CODEC, SyncPacket::handle);
    }

    /** 把该 chunk 的自由方块数据广播给所有跟踪它的玩家。
     *  即使 data 为空也发送（让客户端清除已移除的方块）。 */
    public static void sendToTrackers(ServerLevel world, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        CompoundTag nbt = data.writeNbt();
        SyncPacket packet = new SyncPacket(pos.x, pos.z, nbt);
        PacketDistributor.sendToPlayersTrackingChunk(world, pos, packet);
    }

    /** 发送给单个玩家（用于玩家加入世界时的初始同步）。 */
    public static void sendToPlayer(ServerPlayer player, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        CompoundTag nbt = data.writeNbt();
        SyncPacket packet = new SyncPacket(pos.x, pos.z, nbt);
        PacketDistributor.sendToPlayer(player, packet);
    }

    /** S2C 同步包：把一个 chunk 的自由方块 NBT 快照发给客户端。 */
    public record SyncPacket(int chunkX, int chunkZ, CompoundTag nbt) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SyncPacket> TYPE =
                new CustomPacketPayload.Type<>(new ResourceLocation("placeanywhere", "sync"));

        public static final StreamCodec<FriendlyByteBuf, SyncPacket> STREAM_CODEC =
                StreamCodec.of(SyncPacket::encode, SyncPacket::decode);

        public static void encode(FriendlyByteBuf buf, SyncPacket msg) {
            buf.writeVarInt(msg.chunkX());
            buf.writeVarInt(msg.chunkZ());
            buf.writeNbt(msg.nbt());
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readVarInt(), buf.readVarInt(), buf.readNbt());
        }

        public static void handle(SyncPacket msg, IPayloadContext context) {
            context.enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null || msg.nbt() == null) return;
                com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                        "[PA-Net] 收到同步包 chunk=({},{})", msg.chunkX(), msg.chunkZ());
                LevelChunk lc = mc.level.getChunk(msg.chunkX(), msg.chunkZ());
                ChunkFreeData data = ((FreeBlockChunkAccess) lc).placeanywhere_freeData();
                data.readNbt(msg.nbt());
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
