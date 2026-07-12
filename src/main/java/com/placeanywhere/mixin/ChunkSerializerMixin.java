package com.placeanywhere.mixin;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockLoadQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统8（NBT 存档格式）：在 chunk 序列化/反序列化的 RETURN 处挂载自由方块数据。
 *
 *   write         : 把 LevelChunk 上的 FreeBlockData 写入返回的 CompoundTag（key = "placeanywhere_free"）。
 *   read          : ProtoChunk 阶段无法挂载，于是把 NBT 暂存到 {@link FreeBlockLoadQueue}，
 *                   等 ChunkEvent.Load 时应用到 LevelChunk（见 PlaceAnywhereMod）。
 *
 * 签名（1.20.1 Official）：
 *   write(ServerLevel, ChunkAccess) -> CompoundTag
 *   read(ServerLevel, PoiManager, ChunkPos, CompoundTag) -> ProtoChunk
 */
@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    @Inject(method = "write", at = @At("RETURN"))
    private static void placeanywhere$onSerialize(ServerLevel world, ChunkAccess chunk,
                                                  CallbackInfoReturnable<CompoundTag> cir) {
        if (chunk instanceof FreeBlockChunkAccess access) {
            ChunkFreeData data = access.placeanywhere_freeData();
            if (!data.isEmpty()) {
                cir.getReturnValue().put("placeanywhere_free", data.writeNbt());
            }
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void placeanywhere$onDeserialize(ServerLevel world, PoiManager poiStorage,
                                                    ChunkPos pos, CompoundTag nbt,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        if (nbt.contains("placeanywhere_free")) {
            FreeBlockLoadQueue.offer(world, pos.toLong(), nbt.getCompound("placeanywhere_free"));
        }
    }
}
