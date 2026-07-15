package com.placeanywhere.core;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

public final class ChunkFreeData {
    private final Int2ObjectOpenHashMap<FreeBlockLayer> layers = new Int2ObjectOpenHashMap<>(2);

    private final it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap sectionDirty = new it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap(2);
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

    public void markSectionDirty(int sectionY) {
        if (sectionY == Integer.MIN_VALUE) {
            for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<FreeBlockLayer> e : layers.int2ObjectEntrySet()) {
                sectionDirty.put(e.getIntKey(), true);
            }
        } else {
            sectionDirty.put(sectionY, true);
        }
    }

    public boolean isSectionDirty(int sectionY) {
        return sectionDirty.get(sectionY);
    }

    public void clearSectionDirty(int sectionY) {
        sectionDirty.remove(sectionY);
    }

    public void forEach(java.util.function.BiConsumer<DecimalBlockPos, net.minecraft.block.BlockState> action) {
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

    public NbtCompound writeNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("x", chunkX);
        tag.putInt("z", chunkZ);
        NbtList list = new NbtList();
        for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<FreeBlockLayer> e : layers.int2ObjectEntrySet()) {
            FreeBlockLayer layer = e.getValue();
            if (layer == null || layer.isEmpty()) continue;
            NbtCompound lt = layer.writeNbt();
            lt.putInt("sy", e.getIntKey());
            list.add(lt);
        }
        tag.put("layers", list);
        return tag;
    }

    public void readNbt(NbtCompound tag) {
        this.chunkX = tag.getInt("x");
        this.chunkZ = tag.getInt("z");
        layers.clear();
        sectionDirty.clear();
        NbtList list = tag.getList("layers", NbtCompound.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound lt = list.getCompound(i);
            int sy = lt.getInt("sy");
            FreeBlockLayer layer = new FreeBlockLayer();
            layer.readNbt(lt);
            if (!layer.isEmpty()) {
                layers.put(sy, layer);
                sectionDirty.put(sy, true); 
            }
        }
    }
}
