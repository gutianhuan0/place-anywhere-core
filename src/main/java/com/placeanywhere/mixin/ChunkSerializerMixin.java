package com.placeanywhere.mixin;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockLoadQueue;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统8（NBT 存档格式）：在 chunk 序列化/反序列化的 RETURN 处挂载自由方块数据。
 *
 *   serialize  : 把 WorldChunk 上的 FreeBlockData 写入返回的 NbtCompound（key = "placeanywhere_free"）。
 *   deserialize: ProtoChunk 阶段无法挂载，于是把 NBT 暂存到 {@link FreeBlockLoadQueue}，
 *                等 CHUNK_LOAD 时应用到 WorldChunk（见 PlaceAnywhereMod）。
 *
 * 签名（1.20.1）：
 *   serialize(ServerWorld, Chunk) -> NbtCompound
 *   deserialize(ServerWorld, PointOfInterestStorage, ChunkPos, NbtCompound) -> ProtoChunk
 */
@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    @Inject(method = "serialize", at = @At("RETURN"))
    private static void placeanywhere$onSerialize(ServerWorld world, Chunk chunk,
                                                  CallbackInfoReturnable<NbtCompound> cir) {
        if (chunk instanceof FreeBlockChunkAccess access) {
            ChunkFreeData data = access.placeanywhere_freeData();
            if (!data.isEmpty()) {
                cir.getReturnValue().put("placeanywhere_free", data.writeNbt());
            }
        }
    }

    @Inject(method = "deserialize", at = @At("RETURN"))
    private static void placeanywhere$onDeserialize(ServerWorld world, PointOfInterestStorage poiStorage,
                                                    ChunkPos pos, NbtCompound nbt,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        if (nbt.contains("placeanywhere_free")) {
            FreeBlockLoadQueue.offer(world, pos.toLong(), nbt.getCompound("placeanywhere_free"));
        }
    }
}
