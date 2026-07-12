package com.placeanywhere.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * FreeBlockLayer —— 一个 section（16×16×16）内的自由方块容器。
 *
 * 存储结构 = palette（BlockState 调色板）+ 数组索引（SoA：x/y/z/paletteIndex 并行数组）。
 * 这正是"重写区块存储格式（palette + 数组索引）"在本模组中的落地：
 *
 *   palette      : ObjectArrayList<BlockState>        — 状态去重表
 *   xs/ys/zs     : float[]  长度 = capacity              — section 局部小数坐标 [0,16)
 *   paletteIdx   : int[]    长度 = capacity              — 指向 palette 的下标
 *
 * 用 SoA（Struct of Arrays）而非对象数组，便于顺序遍历、NBT 批量读写与缓存友好。
 * 删除采用 swap-remove，O(1)。
 */
public final class FreeBlockLayer {
    /** 调色板：BlockState 去重列表。 */
    private final ObjectArrayList<BlockState> palette = new ObjectArrayList<>();
    /** 并行索引数组：第 i 个方块的 section 局部坐标。 */
    private float[] xs = new float[8];
    private float[] ys = new float[8];
    private float[] zs = new float[8];
    /** 第 i 个方块的旋转四元数（默认 identity = 0,0,0,1）。 */
    private float[] qx = new float[8];
    private float[] qy = new float[8];
    private float[] qz = new float[8];
    private float[] qw = new float[8];
    /** 第 i 个方块的 BlockEntity NBT 数据（可为 null = 无 BE）。用于箱子等容器持久化。 */
    private CompoundTag[] nbts = new CompoundTag[8];
    /** 第 i 个方块在 palette 中的下标。 */
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
    public CompoundTag nbt(int i) { return nbts[i]; }
    public int paletteIndex(int i) { return paletteIdx[i]; }

    public BlockState state(int i) {
        return palette.get(paletteIdx[i]);
    }

    /** 把一个 state 加入 palette（去重），返回下标。 */
    private int addPalette(BlockState state) {
        int idx = palette.indexOf(state);
        if (idx < 0) {
            palette.add(state);
            idx = palette.size() - 1;
        }
        return idx;
    }

    /** 新增一个自由方块，返回其内部下标。四元数默认 identity。 */
    public int add(float lx, float ly, float lz, BlockState state) {
        return add(lx, ly, lz, 0f, 0f, 0f, 1f, state);
    }

    /** 新增一个自由方块（带旋转四元数），返回其内部下标。 */
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

    /** 更新第 index 个方块的状态（用于 use 后回写按钮按下/拉杆切换等状态变化）。 */
    public void setState(int index, BlockState state) {
        if (index < 0 || index >= size) return;
        paletteIdx[index] = addPalette(state);
    }

    /** 设置第 index 个方块的 BE NBT 数据。 */
    public void setNbt(int index, CompoundTag nbt) {
        if (index < 0 || index >= size) return;
        nbts[index] = nbt == null ? null : nbt.copy();
    }

