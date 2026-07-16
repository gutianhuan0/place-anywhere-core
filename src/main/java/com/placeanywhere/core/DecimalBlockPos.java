package com.placeanywhere.core;

import net.minecraft.util.math.BlockPos;









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

    
    public BlockPos toBlockPos() {
        return BlockPos.ofFloored(x, y, z);
    }

    
    public int chunkX() {
        return Math.floorDiv((int) Math.floor(x), 16);
    }

    
    public int chunkZ() {
        return Math.floorDiv((int) Math.floor(z), 16);
    }

    
    public int sectionY() {
        return Math.floorDiv((int) Math.floor(y), 16);
    }

    
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
