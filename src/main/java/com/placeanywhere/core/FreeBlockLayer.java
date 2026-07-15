package com.placeanywhere.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;

import java.util.Arrays;
import java.util.function.BiConsumer;

public final class FreeBlockLayer {
    private final ObjectArrayList<BlockState> palette = new ObjectArrayList<>();
    private float[] xs = new float[8];
    private float[] ys = new float[8];
    private float[] zs = new float[8];
    private float[] qx = new float[8];
    private float[] qy = new float[8];
    private float[] qz = new float[8];
    private float[] qw = new float[8];
    private NbtCompound[] nbts = new NbtCompound[8];
    private int[] paletteIdx = new int[8];
    private int size = 0;

    private static final int BUCKET_SIZE = 2;
    private static final int BUCKETS_PER_AXIS = 16 / BUCKET_SIZE;
    private static final int BUCKET_COUNT = BUCKETS_PER_AXIS * BUCKETS_PER_AXIS * BUCKETS_PER_AXIS;

    private it.unimi.dsi.fastutil.ints.IntArrayList[] buckets;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public BlockState palette(int index) { return palette.get(index); }
    public float x(int i) { return xs[i]; }
    public float y(int i) { return ys[i]; }
    public float z(int i) { return zs[i]; }
    public float qx(int i) { return qx[i]; }
    public float qy(int i) { return qy[i]; }
    public float qz(int i) { return qz[i]; }
    public float qw(int i) { return qw[i]; }
    public NbtCompound nbt(int i) { return nbts[i]; }
    public int paletteIndex(int i) { return paletteIdx[i]; }
    public BlockState state(int i) { return palette.get(paletteIdx[i]); }

    private int addPalette(BlockState state) {
        int idx = palette.indexOf(state);
        if (idx < 0) { palette.add(state); idx = palette.size() - 1; }
        return idx;
    }

    private int bucketIndex(float lx, float ly, float lz) {
        int bx = Math.min((int)(lx / BUCKET_SIZE), BUCKETS_PER_AXIS - 1);
        int by = Math.min((int)(ly / BUCKET_SIZE), BUCKETS_PER_AXIS - 1);
        int bz = Math.min((int)(lz / BUCKET_SIZE), BUCKETS_PER_AXIS - 1);
        if (bx < 0) bx = 0; if (by < 0) by = 0; if (bz < 0) bz = 0;
        return (by * BUCKETS_PER_AXIS + bz) * BUCKETS_PER_AXIS + bx;
    }

    @SuppressWarnings("unchecked")
    private void ensureBuckets() {
        if (buckets == null) buckets = new it.unimi.dsi.fastutil.ints.IntArrayList[BUCKET_COUNT];
    }

    private void rebuildIndex() {
        ensureBuckets();
        Arrays.fill(buckets, null);
        for (int i = 0; i < size; i++) {
            int b = bucketIndex(xs[i], ys[i], zs[i]);
            if (buckets[b] == null) buckets[b] = new it.unimi.dsi.fastutil.ints.IntArrayList(4);
            buckets[b].add(i);
        }
    }

    public int add(float lx, float ly, float lz, BlockState state) {
        return add(lx, ly, lz, 0f, 0f, 0f, 1f, state);
    }

    public int add(float lx, float ly, float lz, float rqx, float rqy, float rqz, float rqw, BlockState state) {
        ensureCapacity(size + 1);
        xs[size] = lx; ys[size] = ly; zs[size] = lz;
        qx[size] = rqx; qy[size] = rqy; qz[size] = rqz; qw[size] = rqw;
        paletteIdx[size] = addPalette(state);
        ensureBuckets();
        int b = bucketIndex(lx, ly, lz);
        if (buckets[b] == null) buckets[b] = new it.unimi.dsi.fastutil.ints.IntArrayList(4);
        buckets[b].add(size);
        return size++;
    }

    public void setState(int index, BlockState state) {
        if (index < 0 || index >= size) return;
        paletteIdx[index] = addPalette(state);
    }

    public void setNbt(int index, NbtCompound nbt) {
        if (index < 0 || index >= size) return;
        nbts[index] = nbt == null ? null : nbt.copy();
    }

