package com.placeanywhere.mixin;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.FreeBlockInteractHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;




@Mixin(ScreenHandler.class)
public class ScreenHandlerMixin {

    @Inject(method = "canUse(Lnet/minecraft/screen/ScreenHandlerContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/block/Block;)Z", at = @At("HEAD"), cancellable = true)
    private static void placeanywhere$onCanUse(ScreenHandlerContext context, PlayerEntity player, Block block,
                                                CallbackInfoReturnable<Boolean> cir) {
        try {
            BlockPos pos = FreeBlockInteractHandler.getActiveGuiPos(context);
            boolean active = pos != null && FreeBlockInteractHandler.isActiveGuiPos(pos);
            PlaceAnywhereMod.LOGGER.info("[PA-GUI] canUse 注入：pos={} active={} block={}", pos, active, block);
            if (active) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[PA-GUI] canUse 注入异常", t);
        }
    }
}
