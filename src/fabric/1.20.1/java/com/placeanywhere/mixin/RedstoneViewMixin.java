package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RedstoneView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统7（红石逻辑）：在 {@code RedstoneView.getReceivedRedstonePower} 的 RETURN 处，
 * 叠加附近自由方块发出的红石信号，使自由方块参与红石网络。
 *
 * 目标是接口 default 方法；运行时 this 通常是 World。
 */
@Mixin(RedstoneView.class)
public interface RedstoneViewMixin {

    @Inject(method = "getReceivedRedstonePower", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedRedstonePower(BlockPos pos,
                                                          CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof World world)) return;
        int extra = FreeBlocks.getEmittedRedstoneAround(world, Vec3d.ofCenter(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    @Inject(method = "getReceivedStrongRedstonePower", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedStrongRedstonePower(BlockPos pos,
                                                                 CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof World world)) return;
        int extra = FreeBlocks.getStrongRedstoneAround(world, Vec3d.ofCenter(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    /**
     * 注入 {@code getEmittedRedstonePower(BlockPos, Direction, boolean)}（3-arg）：叠加自由方块信号。
     *
     * 这是带 fromBlock 标志的版本，被部分红石元件直接调用。用描述符精确匹配重载。
     */
    @Inject(method = "getEmittedRedstonePower(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;Z)I",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetEmittedRedstonePower3(BlockPos pos, Direction direction, boolean fromBlock,
                                                           CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof World world)) return;
        int extra = FreeBlocks.getEmittedRedstoneFromDirection(world, Vec3d.ofCenter(pos), direction, 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    /**
     * 注入 {@code getEmittedRedstonePower(BlockPos, Direction)}（2-arg）：叠加自由方块信号。
     *
     * 关键点：{@code world.isReceivingRedstonePower(pos)} 内部遍历 6 方向邻居调用此 2-arg 版本。
     * 接口 default 方法之间的自调用是 invokespecial，不会被 3-arg 注入拦截，
     * 因此必须单独注入 2-arg 版本，否则铜灯/活塞的 isReceivingRedstonePower 永远返回 false。
     *
     * 注意：即使 2-arg default 实现内部又调用 3-arg，该 invokespecial 同样绕过 3-arg 注入，
     * 所以这里必须直接在 2-arg 层叠加自由方块信号。
     */
    @Inject(method = "getEmittedRedstonePower(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)I",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetEmittedRedstonePower2(BlockPos pos, Direction direction,
                                                           CallbackInfoReturnable<Integer> cir) {
        if (!(this instanceof World world)) return;
        int extra = FreeBlocks.getEmittedRedstoneFromDirection(world, Vec3d.ofCenter(pos), direction, 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }
}
