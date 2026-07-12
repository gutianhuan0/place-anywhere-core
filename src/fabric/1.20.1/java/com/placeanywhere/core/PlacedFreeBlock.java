package com.placeanywhere.core;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;

/**
 * 查询结果：一个小数坐标方块（不可变快照）。
 * 由 {@link FreeBlocks} 的各类查询返回，供渲染/碰撞/射线/红石等子系统使用。
 *
 * @param pos  方块最小角的世界坐标
 * @param state 方块状态
 * @param qx,qy,qz,qw 旋转四元数（默认 identity = 0,0,0,1）
 * @param nbt  BlockEntity 的 NBT 数据（可为 null = 无 BE），用于箱子等容器持久化
 */
public record PlacedFreeBlock(DecimalBlockPos pos, BlockState state,
                              float qx, float qy, float qz, float qw,
                              NbtCompound nbt) {
    /** 向后兼容：无旋转（identity 四元数）、无 NBT。 */
    public PlacedFreeBlock(DecimalBlockPos pos, BlockState state) {
        this(pos, state, 0f, 0f, 0f, 1f, null);
    }

    /** 向后兼容：带旋转但无 NBT。 */
    public PlacedFreeBlock(DecimalBlockPos pos, BlockState state,
                           float qx, float qy, float qz, float qw) {
        this(pos, state, qx, qy, qz, qw, null);
    }
}
