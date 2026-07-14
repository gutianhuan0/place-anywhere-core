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
