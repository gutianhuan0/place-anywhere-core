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

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public BlockState palette(int index) {
        return palette.get(index);
    }

    public float x(int i) { return xs[i]; }
    public float y(int i) { return ys[i]; }
    public float z(int i) { return zs[i]; }
    public float qx(int i) { return qx[i]; }
    public float qy(int i) { return qy[i]; }
    public float qz(int i) { return qz[i]; }
    public float qw(int i) { return qw[i]; }
    public NbtCompound nbt(int i) { return nbts[i]; }
    public int paletteIndex(int i) { return paletteIdx[i]; }

    public BlockState state(int i) {
        return palette.get(paletteIdx[i]);
    }


    private int addPalette(BlockState state) {
        int idx = palette.indexOf(state);
        if (idx < 0) {
            palette.add(state);
            idx = palette.size() - 1;
        }
        return idx;
    }


    public int add(float lx, float ly, float lz, BlockState state) {
        return add(lx, ly, lz, 0f, 0f, 0f, 1f, state);
    }


    public int add(float lx, float ly, float lz, float rqx, float rqy, float rqz, float rqw, BlockState state) {
        ensureCapacity(size + 1);
        xs[size] = lx;
        ys[size] = ly;
        zs[size] = lz;
        qx[size] = rqx;
        qy[size] = rqy;
        qz[size] = rqz;
        qw[size] = rqw;
        paletteIdx[size] = addPalette(state);
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
        int last = size - 1;
        if (index != last) {
            xs[index] = xs[last];
            ys[index] = ys[last];
            zs[index] = zs[last];
            qx[index] = qx[last];
            qy[index] = qy[last];
            qz[index] = qz[last];
            qw[index] = qw[last];
            nbts[index] = nbts[last];
            paletteIdx[index] = paletteIdx[last];
        }
        nbts[last] = null;
        size--;
        return true;
    }


    public void clear() {
        size = 0;
        palette.clear();
    }

    private void ensureCapacity(int minCap) {
        if (minCap <= xs.length) return;
        int newCap = xs.length;
        while (newCap < minCap) newCap = Math.max(newCap + 8, newCap + (newCap >> 1));
        xs = grow(xs, newCap);
        ys = grow(ys, newCap);
        zs = grow(zs, newCap);
        qx = grow(qx, newCap);
        qy = grow(qy, newCap);
        qz = grow(qz, newCap);
        qw = grow(qw, newCap);
        nbts = grow(nbts, newCap);
        paletteIdx = grow(paletteIdx, newCap);
    }

    private static float[] grow(float[] a, int cap) {
        float[] n = new float[cap];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }

    private static int[] grow(int[] a, int cap) {
        int[] n = new int[cap];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }

    private static NbtCompound[] grow(NbtCompound[] a, int cap) {
        NbtCompound[] n = new NbtCompound[cap];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
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



    public NbtCompound writeNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("size", size);


        int[] palRaw = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            palRaw[i] = Block.STATE_IDS.getRawId(palette.get(i));
        }
        tag.putIntArray("palette", palRaw);


        tag.putIntArray("palIdx", Arrays.copyOf(paletteIdx, size));

        NbtList xl = new NbtList();
        NbtList yl = new NbtList();
        NbtList zl = new NbtList();
        NbtList qxl = new NbtList();
        NbtList qyl = new NbtList();
        NbtList qzl = new NbtList();
        NbtList qwl = new NbtList();
        for (int i = 0; i < size; i++) {
            xl.add(NbtFloat.of(xs[i]));
            yl.add(NbtFloat.of(ys[i]));
            zl.add(NbtFloat.of(zs[i]));
            qxl.add(NbtFloat.of(qx[i]));
            qyl.add(NbtFloat.of(qy[i]));
            qzl.add(NbtFloat.of(qz[i]));
            qwl.add(NbtFloat.of(qw[i]));
        }
        tag.put("xs", xl);
        tag.put("ys", yl);
        tag.put("zs", zl);
        tag.put("qxs", qxl);
        tag.put("qys", qyl);
        tag.put("qzs", qzl);
        tag.put("qws", qwl);


        NbtList nbtList = new NbtList();
        for (int i = 0; i < size; i++) {
            if (nbts[i] == null) continue;
            NbtCompound entry = new NbtCompound();
            entry.putInt("idx", i);
            entry.put("data", nbts[i]);
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
        xs = new float[cap];
        ys = new float[cap];
        zs = new float[cap];
        qx = new float[cap];
        qy = new float[cap];
        qz = new float[cap];
        qw = new float[cap];
        nbts = new NbtCompound[cap];
        paletteIdx = new int[cap];
        System.arraycopy(idx, 0, paletteIdx, 0, Math.min(idx.length, size));

        NbtList xl = tag.getList("xs", NbtElement.FLOAT_TYPE);
        NbtList yl = tag.getList("ys", NbtElement.FLOAT_TYPE);
        NbtList zl = tag.getList("zs", NbtElement.FLOAT_TYPE);
        NbtList qxl = tag.contains("qxs") ? tag.getList("qxs", NbtElement.FLOAT_TYPE) : null;
        NbtList qyl = tag.contains("qys") ? tag.getList("qys", NbtElement.FLOAT_TYPE) : null;
        NbtList qzl = tag.contains("qzs") ? tag.getList("qzs", NbtElement.FLOAT_TYPE) : null;
        NbtList qwl = tag.contains("qws") ? tag.getList("qws", NbtElement.FLOAT_TYPE) : null;
        for (int i = 0; i < size; i++) {
            xs[i] = xl.getFloat(i);
            ys[i] = yl.getFloat(i);
            zs[i] = zl.getFloat(i);

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
    }
}