    public boolean remove(int index) {
        if (index < 0 || index >= size) return false;
        ensureBuckets();
        int last = size - 1;

        int delBucket = bucketIndex(xs[index], ys[index], zs[index]);
        it.unimi.dsi.fastutil.ints.IntArrayList delList = buckets[delBucket];
        if (delList != null) {

            for (int j = 0; j < delList.size(); j++) {
                if (delList.getInt(j) == index) {
                    int lastIdx = delList.size() - 1;
                    if (j != lastIdx) delList.set(j, delList.getInt(lastIdx));
                    delList.removeInt(lastIdx);
                    break;
                }
            }
        }
        if (index != last) {

            xs[index] = xs[last]; ys[index] = ys[last]; zs[index] = zs[last];
            qx[index] = qx[last]; qy[index] = qy[last]; qz[index] = qz[last]; qw[index] = qw[last];
            nbts[index] = nbts[last]; paletteIdx[index] = paletteIdx[last];

            int lastBucket = bucketIndex(xs[index], ys[index], zs[index]);
            it.unimi.dsi.fastutil.ints.IntArrayList lastList = buckets[lastBucket];
            if (lastList != null) {
                for (int j = 0; j < lastList.size(); j++) {
                    if (lastList.getInt(j) == last) {
                        lastList.set(j, index);
                        break;
                    }
                }
            }
        }
        nbts[last] = null;
        size--;
        return true;
    }

    public void clear() {
        size = 0; palette.clear();
        if (buckets != null) Arrays.fill(buckets, null);
    }

    private void ensureCapacity(int minCap) {
        if (minCap <= xs.length) return;
        int newCap = xs.length;
        while (newCap < minCap) newCap = Math.max(newCap + 8, newCap + (newCap >> 1));
        xs = grow(xs, newCap); ys = grow(ys, newCap); zs = grow(zs, newCap);
        qx = grow(qx, newCap); qy = grow(qy, newCap); qz = grow(qz, newCap); qw = grow(qw, newCap);
        nbts = grow(nbts, newCap); paletteIdx = grow(paletteIdx, newCap);
    }

    private static float[] grow(float[] a, int cap) {
        float[] n = new float[cap]; System.arraycopy(a, 0, n, 0, a.length); return n;
    }
    private static int[] grow(int[] a, int cap) {
        int[] n = new int[cap]; System.arraycopy(a, 0, n, 0, a.length); return n;
    }
    private static NbtCompound[] grow(NbtCompound[] a, int cap) {
        NbtCompound[] n = new NbtCompound[cap]; System.arraycopy(a, 0, n, 0, a.length); return n;
    }

    public void forEach(int sectionOriginX, int sectionOriginY, int sectionOriginZ,
                        BiConsumer<DecimalBlockPos, BlockState> action) {
        for (int i = 0; i < size; i++) {
            double wx = sectionOriginX + xs[i];
            double wy = sectionOriginY + ys[i];
            double wz = sectionOriginZ + zs[i];
            action.accept(new DecimalBlockPos(wx, wy, wz), palette.get(paletteIdx[i]));
        }
    }

