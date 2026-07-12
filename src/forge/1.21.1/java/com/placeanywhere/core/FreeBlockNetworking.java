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
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 自由方块的网络同步（C/S）—— NeoForge 1.21.1 CustomPayload 版本。
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
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // C2S: 自由方块交互
        registrar.playToServer(FreeBlockInteractPayload.TYPE, FreeBlockInteractPayload.STREAM_CODEC,
                FreeBlockNetworking::handleInteract);
        // S2C: chunk 自由方块同步
        registrar.playToClient(SyncPacket.TYPE, SyncPacket.STREAM_CODEC,
                FreeBlockNetworking::handleSync);
    }

    /** 处理客户端发来的交互包（服务端执行）。 */
    private static void handleInteract(FreeBlockInteractPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                FreeBlockInteractHandler.handle(payload, player);
            }
        });
    }

    /** 处理服务端发来的同步包（客户端执行）。 */
    private static void handleSync(SyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || packet.nbt() == null) return;
            com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                    "[PA-Net] 收到同步包 chunk=({},{})", packet.chunkX(), packet.chunkZ());
            LevelChunk lc = mc.level.getChunk(packet.chunkX(), packet.chunkZ());
            ChunkFreeData data = ((FreeBlockChunkAccess) lc).placeanywhere_freeData();
            data.readNbt(packet.nbt());
        });
    }

    /** 客户端发送交互包到服务端。 */
    public static void sendToServer(FreeBlockInteractPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 把该 chunk 的自由方块数据广播给所有跟踪它的玩家。
     *  即使 data 为空也发送（让客户端清除已移除的方块）。 */
    public static void sendToTrackers(ServerLevel world, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        CompoundTag nbt = data.writeNbt();
        PacketDistributor.sendToPlayersTrackingChunk(world, pos, new SyncPacket(pos.x, pos.z, nbt));
    }

    /** 发送给单个玩家（用于玩家加入世界时的初始同步）。 */
    public static void sendToPlayer(ServerPlayer player, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        CompoundTag nbt = data.writeNbt();
        PacketDistributor.sendToPlayer(player, new SyncPacket(pos.x, pos.z, nbt));
    }

    /** S2C 同步包：把一个 chunk 的自由方块 NBT 快照发给客户端。 */
    public record SyncPacket(int chunkX, int chunkZ, CompoundTag nbt) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncPacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("placeanywhere", "sync"));

        public static final StreamCodec<FriendlyByteBuf, SyncPacket> STREAM_CODEC =
                StreamCodec.ofMember(SyncPacket::encode, SyncPacket::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(SyncPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.chunkX());
            buf.writeVarInt(msg.chunkZ());
            buf.writeNbt(msg.nbt());
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readVarInt(), buf.readVarInt(), buf.readNbt());
        }
    }
}
