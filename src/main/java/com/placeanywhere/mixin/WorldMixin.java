package com.placeanywhere.mixin;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.FreeBlocks;
import com.placeanywhere.core.PlacedFreeBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {

    @Inject(method = "updateNeighbor(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("HEAD"))
    private void placeanywhere$onUpdateNeighbor(BlockPos pos, Block sourceBlock, BlockPos sourcePos, CallbackInfo ci) {
        World self = (World) (Object) this;
        FreeBlocks.notifyNeighborToFreeBlocks(self, pos, sourceBlock, sourcePos);
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {

        java.util.Map<Long, BlockState> map = FreeBlocks.renderNeighborMap.get();
        if (map != null) {
            BlockState fbState = map.get(pos.asLong());
            if (fbState != null) {
                cir.setReturnValue(fbState);
                return;
            }
        }

        PlacedFreeBlock hit = FreeBlocks.lastRaycastHit.get();
        if (hit == null) return;

        if (hit.pos().toBlockPos().equals(pos)) {
            cir.setReturnValue(hit.state());
        }
    }
}
