package com.placeanywhere.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.WeakHashMap;










public final class FreeBlockLoadQueue {
    private static final Map<ServerWorld, Long2ObjectOpenHashMap<NbtCompound>> MAP = new WeakHashMap<>();

    private FreeBlockLoadQueue() {}

    public static void offer(ServerWorld world, long chunkPos, NbtCompound data) {
        MAP.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>()).put(chunkPos, data);
    }

    public static NbtCompound poll(ServerWorld world, long chunkPos) {
        Long2ObjectOpenHashMap<NbtCompound> m = MAP.get(world);
        if (m == null) return null;
        NbtCompound d = m.get(chunkPos);
        if (d != null) m.remove(chunkPos);
        return d;
    }
}
