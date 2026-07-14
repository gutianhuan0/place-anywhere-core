package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;











@Mixin(LivingEntity.class)
public class TravelFrictionMixin {

    private static final ThreadLocal<Boolean> travelOnGround = ThreadLocal.withInitial(() -> false);

    @Inject(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At("HEAD"))
    private void placeanywhere$recordOnGround(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        travelOnGround.set(self.isOnGround());
    }

    @Inject(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At("RETURN"))
    private void placeanywhere$correctFriction(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        World world = self.getWorld();
        if (world == null) return;


        boolean wasOnGround = travelOnGround.get();
        if (!wasOnGround) return;


        Box box = self.getBoundingBox();
        BlockState supportState = FreeBlocks.findSupportingFreeBlock(world, box);
        if (supportState == null) return;


        float correctSlip = supportState.getBlock().getSlipperiness();


        net.minecraft.util.math.BlockPos belowPos = self.getVelocityAffectingPos();
        BlockState belowState = world.getBlockState(belowPos);
        float originalSlip = belowState.getBlock().getSlipperiness();


        float f3 = originalSlip * 0.91f;


        float correctF3 = correctSlip * 0.91f;


        if (Math.abs(f3 - correctF3) < 0.001f) return;


        float ratio = correctF3 / f3;

        Vec3d vel = self.getVelocity();
        self.setVelocity(vel.x * ratio, vel.y, vel.z * ratio);
    }
}
