package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统7（红石逻辑）：在 {@code Level.getBestNeighborSignal} / {@code getDirectSignalTo} /
 * {@code getSignal} 的 RETURN 处，叠加附近自由方块发出的红石信号，使自由方块参与红石网络。
 *
 * 原 Fabric 版注入 RedstoneView 接口，Official 映射中这些方法直接位于 Level 上。
 */
@Mixin(Level.class)
public class RedstoneViewMixin {

    @Inject(method = "getBestNeighborSignal", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedRedstonePower(BlockPos pos,
                                                          CallbackInfoReturnable<Integer> cir) {
        Level self = (Level) (Object) this;
        int extra = FreeBlocks.getEmittedRedstoneAround(self, Vec3.atCenterOf(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    @Inject(method = "getDirectSignalTo", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedStrongRedstonePower(BlockPos pos,
                                                                 CallbackInfoReturnable<Integer> cir) {
        Level self = (Level) (Object) this;
        int extra = FreeBlocks.getStrongRedstoneAround(self, Vec3.atCenterOf(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    /**
     * 注入 {@code getSignal(BlockPos, Direction)}：叠加自由方块信号。
     *
     * 关键点：{@code level.hasNeighborSignal(pos)} 内部遍历 6 方向邻居调用此方法。
     * 必须注入此方法，否则铜灯/活塞的 hasNeighborSignal 永远返回 false。
     */
    @Inject(method = "getSignal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)I",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetEmittedRedstonePower(BlockPos pos, Direction direction,
                                                           CallbackInfoReturnable<Integer> cir) {
        Level self = (Level) (Object) this;
        int extra = FreeBlocks.getEmittedRedstoneFromDirection(self, Vec3.atCenterOf(pos), direction, 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }
}
