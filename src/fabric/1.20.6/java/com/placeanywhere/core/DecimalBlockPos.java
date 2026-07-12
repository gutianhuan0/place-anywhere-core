package com.placeanywhere.core;

import net.minecraft.util.math.BlockPos;

/**
 * DecimalBlockPos —— 用 double 坐标表示的方块位置，突破原版 BlockPos 的整数网格限制。
 *
 * 这是本模组所有子系统（渲染/碰撞/射线/邻居更新/红石/NBT）共用的位置类型。
 * 当需要与原版 API 交互时，通过 {@link #toBlockPos()} 向下取整为整数 BlockPos。
 *
 * 不可变、线程安全。
 */
public final class DecimalBlockPos {
    public static final DecimalBlockPos ZERO = new DecimalBlockPos(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double z;

    public DecimalBlockPos(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    /** 向下取整为原版整数 BlockPos（用于原版 API 的容错查询）。 */
    public BlockPos toBlockPos() {
        return BlockPos.ofFloored(x, y, z);
    }

    /** 所在区块 X（含负数处理）。 */
    public int chunkX() {
        return Math.floorDiv((int) Math.floor(x), 16);
    }

    /** 所在区块 Z。 */
    public int chunkZ() {
        return Math.floorDiv((int) Math.floor(z), 16);
    }

    /** 所在 section（16 格一组）的 Y 索引。 */
    public int sectionY() {
        return Math.floorDiv((int) Math.floor(y), 16);
    }

    /** 在所在 section 内的局部 X 坐标 [0,16)。 */
    public float localX() {
        return (float) (x - (double) chunkX() * 16.0);
    }

    public float localY() {
        return (float) (y - (double) sectionY() * 16.0);
    }

    public float localZ() {
        return (float) (z - (double) chunkZ() * 16.0);
    }

    public DecimalBlockPos add(double dx, double dy, double dz) {
        return new DecimalBlockPos(x + dx, y + dy, z + dz);
    }

    public double squaredDistanceTo(double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecimalBlockPos other)) return false;
        return Double.doubleToLongBits(x) == Double.doubleToLongBits(other.x)
                && Double.doubleToLongBits(y) == Double.doubleToLongBits(other.y)
                && Double.doubleToLongBits(z) == Double.doubleToLongBits(other.z);
    }

    @Override
    public int hashCode() {
        long bits = Double.doubleToLongBits(x);
        bits = 31 * bits + Double.doubleToLongBits(y);
        bits = 31 * bits + Double.doubleToLongBits(z);
        return (int) (bits ^ (bits >>> 32));
    }

    @Override
    public String toString() {
        return "DecimalBlockPos[" + x + ", " + y + ", " + z + "]";
    }
}
