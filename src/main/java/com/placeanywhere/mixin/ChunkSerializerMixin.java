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
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;












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
                                                    StorageKey storageKey, ChunkPos pos, NbtCompound nbt,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        if (nbt.contains("placeanywhere_free")) {
            FreeBlockLoadQueue.offer(world, pos.toLong(), nbt.getCompound("placeanywhere_free"));
        }
    }
}
