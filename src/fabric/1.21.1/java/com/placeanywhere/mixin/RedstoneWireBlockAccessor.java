package com.placeanywhere.mixin;

import net.minecraft.block.RedstoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 子系统7（红石逻辑）：访问 {@link RedstoneWireBlock} 的私有字段 {@code wiresGivePower}。
 *
 * 红石粉在计算自身 power 时会临时将此字段置 false（禁止 wire-to-wire 通过接口查询递归）。
 * 自由方块上的红石粉无法走原版 update 路径，需在 {@code FreeBlocks.recomputeWirePower}
 * 中手动切换此标志，复用原版 {@code world.getReceivedRedstonePower} 查询非红石粉信号源。
 */
@Mixin(RedstoneWireBlock.class)
public interface RedstoneWireBlockAccessor {

    @Accessor("wiresGivePower")
    void placeanywhere$setWiresGivePower(boolean value);

    @Accessor("wiresGivePower")
    boolean placeanywhere$getWiresGivePower();
}
