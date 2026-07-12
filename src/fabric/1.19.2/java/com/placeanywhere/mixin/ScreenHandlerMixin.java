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

/** 注入 ScreenHandler.canUse 静态方法：
 *  当 bpos 在 activeGuiPos 集合中时（自由方块 GUI 打开期间），跳过原版距离检查返回 true。
 *  自由方块的小数坐标对应的整数网格位置可能距玩家超过 8 格，需要此 Mixin 绕过。 */
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
