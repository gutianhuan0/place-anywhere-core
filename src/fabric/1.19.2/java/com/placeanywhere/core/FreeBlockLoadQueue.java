package com.placeanywhere.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 自由方块 NBT 的"反序列化→区块加载"中转队列。
 *
 * ChunkSerializer.deserialize 返回 ProtoChunk，此时还不是 WorldChunk，无法直接挂载数据。
 * 因此 deserialize 阶段把 NBT 暂存到这里（按世界 + 区块坐标索引），
 * 等 ServerChunkEvents.CHUNK_LOAD 触发、WorldChunk 真正生成后再取出应用。
 *
 * 用 WeakHashMap 以世界为 key，世界卸载时自动清理，避免内存泄漏。
 */
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
