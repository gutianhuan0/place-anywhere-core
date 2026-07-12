package com.placeanywhere.core;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

/**
 * 自由方块的网络同步（C/S）。
 *
 * 服务端是权威源：每次 place/remove 后把该 chunk 的完整自由方块 NBT 快照
 * 发给所有正在跟踪该 chunk 的玩家；玩家加入世界时补发附近已加载 chunk 的数据。
 * 客户端收到后直接覆盖写入 ClientWorld 中对应 WorldChunk 的 ChunkFreeData。
 *
 * 这是让"放置可见"的关键——没有它，服务端放下的自由方块永远不会出现在客户端。
 */
public final class FreeBlockNetworking {
    private FreeBlockNetworking() {}

    /** 把该 chunk 的自由方块数据广播给所有跟踪它的玩家。
     *  即使 data 为空也发送（让客户端清除已移除的方块）。 */
    public static void sendToTrackers(ServerWorld world, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        FreeBlockSyncPayload payload = new FreeBlockSyncPayload(pos.x, pos.z, data.writeNbt());
        for (ServerPlayerEntity player : PlayerLookup.tracking(world, pos)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** 发送给单个玩家（用于玩家加入世界时的初始同步）。 */
    public static void sendToPlayer(ServerPlayerEntity player, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        ServerPlayNetworking.send(player, new FreeBlockSyncPayload(pos.x, pos.z, data.writeNbt()));
    }

    /** 自由方块同步 payload：携带 chunk 坐标与该 chunk 的完整自由方块 NBT 快照。 */
    public record FreeBlockSyncPayload(int chunkX, int chunkZ, NbtCompound nbt) implements CustomPayload {
        public static final CustomPayload.Id<FreeBlockSyncPayload> ID =
                new CustomPayload.Id<>(Identifier.of("placeanywhere", "free_sync"));
        public static final PacketCodec<PacketByteBuf, FreeBlockSyncPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, FreeBlockSyncPayload::chunkX,
                PacketCodecs.VAR_INT, FreeBlockSyncPayload::chunkZ,
                PacketCodecs.NBT_COMPOUND, FreeBlockSyncPayload::nbt,
                FreeBlockSyncPayload::new
        );
        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }
}
