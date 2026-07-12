package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OBB 碰撞系统（预移动裁剪 + 残穿安全网）：
 *
 * 参考瓦尔基里（Valkyrien Skies）的碰撞思路：
 *   1. @ModifyVariable 在 move() HEAD 裁剪移动向量，使实体永远不穿入 OBB
 *   2. @Inject 在 move() RETURN 做残穿推回安全网（处理生成在内部等边缘情况）
 *
 * 预裁剪优于后置推回：后置推回每帧穿入→推回→穿入，导致抖动和站不住。
 * 预裁剪让实体在移动前就被阻挡，与原版碰撞行为一致。
 */
@Mixin(Entity.class)
public class EntityCollisionMixin {

    /** 预移动裁剪：在 move() 执行前裁剪移动向量，防止穿入旋转 OBB。 */
    @ModifyVariable(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Vec3 placeanywhere$clipMovement(Vec3 movement) {
        Entity self = (Entity) (Object) this;
        return FreeBlocks.clipMovement(self, movement);
    }

    /** 残穿安全网：move() 执行后检查是否仍有穿入，推回处理边缘情况。 */
    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("RETURN"))
    private void placeanywhere$onMoveReturn(MoverType movementType, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        FreeBlocks.resolveRotatedCollisions(self);
    }
}
