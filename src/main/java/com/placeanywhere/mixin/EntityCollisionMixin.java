package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 碰撞 Mixin：用 OBB SAT 检测处理旋转自由方块的碰撞。
 *
 * 只在 adjustMovementForCollisions RETURN 处注入 OBB 精确裁剪。
 * 原版 move() 通过比较原始 movement.y 和裁剪后返回值自动设置 onGround。
 *
 * 不再注入 move() RETURN：
 *   旧方案的 resolveRotatedCollations 会强制 onGround=true，导致跳跃被取消。
 *   现在完全依赖原版 onGround 判断，避免干扰跳跃。
 *
 * 非旋转方块由 CollisionViewMixin 注入 VoxelShape 交给原版处理。
 */
@Mixin(Entity.class)
public class EntityCollisionMixin {

    @Inject(
            method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void placeanywhere$clipOBBCollision(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
        Entity self = (Entity) (Object) this;
        // 原版裁剪后的 movement（已处理原版方块 + 非旋转自由方块）
        Vec3d adjusted = cir.getReturnValue();
        // 对其再做旋转 OBB 精确裁剪（只可能进一步缩小移动量，不会放大）
        Vec3d clipped = FreeBlocks.clipMovement(self, adjusted);
        if (!clipped.equals(adjusted)) {
            cir.setReturnValue(clipped);
        }
    }
}
