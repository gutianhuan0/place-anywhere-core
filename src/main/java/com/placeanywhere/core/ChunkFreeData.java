package com.placeanywhere.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * ChunkFreeData —— 一个 Chunk 内全部 section 的自由方块集合。
 *
 * 内部用 {@code Int2ObjectMap<FreeBlockLayer>}，key = sectionY（含负值，自定义世界高度也可工作）。
 * 这是对原版 PalettedContainer 数组（按 section 固定下标）的弹性替代：只为真正含有自由方块的
 * section 分配层，省内存。
 */
public final class ChunkFreeData {
    private final Int2ObjectOpenHashMap<FreeBlockLayer> layers = new Int2ObjectOpenHashMap<>(2);
    private int chunkX;
    private int chunkZ;

    public ChunkFreeData(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }

    public FreeBlockLayer get(int sectionY) {
        return layers.get(sectionY);
    }

    public FreeBlockLayer getOrCreate(int sectionY) {
        return layers.computeIfAbsent(sectionY, k -> new FreeBlockLayer());
    }

    public boolean isEmpty() {
        if (layers.isEmpty()) return true;
        for (FreeBlockLayer l : layers.values()) if (!l.isEmpty()) return false;
        return true;
    }

    /** 遍历本 chunk 所有自由方块（世界绝对坐标）。 */
    public void forEach(java.util.function.BiConsumer<DecimalBlockPos, net.minecraft.world.level.block.state.BlockState> action) {
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<FreeBlockLayer> e : layers.int2ObjectEntrySet()) {
            int sy = e.getIntKey();
            FreeBlockLayer layer = e.getValue();
            if (layer == null || layer.isEmpty()) continue;
            int originX = chunkX << 4;
            int originY = sy << 4;
            int originZ = chunkZ << 4;
            layer.forEach(originX, originY, originZ, action);
        }
    }

    // ---------- NBT ----------

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", chunkX);
        tag.putInt("z", chunkZ);
        ListTag list = new ListTag();
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<FreeBlockLayer> e : layers.int2ObjectEntrySet()) {
            FreeBlockLayer layer = e.getValue();
            if (layer == null || layer.isEmpty()) continue;
            CompoundTag lt = layer.writeNbt();
            lt.putInt("sy", e.getIntKey());
            list.add(lt);
        }
        tag.put("layers", list);
        return tag;
    }

    public void readNbt(CompoundTag tag) {
        this.chunkX = tag.getInt("x");
        this.chunkZ = tag.getInt("z");
        layers.clear();
        ListTag list = tag.getList("layers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag lt = list.getCompound(i);
            int sy = lt.getInt("sy");
            FreeBlockLayer layer = new FreeBlockLayer();
            layer.readNbt(lt);
            if (!layer.isEmpty()) layers.put(sy, layer);
        }
    }
}