    public void forEachInBox(int sectionOriginX, int sectionOriginY, int sectionOriginZ,
                             double boxMinX, double boxMinY, double boxMinZ,
                             double boxMaxX, double boxMaxY, double boxMaxZ,
                             java.util.function.Consumer<PlacedFreeBlock> action) {
        if (size == 0) return;
        ensureBuckets();
        float lMinX = (float)(boxMinX - sectionOriginX);
        float lMinY = (float)(boxMinY - sectionOriginY);
        float lMinZ = (float)(boxMinZ - sectionOriginZ);
        float lMaxX = (float)(boxMaxX - sectionOriginX);
        float lMaxY = (float)(boxMaxY - sectionOriginY);
        float lMaxZ = (float)(boxMaxZ - sectionOriginZ);
        int bMinX = Math.max(0, Math.min((int)(lMinX / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        int bMinY = Math.max(0, Math.min((int)(lMinY / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        int bMinZ = Math.max(0, Math.min((int)(lMinZ / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        int bMaxX = Math.max(0, Math.min((int)(lMaxX / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        int bMaxY = Math.max(0, Math.min((int)(lMaxY / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        int bMaxZ = Math.max(0, Math.min((int)(lMaxZ / BUCKET_SIZE), BUCKETS_PER_AXIS - 1));
        for (int by = bMinY; by <= bMaxY; by++) {
            for (int bz = bMinZ; bz <= bMaxZ; bz++) {
                for (int bx = bMinX; bx <= bMaxX; bx++) {
                    int bIdx = (by * BUCKETS_PER_AXIS + bz) * BUCKETS_PER_AXIS + bx;
                    it.unimi.dsi.fastutil.ints.IntArrayList bucket = buckets[bIdx];
                    if (bucket == null) continue;
                    for (int j = 0; j < bucket.size(); j++) {
                        int i = bucket.getInt(j);
                        double wx = sectionOriginX + xs[i];
                        double wy = sectionOriginY + ys[i];
                        double wz = sectionOriginZ + zs[i];

                        if (wx + 1.5 <= boxMinX || wx - 0.5 >= boxMaxX) continue;
                        if (wy + 1.5 <= boxMinY || wy - 0.5 >= boxMaxY) continue;
                        if (wz + 1.5 <= boxMinZ || wz - 0.5 >= boxMaxZ) continue;
                        action.accept(new PlacedFreeBlock(
                                new DecimalBlockPos(wx, wy, wz), palette.get(paletteIdx[i]),
                                qx[i], qy[i], qz[i], qw[i], nbts[i]));
                    }
                }
            }
        }
    }

    public NbtCompound writeNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("size", size);
        int[] palRaw = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) palRaw[i] = Block.STATE_IDS.getRawId(palette.get(i));
        tag.putIntArray("palette", palRaw);
        tag.putIntArray("palIdx", Arrays.copyOf(paletteIdx, size));
        NbtList xl = new NbtList(), yl = new NbtList(), zl = new NbtList();
        NbtList qxl = new NbtList(), qyl = new NbtList(), qzl = new NbtList(), qwl = new NbtList();
        for (int i = 0; i < size; i++) {
            xl.add(NbtFloat.of(xs[i])); yl.add(NbtFloat.of(ys[i])); zl.add(NbtFloat.of(zs[i]));
            qxl.add(NbtFloat.of(qx[i])); qyl.add(NbtFloat.of(qy[i]));
            qzl.add(NbtFloat.of(qz[i])); qwl.add(NbtFloat.of(qw[i]));
        }
        tag.put("xs", xl); tag.put("ys", yl); tag.put("zs", zl);
        tag.put("qxs", qxl); tag.put("qys", qyl); tag.put("qzs", qzl); tag.put("qws", qwl);
        NbtList nbtList = new NbtList();
        for (int i = 0; i < size; i++) {
            if (nbts[i] == null) continue;
            NbtCompound entry = new NbtCompound();
            entry.putInt("idx", i); entry.put("data", nbts[i]);
            nbtList.add(entry);
        }
        tag.put("nbts", nbtList);
        return tag;
    }

    public void readNbt(NbtCompound tag) {
        this.size = tag.getInt("size");
        int[] palRaw = tag.getIntArray("palette");
        palette.clear();
        for (int raw : palRaw) {
            BlockState st = Block.STATE_IDS.get(raw);
            if (st == null) st = net.minecraft.block.Blocks.AIR.getDefaultState();
            palette.add(st);
        }
        int[] idx = tag.getIntArray("palIdx");
        int cap = Math.max(size, 8);
        xs = new float[cap]; ys = new float[cap]; zs = new float[cap];
        qx = new float[cap]; qy = new float[cap]; qz = new float[cap]; qw = new float[cap];
        nbts = new NbtCompound[cap]; paletteIdx = new int[cap];
        System.arraycopy(idx, 0, paletteIdx, 0, Math.min(idx.length, size));
        NbtList xl = tag.getList("xs", NbtElement.FLOAT_TYPE);
        NbtList yl = tag.getList("ys", NbtElement.FLOAT_TYPE);
        NbtList zl = tag.getList("zs", NbtElement.FLOAT_TYPE);
        NbtList qxl = tag.contains("qxs") ? tag.getList("qxs", NbtElement.FLOAT_TYPE) : null;
        NbtList qyl = tag.contains("qys") ? tag.getList("qys", NbtElement.FLOAT_TYPE) : null;
        NbtList qzl = tag.contains("qzs") ? tag.getList("qzs", NbtElement.FLOAT_TYPE) : null;
        NbtList qwl = tag.contains("qws") ? tag.getList("qws", NbtElement.FLOAT_TYPE) : null;
        for (int i = 0; i < size; i++) {
            xs[i] = xl.getFloat(i); ys[i] = yl.getFloat(i); zs[i] = zl.getFloat(i);
            qx[i] = qxl != null ? qxl.getFloat(i) : 0f;
            qy[i] = qyl != null ? qyl.getFloat(i) : 0f;
            qz[i] = qzl != null ? qzl.getFloat(i) : 0f;
            qw[i] = qwl != null ? qwl.getFloat(i) : 1f;
        }
        if (tag.contains("nbts")) {
            NbtList nbtList = tag.getList("nbts", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < nbtList.size(); i++) {
                NbtCompound entry = nbtList.getCompound(i);
                int idx2 = entry.getInt("idx");
                if (idx2 >= 0 && idx2 < size) {
                    NbtCompound data = entry.getCompound("data");
                    nbts[idx2] = data.isEmpty() ? null : data;
                }
            }
        }
        rebuildIndex();
    }
}