    /** swap-remove：用末尾元素覆盖被删位置，O(1)。返回是否删除成功。 */
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
        size--;
        return true;
    }

    /** 清空（保留 palette 以复用？这里一并清空，序列化时不会写出空层）。 */
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

    private static CompoundTag[] grow(CompoundTag[] a, int cap) {
        CompoundTag[] n = new CompoundTag[cap];
        System.arraycopy(a, 0, n, 0, a.length);
        return n;
    }

    /** 遍历所有方块，回调拿到世界绝对坐标（由 section 原点 + 局部坐标合成）与状态。 */
    public void forEach(int sectionOriginX, int sectionOriginY, int sectionOriginZ,
                        BiConsumer<DecimalBlockPos, BlockState> action) {
        for (int i = 0; i < size; i++) {
            double wx = sectionOriginX + xs[i];
            double wy = sectionOriginY + ys[i];
            double wz = sectionOriginZ + zs[i];
            action.accept(new DecimalBlockPos(wx, wy, wz), palette.get(paletteIdx[i]));
        }
    }

    // ---------- NBT 序列化 ----------

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("size", size);

        // palette 存为 BlockState 的 rawId 数组（Block.BLOCK_STATE_REGISTRY 稳定）
        int[] palRaw = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            palRaw[i] = Block.BLOCK_STATE_REGISTRY.getId(palette.get(i));
        }
        tag.putIntArray("palette", palRaw);

        // 仅写出有效长度（putIntArray 没有 4 参数版本，先 copyOf 截断）
        tag.putIntArray("palIdx", Arrays.copyOf(paletteIdx, size));

        ListTag xl = new ListTag();
        ListTag yl = new ListTag();
        ListTag zl = new ListTag();
        ListTag qxl = new ListTag();
        ListTag qyl = new ListTag();
        ListTag qzl = new ListTag();
        ListTag qwl = new ListTag();
        for (int i = 0; i < size; i++) {
            xl.add(FloatTag.valueOf(xs[i]));
            yl.add(FloatTag.valueOf(ys[i]));
            zl.add(FloatTag.valueOf(zs[i]));
            qxl.add(FloatTag.valueOf(qx[i]));
            qyl.add(FloatTag.valueOf(qy[i]));
            qzl.add(FloatTag.valueOf(qz[i]));
            qwl.add(FloatTag.valueOf(qw[i]));
        }
        tag.put("xs", xl);
        tag.put("ys", yl);
        tag.put("zs", zl);
        tag.put("qxs", qxl);
        tag.put("qys", qyl);
        tag.put("qzs", qzl);
        tag.put("qws", qwl);

        // NBT 数据（BE 容器等），只写非 null 的
        ListTag nbtList = new ListTag();
        for (int i = 0; i < size; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("idx", i);
            entry.put("data", nbts[i] != null ? nbts[i] : new CompoundTag());
            nbtList.add(entry);
        }
        tag.put("nbts", nbtList);

        return tag;
    }

    public void readNbt(CompoundTag tag) {
        this.size = tag.getInt("size");
        int[] palRaw = tag.getIntArray("palette");
        palette.clear();
        for (int raw : palRaw) {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(raw);
            if (st == null) st = Blocks.AIR.defaultBlockState();
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
        nbts = new CompoundTag[cap];
        paletteIdx = new int[cap];
        System.arraycopy(idx, 0, paletteIdx, 0, Math.min(idx.length, size));

        ListTag xl = tag.getList("xs", Tag.TAG_FLOAT);
        ListTag yl = tag.getList("ys", Tag.TAG_FLOAT);
        ListTag zl = tag.getList("zs", Tag.TAG_FLOAT);
        ListTag qxl = tag.contains("qxs") ? tag.getList("qxs", Tag.TAG_FLOAT) : null;
        ListTag qyl = tag.contains("qys") ? tag.getList("qys", Tag.TAG_FLOAT) : null;
        ListTag qzl = tag.contains("qzs") ? tag.getList("qzs", Tag.TAG_FLOAT) : null;
        ListTag qwl = tag.contains("qws") ? tag.getList("qws", Tag.TAG_FLOAT) : null;
        for (int i = 0; i < size; i++) {
            xs[i] = xl.getFloat(i);
            ys[i] = yl.getFloat(i);
            zs[i] = zl.getFloat(i);
            // 兼容旧存档：无四元数数据时默认 identity (0,0,0,1)
            qx[i] = qxl != null ? qxl.getFloat(i) : 0f;
            qy[i] = qyl != null ? qyl.getFloat(i) : 0f;
            qz[i] = qzl != null ? qzl.getFloat(i) : 0f;
            qw[i] = qwl != null ? qwl.getFloat(i) : 1f;
        }
        // 读取 NBT 数据
        if (tag.contains("nbts")) {
            ListTag nbtList = tag.getList("nbts", Tag.TAG_COMPOUND);
            for (int i = 0; i < nbtList.size(); i++) {
                CompoundTag entry = nbtList.getCompound(i);
                int idx2 = entry.getInt("idx");
                if (idx2 >= 0 && idx2 < size) {
                    CompoundTag data = entry.getCompound("data");
                    nbts[idx2] = data.isEmpty() ? null : data;
                }
            }
        }
    }
}
