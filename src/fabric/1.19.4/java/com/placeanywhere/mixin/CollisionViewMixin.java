package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.CollisionView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 子系统4（碰撞检测）：在 {@code CollisionView.getBlockCollisions} 的 RETURN 处，
 * 把范围内自由方块的偏移 VoxelShape 合并进返回结果，使实体与自由方块发生碰撞。
 *
 * 目标是接口 default 方法，因此用接口 mixin；运行时 this 通常是 World（ClientWorld/ServerWorld），
 * 非 World 实现（如 ChunkRegion）直接跳过。
 */
@Mixin(CollisionView.class)
public interface CollisionViewMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetBlockCollisions(Entity entity, Box box,
                                                    CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!(this instanceof World world)) return;
        List<VoxelShape> extra = FreeBlocks.collectCollisionShapes(world, box);
        if (extra.isEmpty()) return;
        List<VoxelShape> merged = new ArrayList<>(16);
        cir.getReturnValue().forEach(merged::add);
        merged.addAll(extra);
        cir.setReturnValue(merged);
    }
}
