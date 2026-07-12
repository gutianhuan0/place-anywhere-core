package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 速度倍率 Mixin：让站在自由方块上的实体获得正确的速度/跳跃倍率。
 *
 * 原版 Entity.getVelocityMultiplier() 和 getJumpVelocityMultiplier() 通过
 * 整数网格 getBlockPosBelowThatAffectsMyMovement() 查询方块状态，
 * 但自由方块不在原版网格中，导致倍率来自脚下方整数位置的方块（通常是 AIR，倍率 1.0）。
 *
 * 本 Mixin 在方法 RETURN 时检查实体下方是否有自由方块，有则用自由方块的倍率覆盖。
 */
@Mixin(Entity.class)
public class EntityVelocityMixin {

    @Inject(method = "getVelocityMultiplier()F", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$modifyVelocityMultiplier(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        World world = self.getWorld();
        if (world == null) return;

        Box box = self.getBoundingBox();
        BlockState supportState = FreeBlocks.findSupportingFreeBlock(world, box);
        if (supportState != null) {
            cir.setReturnValue(supportState.getBlock().getVelocityMultiplier());
        }
    }

    @Inject(method = "getJumpVelocityMultiplier()F", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$modifyJumpVelocityMultiplier(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        World world = self.getWorld();
        if (world == null) return;

        Box box = self.getBoundingBox();
        BlockState supportState = FreeBlocks.findSupportingFreeBlock(world, box);
        if (supportState != null) {
            cir.setReturnValue(supportState.getBlock().getJumpVelocityMultiplier());
        }
    }
}
