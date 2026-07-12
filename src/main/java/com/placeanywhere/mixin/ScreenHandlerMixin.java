package com.placeanywhere.mixin;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.FreeBlockInteractHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 注入 AbstractContainerMenu.stillValid 静态方法：
 *  当 bpos 在 activeGuiPos 集合中时（自由方块 GUI 打开期间），跳过原版距离检查返回 true。
 *  自由方块的小数坐标对应的整数网格位置可能距玩家超过 8 格，需要此 Mixin 绕过。 */
@Mixin(AbstractContainerMenu.class)
public class ScreenHandlerMixin {

    @Inject(method = "stillValid(Lnet/minecraft/world/inventory/ContainerLevelAccess;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/block/Block;)Z", at = @At("HEAD"), cancellable = true)
    private static void placeanywhere$onCanUse(ContainerLevelAccess context, Player player, Block block,
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
