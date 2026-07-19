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










public final class FreeBlockNetworking {
    private FreeBlockNetworking() {}

    

    public static void sendToTrackers(ServerWorld world, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        FreeBlockSyncPayload payload = new FreeBlockSyncPayload(pos.x, pos.z, data.writeNbt());
        for (ServerPlayerEntity player : PlayerLookup.tracking(world, pos)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    
    public static void sendToPlayer(ServerPlayerEntity player, ChunkPos pos, ChunkFreeData data) {
        if (data == null) return;
        ServerPlayNetworking.send(player, new FreeBlockSyncPayload(pos.x, pos.z, data.writeNbt()));
    }

    
    public record FreeBlockSyncPayload(int chunkX, int chunkZ, NbtCompound nbt) implements CustomPayload {
        public static final CustomPayload.Id<FreeBlockSyncPayload> ID =
                new CustomPayload.Id<>(Identifier.of("placeanywherecore", "free_sync"));
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
