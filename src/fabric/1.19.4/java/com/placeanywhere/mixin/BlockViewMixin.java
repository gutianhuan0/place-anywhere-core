package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统5（射线检测）：在 {@code BlockView.raycast(RaycastContext)} 的 RETURN 处，
 * 与范围内自由方块做射线求交；若命中且比原版结果更近，则替换为自由方块命中结果。
 *
 * 这覆盖了玩家准星瞄准与服务端实体射线检测（二者都走 BlockView.raycast 默认实现）。
 *
 * 注意：BlockView 有两个 raycast 重载，这里用完整描述符锁定 RaycastContext 版本。
 */
@Mixin(BlockView.class)
public interface BlockViewMixin {

    @Inject(method = "raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onRaycast(RaycastContext context,
                                         CallbackInfoReturnable<BlockHitResult> cir) {
        if (!(this instanceof World world)) return;
        BlockHitResult base = cir.getReturnValue();
        var oh = FreeBlocks.raycast(world, context.getStart(), context.getEnd());
        if (oh.isEmpty()) return;
        FreeBlocks.FreeBlockHit h = oh.get();
        boolean closer = base == null
                || base.getType() == HitResult.Type.MISS
                || squaredDistanceTo(base.getPos(), context.getStart()) > h.distanceSq();
        if (closer) {
            cir.setReturnValue(new BlockHitResult(h.point(), h.side(), h.pos().toBlockPos(), false));
        }
    }

    private static double squaredDistanceTo(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
