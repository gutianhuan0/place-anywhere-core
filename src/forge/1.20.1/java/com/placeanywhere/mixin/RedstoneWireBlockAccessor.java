package com.placeanywhere.mixin;

import net.minecraft.world.level.block.RedStoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 子系统7（红石逻辑）：访问 {@link RedStoneWireBlock} 的私有字段 {@code shouldSignal}。
 *
 * 红石粉在计算自身 power 时会临时将此字段置 false（禁止 wire-to-wire 通过接口查询递归）。
 * 自由方块上的红石粉无法走原版 update 路径，需在 {@code FreeBlocks.recomputeWirePower}
 * 中手动切换此标志，复用原版 {@code level.getBestNeighborSignal} 查询非红石粉信号源。
 */
@Mixin(RedStoneWireBlock.class)
public interface RedstoneWireBlockAccessor {

    @Accessor("shouldSignal")
    void placeanywhere$setWiresGivePower(boolean value);

    @Accessor("shouldSignal")
    boolean placeanywhere$getWiresGivePower();
}
