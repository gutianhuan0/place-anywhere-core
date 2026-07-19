package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;








@Mixin(Entity.class)
public class EntityCollisionMixin {

    @Inject(
            method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void placeanywhere$clipOBBCollision(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
        Entity self = (Entity) (Object) this;
        Vec3d adjusted = cir.getReturnValue();
        Vec3d clipped = FreeBlocks.clipMovement(self, adjusted);

        if (!clipped.equals(adjusted)) {
            cir.setReturnValue(clipped);
        }
    }

    




    @Inject(
            method = "move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V",
            at = @At("RETURN")
    )
    private void placeanywhere$forceOnGround(net.minecraft.entity.MovementType type, Vec3d movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        
        
        FreeBlocks.resolveRotatedCollisions(self);

        
        if (self.isOnGround()) return;

        World world = self.getWorld();
        if (world == null) return;

        
        net.minecraft.block.BlockState support = FreeBlocks.findSupportingFreeBlock(world, self);
        if (support != null) {
            self.setOnGround(true);
            self.fallDistance = 0f;
        }
    }
}
