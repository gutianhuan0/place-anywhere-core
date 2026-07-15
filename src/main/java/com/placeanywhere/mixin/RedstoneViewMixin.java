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
