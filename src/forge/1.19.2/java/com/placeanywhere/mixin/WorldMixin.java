package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 子系统6（邻居更新）：当原版通知整数网格邻居时，顺带通知附近的自由方块，
 * 让自由方块也能响应红石/活塞/液体等邻居变化。
 *
 * 目标方法：{@code Level.updateNeighbor(BlockPos, Block, BlockPos)}
 * （另一重载 updateNeighbor(BlockState,...) 用描述符区分避免误绑）。
 */
@Mixin(Level.class)
public class WorldMixin {

    @Inject(method = "updateNeighbor(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"))
    private void placeanywhere$onUpdateNeighbor(BlockPos pos, Block sourceBlock, BlockPos sourcePos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        FreeBlocks.notifyNeighborToFreeBlocks(self, pos, sourceBlock, sourcePos);
    }
}
