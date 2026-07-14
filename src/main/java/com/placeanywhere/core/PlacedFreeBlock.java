package com.placeanywhere.core;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;










public record PlacedFreeBlock(DecimalBlockPos pos, BlockState state,
                              float qx, float qy, float qz, float qw,
                              NbtCompound nbt) {

    public PlacedFreeBlock(DecimalBlockPos pos, BlockState state) {
        this(pos, state, 0f, 0f, 0f, 1f, null);
    }


    public PlacedFreeBlock(DecimalBlockPos pos, BlockState state,
                           float qx, float qy, float qz, float qw) {
        this(pos, state, qx, qy, qz, qw, null);
    }
}
