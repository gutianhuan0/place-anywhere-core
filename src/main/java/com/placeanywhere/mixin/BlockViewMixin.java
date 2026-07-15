package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import com.placeanywhere.core.PlacedFreeBlock;
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

            FreeBlocks.lastRaycastHit.set(
                    new PlacedFreeBlock(h.pos(), h.state(),
                            h.qx(), h.qy(), h.qz(), h.qw(), null));
            cir.setReturnValue(new BlockHitResult(h.point(), h.side(), h.pos().toBlockPos(), false));
        }
    }

    private static double squaredDistanceTo(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
