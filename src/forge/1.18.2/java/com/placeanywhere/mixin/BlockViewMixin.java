package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统5（射线检测）：在 {@code BlockGetter.clip(ClipContext)} 的 RETURN 处，
 * 与范围内自由方块做射线求交；若命中且比原版结果更近，则替换为自由方块命中结果。
 *
 * 这覆盖了玩家准星瞄准与服务端实体射线检测（二者都走 BlockGetter.clip 默认实现）。
 *
 * 注意：BlockGetter 有两个 clip 重载，这里用完整描述符锁定 ClipContext 版本。
 */
@Mixin(BlockGetter.class)
public interface BlockViewMixin {

    @Inject(method = "clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onRaycast(ClipContext context,
                                         CallbackInfoReturnable<BlockHitResult> cir) {
        if (!(this instanceof Level level)) return;
        BlockHitResult base = cir.getReturnValue();
        var oh = FreeBlocks.raycast(level, context.getFrom(), context.getTo());
        if (oh.isEmpty()) return;
        FreeBlocks.FreeBlockHit h = oh.get();
        boolean closer = base == null
                || base.getType() == HitResult.Type.MISS
                || squaredDistanceTo(base.getLocation(), context.getFrom()) > h.distanceSq();
        if (closer) {
            cir.setReturnValue(new BlockHitResult(h.point(), h.side(), h.pos().toBlockPos(), false));
        }
    }

    private static double squaredDistanceTo(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
