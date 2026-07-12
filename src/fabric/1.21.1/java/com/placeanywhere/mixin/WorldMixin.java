package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 子系统6（邻居更新）：当原版通知整数网格邻居时，顺带通知附近的自由方块，
 * 让自由方块也能响应红石/活塞/液体等邻居变化。
 *
 * 目标方法：{@code World.updateNeighbor(BlockPos, Block, BlockPos)}
 * （另一重载 updateNeighbor(BlockState,...) 用描述符区分避免误绑）。
 */
@Mixin(World.class)
public class WorldMixin {

    @Inject(method = "updateNeighbor(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("HEAD"))
    private void placeanywhere$onUpdateNeighbor(BlockPos pos, Block sourceBlock, BlockPos sourcePos, CallbackInfo ci) {
        World self = (World) (Object) this;
        FreeBlocks.notifyNeighborToFreeBlocks(self, pos, sourceBlock, sourcePos);
    }
}
