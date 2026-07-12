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

/**
 * 摩擦力修正 Mixin：在 LivingEntity.travel() 执行后修正水平速度。
 *
 * 原版 travel() 通过整数网格查询方块滑度，但自由方块不在原版网格中，
 * 导致滑度来自脚下方整数位置的方块（通常是 AIR，slip=0.6）。
 *
 * 关键：只在实体处于地面状态（wasOnGround=true）时才修正摩擦力。
 * 空中时原版用 f3=0.91（不依赖方块），不需要修正。
 * 如果空中时也修正，上坡短暂离地会导致 ratio=0.6，水平速度被大幅减速 → "阻力大"。
 */
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

        // travel() HEAD 时的 onGround。空中时不修正——原版空中 f3=0.91 是正确的。
        boolean wasOnGround = travelOnGround.get();
        if (!wasOnGround) return;

        // 检查实体是否站在自由方块上
        Box box = self.getBoundingBox();
        BlockState supportState = FreeBlocks.findSupportingFreeBlock(world, box);
        if (supportState == null) return;

        // 获取正确滑度（自由方块的滑度）
        float correctSlip = supportState.getBlock().getSlipperiness();

        // 获取原版查询到的滑度（脚下方整数位置的方块）
        net.minecraft.util.math.BlockPos belowPos = self.getVelocityAffectingPos();
        BlockState belowState = world.getBlockState(belowPos);
        float originalSlip = belowState.getBlock().getSlipperiness();

        // wasOnGround=true 时 travel() 内用的 f3
        float f3 = originalSlip * 0.91f;

        // 正确的 f3 应该是 correctSlip * 0.91
        float correctF3 = correctSlip * 0.91f;

        // 如果两者相同则不需要修正
        if (Math.abs(f3 - correctF3) < 0.001f) return;

        // 修正比例 = correctF3 / f3 = correctSlip / originalSlip
        float ratio = correctF3 / f3;

        Vec3d vel = self.getVelocity();
        self.setVelocity(vel.x * ratio, vel.y, vel.z * ratio);
    }
}
