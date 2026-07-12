package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 子系统4（碰撞检测）：在 {@code CollisionGetter.getBlockCollisions} 的 RETURN 处，
 * 把范围内自由方块的偏移 VoxelShape 合并进返回结果，使实体与自由方块发生碰撞。
 *
 * 目标是接口 default 方法，因此用接口 mixin；运行时 this 通常是 Level（ClientLevel/ServerLevel），
 * 非 Level 实现（如 ChunkRegion）直接跳过。
 */
@Mixin(CollisionGetter.class)
public interface CollisionViewMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetBlockCollisions(Entity entity, AABB box,
                                                    CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!(this instanceof Level level)) return;
        List<VoxelShape> extra = FreeBlocks.collectCollisionShapes(level, box);
        if (extra.isEmpty()) return;
        List<VoxelShape> merged = new ArrayList<>(16);
        cir.getReturnValue().forEach(merged::add);
        merged.addAll(extra);
        cir.setReturnValue(merged);
    }
}
