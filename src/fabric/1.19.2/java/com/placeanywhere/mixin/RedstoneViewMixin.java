package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 子系统7（红石逻辑）：在 {@code World.getReceivedRedstonePower} 的 RETURN 处，
 * 叠加附近自由方块发出的红石信号，使自由方块参与红石网络。
 *
 * 1.19.2 中红石方法直接在 World 上，不需要通过 RedstoneView 接口 mixin。
 */
@Mixin(World.class)
public abstract class RedstoneViewMixin {

    @Inject(method = "getReceivedRedstonePower", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedRedstonePower(BlockPos pos,
                                                          CallbackInfoReturnable<Integer> cir) {
        World world = (World)(Object)this;
        int extra = FreeBlocks.getEmittedRedstoneAround(world, Vec3d.ofCenter(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    @Inject(method = "getReceivedStrongRedstonePower", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetReceivedStrongRedstonePower(BlockPos pos,
                                                                 CallbackInfoReturnable<Integer> cir) {
        World world = (World)(Object)this;
        int extra = FreeBlocks.getStrongRedstoneAround(world, Vec3d.ofCenter(pos), 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }

    /**
     * 注入 {@code getEmittedRedstonePower(BlockPos, Direction)}（2-arg）：叠加自由方块信号。
     *
     * 关键点：{@code world.isReceivingRedstonePower(pos)} 内部遍历 6 方向邻居调用此 2-arg 版本。
     * 所以必须直接在 2-arg 层叠加自由方块信号。
     */
    @Inject(method = "getEmittedRedstonePower(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)I",
            at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetEmittedRedstonePower2(BlockPos pos, Direction direction,
                                                           CallbackInfoReturnable<Integer> cir) {
        World world = (World)(Object)this;
        int extra = FreeBlocks.getEmittedRedstoneFromDirection(world, Vec3d.ofCenter(pos), direction, 0.5);
        if (extra > cir.getReturnValue()) {
            cir.setReturnValue(extra);
        }
    }
}
