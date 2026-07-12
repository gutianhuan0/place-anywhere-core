package com.placeanywhere.core;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.mixin.RedstoneWireBlockAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.enums.WireConnection;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Quaternion;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.LiteralText;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * FreeBlocks —— 全模组的统一访问门面。所有 Mixin 子系统都通过这里的静态方法读写自由方块。
 *
 * 设计：
 *   - 自由方块存放在对应 chunk 的 {@link ChunkFreeData} 中（通过 {@link FreeBlockChunkAccess} 取出）。
 *   - 一个自由方块占据一个 1×1×1 的立方体，其"最小角"= DecimalBlockPos（与原版 block grid 一致）。
 *   - 渲染：按 pos 平移 BakedModel；碰撞/射线：取 state 的 outline shape 后按 pos 偏移。
 */
public final class FreeBlocks {
    /** 自由方块在世界中占据的立方体边长。 */
    public static final double BLOCK_SIZE = 1.0;

    /**
     * 红石粉 power 重算标志。为 true 时 {@link #getEmittedRedstoneAround} 会跳过红石粉，
     * 模拟原版 {@code RedstoneWireBlock.wiresGivePower=false} 行为，避免 wire-to-wire 递归。
     */
    private static boolean computingWirePower = false;

    /**
     * 状态捕获递归保护。为 true 时 {@link #notifyNeighborToFreeBlocks} 直接返回，
     * 避免 {@link #neighborUpdateWithCapture} 内部 setBlockState 触发的 updateNeighbor 造成无限递归。
     */
    private static final ThreadLocal<Boolean> inStateCapture = ThreadLocal.withInitial(() -> false);

    private FreeBlocks() {}

    // ---------- 存取 ----------

    public static ChunkFreeData dataOf(Chunk chunk) {
        return ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
    }

    /** 放置一个自由方块（identity 旋转）。返回是否成功。 */
    public static boolean placeBlock(World world, double x, double y, double z, BlockState state) {
        return placeBlock(world, x, y, z, 0f, 0f, 0f, 1f, state);
    }

    /** 放置一个自由方块（带四元数旋转）。返回是否成功。 */
    public static boolean placeBlock(World world, double x, double y, double z,
                                     float rqx, float rqy, float rqz, float rqw, BlockState state) {
        if (state == null || state.isAir()) return false;
        // 归一化四元数：非单位四元数会导致渲染时缩放（方块变大/变小）
        float qlen = (float) Math.sqrt(rqx * rqx + rqy * rqy + rqz * rqz + rqw * rqw);
        if (qlen > 1e-6f) {
            rqx /= qlen; rqy /= qlen; rqz /= qlen; rqw /= qlen;
        } else {
            rqx = 0f; rqy = 0f; rqz = 0f; rqw = 1f; // 退化情况退回 identity
        }
        WorldChunk chunk = getChunk(world, Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
        if (chunk == null) return false;
        int sy = Math.floorDiv((int) Math.floor(y), 16);
        ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
        FreeBlockLayer layer = data.getOrCreate(sy);
        if (layer == null) return false;
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        float lx = (float) (x - (double) cx * 16.0);
        float ly = (float) (y - (double) sy * 16.0);
        float lz = (float) (z - (double) cz * 16.0);
        layer.add(lx, ly, lz, rqx, rqy, rqz, rqw, state);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        PlaceAnywhereMod.LOGGER.info("[PA-Store] placeBlock @ {},{},{} = {} (chunk {},{} layer size={})",
                x, y, z, state, chunk.getPos().x, chunk.getPos().z, layer.size());
        DecimalBlockPos pos = new DecimalBlockPos(x, y, z);
        // 触发邻居更新与红石刷新（子系统 6/7 接入点）
        onFreeBlockChanged(world, pos, state);
        // 红石粉放置后需计算自身连接方向 + 初始 power
        if (state.isOf(Blocks.REDSTONE_WIRE)) {
            recomputeWirePower(world, pos);
        }
        // 同步到客户端（让自由方块在玩家视野中可见）—— recomputeWirePower 可能已更新 state，取最新 data
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        return true;
    }

    /** 移除距离 (x,y,z) 最近（在 epsilon 内）的自由方块。返回被移除的状态或 null。 */
    public static BlockState removeBlockAt(World world, double x, double y, double z, double epsilon) {
        WorldChunk chunk = getChunk(world, Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
        if (chunk == null) return null;
        int sy = Math.floorDiv((int) Math.floor(y), 16);
        ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
        FreeBlockLayer layer = data.get(sy);
        if (layer == null || layer.isEmpty()) return null;
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        float lx = (float) (x - (double) cx * 16.0);
        float ly = (float) (y - (double) sy * 16.0);
        float lz = (float) (z - (double) cz * 16.0);
        int best = -1;
        double bestD = epsilon * epsilon;
        for (int i = 0; i < layer.size(); i++) {
            double dx = layer.x(i) - lx, dy = layer.y(i) - ly, dz = layer.z(i) - lz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = i; }
        }
        if (best < 0) return null;
        BlockState removed = layer.state(best);
        layer.remove(best);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        onFreeBlockChanged(world, new DecimalBlockPos(x, y, z), Blocks.AIR.getDefaultState());
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        return removed;
    }

    /** 查询 (x,y,z) 处（epsilon 内）的方块。 */
    public static PlacedFreeBlock getBlockAt(World world, double x, double y, double z, double epsilon) {
        WorldChunk chunk = getChunk(world, Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
        if (chunk == null) return null;
        int sy = Math.floorDiv((int) Math.floor(y), 16);
        ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
        FreeBlockLayer layer = data.get(sy);
        if (layer == null || layer.isEmpty()) return null;
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        float lx = (float) (x - (double) cx * 16.0);
        float ly = (float) (y - (double) sy * 16.0);
        float lz = (float) (z - (double) cz * 16.0);
        int best = -1;
        double bestD = epsilon * epsilon;
        for (int i = 0; i < layer.size(); i++) {
            double dx = layer.x(i) - lx, dy = layer.y(i) - ly, dz = layer.z(i) - lz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = i; }
        }
        if (best < 0) return null;
        return new PlacedFreeBlock(
                new DecimalBlockPos(cx * 16.0 + layer.x(best), sy * 16.0 + layer.y(best), cz * 16.0 + layer.z(best)),
                layer.state(best),
                layer.qx(best), layer.qy(best), layer.qz(best), layer.qw(best),
                layer.nbt(best));
    }

    /** 设置 (x,y,z) 处（0.5 内）自由方块的 BE NBT 数据（用于容器类方块持久化）。 */
    public static void setBlockNbt(World world, double x, double y, double z, NbtCompound nbt) {
        WorldChunk chunk = getChunk(world, Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
        if (chunk == null) return;
        int sy = Math.floorDiv((int) Math.floor(y), 16);
        ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
        FreeBlockLayer layer = data.get(sy);
        if (layer == null || layer.isEmpty()) return;
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        float lx = (float) (x - (double) cx * 16.0);
        float ly = (float) (y - (double) sy * 16.0);
        float lz = (float) (z - (double) cz * 16.0);
        int best = -1;
        double bestD = 0.25;
        for (int i = 0; i < layer.size(); i++) {
            double dx = layer.x(i) - lx, dy = layer.y(i) - ly, dz = layer.z(i) - lz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = i; }
        }
        if (best < 0) return;
        layer.setNbt(best, nbt);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
    }

    /** 更新 (x,y,z) 处（epsilon 内）自由方块的状态（用于 onUse 后回写按钮按下/拉杆切换等）。
     *  返回更新前的状态，null 表示未找到。 */
    public static BlockState updateBlockState(World world, double x, double y, double z, BlockState newState) {
        WorldChunk chunk = getChunk(world, Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
        if (chunk == null) return null;
        int sy = Math.floorDiv((int) Math.floor(y), 16);
        ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
        FreeBlockLayer layer = data.get(sy);
        if (layer == null || layer.isEmpty()) return null;
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        float lx = (float) (x - (double) cx * 16.0);
        float ly = (float) (y - (double) sy * 16.0);
        float lz = (float) (z - (double) cz * 16.0);
        int best = -1;
        double bestD = 0.25; // 0.5^2
        for (int i = 0; i < layer.size(); i++) {
            double dx = layer.x(i) - lx, dy = layer.y(i) - ly, dz = layer.z(i) - lz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = i; }
        }
        if (best < 0) return null;
        BlockState old = layer.state(best);
        layer.setState(best, newState);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        // 同步到客户端
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        return old;
    }

    /** 收集 AABB 范围内的所有自由方块（用于碰撞/渲染剔除/红石）。 */
    public static List<PlacedFreeBlock> getInBox(World world, Box box) {
        List<PlacedFreeBlock> out = new ArrayList<>();
        forEachPlaced(world, box, out::add);
        return out;
    }

    public static void forEachInBox(World world, Box box, BiConsumer<DecimalBlockPos, BlockState> action) {
        forEachPlaced(world, box, fb -> action.accept(fb.pos(), fb.state()));
    }

    /** 遍历范围内的自由方块，回调拿到完整的 PlacedFreeBlock（含四元数）。供渲染使用。 */
    public static void forEachPlaced(World world, Box box, java.util.function.Consumer<PlacedFreeBlock> action) {
        int minCX = Math.floorDiv(MathHelper.floor(box.minX), 16);
        int maxCX = Math.floorDiv(MathHelper.floor(box.maxX), 16);
        int minCZ = Math.floorDiv(MathHelper.floor(box.minZ), 16);
        int maxCZ = Math.floorDiv(MathHelper.floor(box.maxZ), 16);
        int minSY = Math.floorDiv(MathHelper.floor(box.minY), 16);
        int maxSY = Math.floorDiv(MathHelper.floor(box.maxY), 16);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                WorldChunk chunk = getChunk(world, cx, cz);
                if (chunk == null) continue;
                ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
                for (int sy = minSY; sy <= maxSY; sy++) {
                    FreeBlockLayer layer = data.get(sy);
                    if (layer == null || layer.isEmpty()) continue;
                    int ox = cx << 4, oy = sy << 4, oz = cz << 4;
                    for (int i = 0; i < layer.size(); i++) {
                        double wx = ox + layer.x(i);
                        double wy = oy + layer.y(i);
                        double wz = oz + layer.z(i);
                        if (wx + BLOCK_SIZE <= box.minX || wx >= box.maxX) continue;
                        if (wy + BLOCK_SIZE <= box.minY || wy >= box.maxY) continue;
                        if (wz + BLOCK_SIZE <= box.minZ || wz >= box.maxZ) continue;
                        action.accept(new PlacedFreeBlock(
                                new DecimalBlockPos(wx, wy, wz), layer.state(i),
                                layer.qx(i), layer.qy(i), layer.qz(i), layer.qw(i),
                                layer.nbt(i)));
                    }
                }
            }
        }
    }

    // ---------- 碰撞（子系统 4） ----------
    // 参考 Valkyrien Skies / Create: Aeronautics 的碰撞思路：
    //   1. 预移动裁剪（clipMovement）在 move() HEAD 用 @ModifyVariable 拦截
    //   2. 逐轴二分法部分裁剪——不是全有或全无，而是找到最大可移动距离
    //   3. move() RETURN 检查地面接触——原版不会设置 onGround 因为 OBB 不在原版碰撞中
    //   4. 残穿推回安全网——处理生成在内部等边缘情况

    /** ThreadLocal 标志：clipMovement 中 Y 轴向下被裁剪时设置，供 RETURN 注入读取。 */
    private static final ThreadLocal<Boolean> yClippedDown = ThreadLocal.withInitial(() -> false);

    /** 收集非旋转自由方块的 VoxelShape 碰撞形状。
     *  非旋转方块交给原版碰撞系统处理（精确、稳定、支持站立）。
     *  旋转方块由 clipMovement 预裁剪处理。 */
    public static List<VoxelShape> collectCollisionShapes(World world, Box queryBox) {
        List<VoxelShape> shapes = new ArrayList<>();
        forEachPlaced(world, queryBox, fb -> {
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (hasRotation) return; // 旋转方块由 clipMovement 处理
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;
            shapes.add(VoxelShapes.cuboid(localBox.offset(fb.pos().x(), fb.pos().y(), fb.pos().z())));
        });
        return shapes;
    }

    /** 预移动裁剪：逐轴二分法找到最大可移动距离，防止穿入旋转 OBB。
     *  处理顺序 Y → X → Z（与原版一致，Y 优先支持站立）。
     *  水平检查时底部上移 0.05 格，让站在 OBB 顶部的实体能水平滑动。
     *  Y 轴向下被裁剪时设置 ThreadLocal 标志，供 RETURN 注入设置 onGround。 */
    public static Vec3d clipMovement(net.minecraft.entity.Entity entity, Vec3d movement) {
        yClippedDown.set(false); // 重置标志
        if (movement.lengthSquared() < 1e-10) return movement;
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return movement;

        Box entityBox = entity.getBoundingBox();
        double mx = movement.x, my = movement.y, mz = movement.z;

        // Y 轴：二分法找最大可移动距离
        if (my != 0) {
            double allowed = binaryClipAxis(world, entityBox, 0, my, 0);
            if (my < 0 && allowed < my - 1e-6) {
                // 向下移动被裁剪 → 标记地面接触
                yClippedDown.set(true);
            }
            my = allowed;
        }
        // X 轴：水平检查时底部上移 0.05，避免脚部碰到 OBB 顶部导致卡住
        if (mx != 0) {
            Box groundedBox = shrinkY(entityBox.offset(0, my, 0), 0.05);
            mx = binaryClipAxis(world, groundedBox, mx, 0, 0);
        }
        // Z 轴：同上
        if (mz != 0) {
            Box groundedBox = shrinkY(entityBox.offset(mx, my, 0), 0.05);
            mz = binaryClipAxis(world, groundedBox, 0, 0, mz);
        }

        return new Vec3d(mx, my, mz);
    }

    /** 二分法裁剪：找到沿单轴最大可移动距离，使移动后 AABB 不与任何 OBB 相交。
     *  初始检测全距离是否可行，如可行直接返回；否则二分搜索。
     *  精度 1/1024（约 0.001 格），迭代最多 10 次。 */
    private static double binaryClipAxis(World world, Box entityBox, double dx, double dy, double dz) {
        // 先检测完整移动是否可行
        Box testBox = entityBox.offset(dx, dy, dz);
        if (!intersectsAnyRotatedOBB(world, testBox)) {
            return dx != 0 ? dx : (dy != 0 ? dy : dz);
        }
        // 二分搜索
        double lo = 0, hi = dx != 0 ? dx : (dy != 0 ? dy : dz);
        double sign = Math.signum(hi);
        double absHi = Math.abs(hi);
        for (int i = 0; i < 10; i++) {
            double mid = (lo + absHi) * 0.5;
            Box midBox = entityBox.offset(dx != 0 ? sign * mid : 0, dy != 0 ? sign * mid : 0, dz != 0 ? sign * mid : 0);
            if (!intersectsAnyRotatedOBB(world, midBox)) {
                lo = mid;
            } else {
                absHi = mid;
            }
        }
        return sign * lo;
    }

    /** 将 Box 的 Y 范围向上收缩 amount（底部上移），用于水平碰撞检测时避免脚部卡住。 */
    private static Box shrinkY(Box box, double amount) {
        return new Box(box.minX, box.minY + amount, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** 检查给定 AABB 是否与任何旋转自由方块的 OBB 相交（SAT 6 轴检测）。 */
    public static boolean intersectsAnyRotatedOBB(World world, Box aabb) {
        Box searchBox = aabb.expand(0.1);
        boolean[] hit = { false };
        forEachPlaced(world, searchBox, fb -> {
            if (hit[0]) return;
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (!hasRotation) return;
            if (aabbIntersectsOBB(world, aabb, fb)) hit[0] = true;
        });
        return hit[0];
    }

    /** SAT OBB vs AABB 相交检测。 */
    private static boolean aabbIntersectsOBB(World world, Box aabb, PlacedFreeBlock fb) {
        VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
        if (base.isEmpty()) base = VoxelShapes.fullCube();
        Box localBox = base.getBoundingBox();
        if (localBox == null) return false;

        Quaternion q = new Quaternion(fb.qx(), fb.qy(), fb.qz(), fb.qw());
        q.normalize();

        GJKEPA.Vec3 obbX = new GJKEPA.Vec3(1, 0, 0);
        GJKEPA.Vec3 obbY = new GJKEPA.Vec3(0, 1, 0);
        GJKEPA.Vec3 obbZ = new GJKEPA.Vec3(0, 0, 1);
        transformQuat(q, obbX);
        transformQuat(q, obbY);
        transformQuat(q, obbZ);

        double px = fb.pos().x(), py = fb.pos().y(), pz = fb.pos().z();
        float lcX = (float) ((localBox.minX + localBox.maxX) * 0.5);
        float lcY = (float) ((localBox.minY + localBox.maxY) * 0.5);
        float lcZ = (float) ((localBox.minZ + localBox.maxZ) * 0.5);
        GJKEPA.Vec3 obbCenter = new GJKEPA.Vec3(lcX, lcY, lcZ);
        transformQuat(q, obbCenter);
        obbCenter.add((float) px, (float) py, (float) pz);

        float obbHalfX = (float) ((localBox.maxX - localBox.minX) * 0.5);
        float obbHalfY = (float) ((localBox.maxY - localBox.minY) * 0.5);
        float obbHalfZ = (float) ((localBox.maxZ - localBox.minZ) * 0.5);

        float aabbCx = (float) ((aabb.minX + aabb.maxX) * 0.5);
        float aabbCy = (float) ((aabb.minY + aabb.maxY) * 0.5);
        float aabbCz = (float) ((aabb.minZ + aabb.maxZ) * 0.5);
        float aabbHalfX = (float) ((aabb.maxX - aabb.minX) * 0.5);
        float aabbHalfY = (float) ((aabb.maxY - aabb.minY) * 0.5);
        float aabbHalfZ = (float) ((aabb.maxZ - aabb.minZ) * 0.5);

        GJKEPA.Vec3[] axes = {
            new GJKEPA.Vec3(1, 0, 0), new GJKEPA.Vec3(0, 1, 0), new GJKEPA.Vec3(0, 0, 1),
            obbX, obbY, obbZ
        };

        for (GJKEPA.Vec3 axis : axes) {
            float len = axis.length();
            if (len < 1e-6f) continue;
            float ax = axis.x / len, ay = axis.y / len, az = axis.z / len;

            float aabbRadius = aabbHalfX * Math.abs(ax) + aabbHalfY * Math.abs(ay) + aabbHalfZ * Math.abs(az);
            float obbRadius = obbHalfX * Math.abs(obbX.x * ax + obbX.y * ay + obbX.z * az)
                            + obbHalfY * Math.abs(obbY.x * ax + obbY.y * ay + obbY.z * az)
                            + obbHalfZ * Math.abs(obbZ.x * ax + obbZ.y * ay + obbZ.z * az);
            float aabbProj = aabbCx * ax + aabbCy * ay + aabbCz * az;
            float obbProj = obbCenter.x * ax + obbCenter.y * ay + obbCenter.z * az;

            float diff = Math.abs(aabbProj - obbProj);
            if (aabbRadius + obbRadius <= diff) return false; // 此轴分离
        }
        return true; // 所有轴都重叠 → 相交
    }

    /** move() RETURN 后处理：
     *  1. 如果 clipMovement 标记了 Y 轴向下裁剪，设置 onGround（原版不会设置因为 OBB 不在原版碰撞中）
     *  2. 残穿安全网：如果实体已经穿入 OBB，用 SAT 推回 */
    public static void resolveRotatedCollisions(net.minecraft.entity.Entity entity) {
        // 1. 地面接触检测
        if (yClippedDown.get()) {
            entity.setOnGround(true);
            entity.fallDistance = 0f;
            yClippedDown.set(false);
        }

        // 2. 残穿安全网推回
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return;
        Box[] currentBox = { entity.getBoundingBox() };
        Box searchBox = currentBox[0].expand(0.1);

        forEachPlaced(world, searchBox, fb -> {
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (!hasRotation) return;

            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;

            Quaternion q = new Quaternion(fb.qx(), fb.qy(), fb.qz(), fb.qw());
            q.normalize();

            GJKEPA.Vec3 obbX = new GJKEPA.Vec3(1, 0, 0);
            GJKEPA.Vec3 obbY = new GJKEPA.Vec3(0, 1, 0);
            GJKEPA.Vec3 obbZ = new GJKEPA.Vec3(0, 0, 1);
            transformQuat(q, obbX);
            transformQuat(q, obbY);
            transformQuat(q, obbZ);

            double px = fb.pos().x(), py = fb.pos().y(), pz = fb.pos().z();
            float lcX = (float) ((localBox.minX + localBox.maxX) * 0.5);
            float lcY = (float) ((localBox.minY + localBox.maxY) * 0.5);
            float lcZ = (float) ((localBox.minZ + localBox.maxZ) * 0.5);
            GJKEPA.Vec3 obbCenter = new GJKEPA.Vec3(lcX, lcY, lcZ);
            transformQuat(q, obbCenter);
            obbCenter.add((float) px, (float) py, (float) pz);

            float obbHalfX = (float) ((localBox.maxX - localBox.minX) * 0.5);
            float obbHalfY = (float) ((localBox.maxY - localBox.minY) * 0.5);
            float obbHalfZ = (float) ((localBox.maxZ - localBox.minZ) * 0.5);

            Box box = currentBox[0];
            float aabbCx = (float) ((box.minX + box.maxX) * 0.5);
            float aabbCy = (float) ((box.minY + box.maxY) * 0.5);
            float aabbCz = (float) ((box.minZ + box.maxZ) * 0.5);
            float aabbHalfX = (float) ((box.maxX - box.minX) * 0.5);
            float aabbHalfY = (float) ((box.maxY - box.minY) * 0.5);
            float aabbHalfZ = (float) ((box.maxZ - box.minZ) * 0.5);

            GJKEPA.Vec3[] axes = {
                new GJKEPA.Vec3(1, 0, 0), new GJKEPA.Vec3(0, 1, 0), new GJKEPA.Vec3(0, 0, 1),
                obbX, obbY, obbZ
            };

            float minOverlap = Float.MAX_VALUE;
            GJKEPA.Vec3 minAxis = null;

            for (GJKEPA.Vec3 axis : axes) {
                float len = axis.length();
                if (len < 1e-6f) continue;
                float ax = axis.x / len, ay = axis.y / len, az = axis.z / len;

                float aabbRadius = aabbHalfX * Math.abs(ax) + aabbHalfY * Math.abs(ay) + aabbHalfZ * Math.abs(az);
                float obbRadius = obbHalfX * Math.abs(obbX.x * ax + obbX.y * ay + obbX.z * az)
                                + obbHalfY * Math.abs(obbY.x * ax + obbY.y * ay + obbY.z * az)
                                + obbHalfZ * Math.abs(obbZ.x * ax + obbZ.y * ay + obbZ.z * az);
                float aabbProj = aabbCx * ax + aabbCy * ay + aabbCz * az;
                float obbProj = obbCenter.x * ax + obbCenter.y * ay + obbCenter.z * az;

                float diff = Math.abs(aabbProj - obbProj);
                float overlap = aabbRadius + obbRadius - diff;
                if (overlap <= 0f) return; // 分离
                if (overlap < minOverlap) {
                    minOverlap = overlap;
                    minAxis = new GJKEPA.Vec3(ax, ay, az);
                }
            }

            if (minAxis == null) return;
            // 只处理小穿透（< 0.5），大穿透可能是实体故意在里面，不推回
            if (minOverlap > 0.5f) return;

            float dx = aabbCx - obbCenter.x;
            float dy = aabbCy - obbCenter.y;
            float dz = aabbCz - obbCenter.z;
            float dot = dx * minAxis.x + dy * minAxis.y + dz * minAxis.z;
            float sign = dot >= 0f ? 1f : -1f;

            float pushX = sign * minOverlap * minAxis.x;
            float pushY = sign * minOverlap * minAxis.y;
            float pushZ = sign * minOverlap * minAxis.z;
            entity.setPos(entity.getX() + pushX, entity.getY() + pushY, entity.getZ() + pushZ);
            currentBox[0] = entity.getBoundingBox();

            if (pushY > 0.01f && Math.abs(pushY) > Math.max(Math.abs(pushX), Math.abs(pushZ))) {
                entity.setOnGround(true);
                entity.fallDistance = 0f;
            }
        });
    }

    // ---------- 射线检测（子系统 5） ----------

    /** 自由方块命中结果。 */
    public record FreeBlockHit(DecimalBlockPos pos, BlockState state, Vec3d point, Direction side, double distanceSq,
                               float qx, float qy, float qz, float qw) {
        /** 向后兼容：identity 四元数。 */
        public FreeBlockHit(DecimalBlockPos pos, BlockState state, Vec3d point, Direction side, double distanceSq) {
            this(pos, state, point, side, distanceSq, 0f, 0f, 0f, 1f);
        }
    }

    /** 对范围内自由方块做射线求交，返回最近的命中。
     *  方块旋转后用 OBB 的包围 AABB 做射线检测（近似）。 */
    public static Optional<FreeBlockHit> raycast(World world, Vec3d start, Vec3d end) {
        Box queryBox = new Box(start, end).expand(1.0);
        FreeBlockHit[] best = { null };
        int[] count = { 0 };
        forEachPlaced(world, queryBox, fb -> {
            count[0]++;
            // 取 state 的 outline shape，旋转 8 角生成包围 AABB
            VoxelShape shape = fb.state().getOutlineShape(world, fb.pos().toBlockPos());
            Box blockBox;
            if (shape.isEmpty()) {
                blockBox = VoxelShapes.fullCube().getBoundingBox();
            } else {
                blockBox = shape.getBoundingBox();
            }
            if (blockBox == null) return;
            Box worldBox = rotateBoxAABB(blockBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
            double[] t = rayAABB(start, end, worldBox);
            if (t == null) return;
            double distSq = start.squaredDistanceTo(
                    start.x + (end.x - start.x) * t[0],
                    start.y + (end.y - start.y) * t[0],
                    start.z + (end.z - start.z) * t[0]);
            if (best[0] == null || distSq < best[0].distanceSq()) {
                Vec3d hit = new Vec3d(
                        start.x + (end.x - start.x) * t[0],
                        start.y + (end.y - start.y) * t[0],
                        start.z + (end.z - start.z) * t[0]);
                Direction side = Direction.byId((int) t[1]);
                best[0] = new FreeBlockHit(fb.pos(), fb.state(), hit, side, distSq,
                        fb.qx(), fb.qy(), fb.qz(), fb.qw());
            }
        });
        if (best[0] != null) {
            com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                    "[PA-Ray] 命中 @ {},{} ({})", best[0].pos().x(), best[0].pos().y(), best[0].pos().z(), count[0]);
        }
        return Optional.ofNullable(best[0]);
    }

    /** ray-AABB 相交，返回 [t, hitAxis] 或 null。t∈[0,1] 为命中参数。 */
    private static double[] rayAABB(Vec3d start, Vec3d end, Box box) {
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        double tmin = 0.0, tmax = 1.0;
        int hitAxis = 0;
        // X
        if (Math.abs(dx) < 1e-8) {
            if (start.x < box.minX || start.x > box.maxX) return null;
        } else {
            double t1 = (box.minX - start.x) / dx;
            double t2 = (box.maxX - start.x) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tmin) { tmin = t1; hitAxis = 0; }
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return null;
        }
        // Y
        if (Math.abs(dy) < 1e-8) {
            if (start.y < box.minY || start.y > box.maxY) return null;
        } else {
            double t1 = (box.minY - start.y) / dy;
            double t2 = (box.maxY - start.y) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tmin) { tmin = t1; hitAxis = 1; }
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return null;
        }
        // Z
        if (Math.abs(dz) < 1e-8) {
            if (start.z < box.minZ || start.z > box.maxZ) return null;
        } else {
            double t1 = (box.minZ - start.z) / dz;
            double t2 = (box.maxZ - start.z) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tmin) { tmin = t1; hitAxis = 2; }
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return null;
        }
        double t = tmin;
        // 命中面方向：沿 hitAxis 方向，dir<0 表示射线朝正方向走、命中 min 面（负向）
        double dir;
        switch (hitAxis) {
            case 0 -> dir = dx;
            case 1 -> dir = dy;
            default -> dir = dz;
        }
        Direction side = switch (hitAxis) {
            case 0 -> dir < 0 ? Direction.EAST : Direction.WEST;
            case 1 -> dir < 0 ? Direction.UP : Direction.DOWN;
            default -> dir < 0 ? Direction.SOUTH : Direction.NORTH;
        };
        return new double[]{ t, side.getId() };
    }

    // ---------- 四元数旋转辅助 ----------

    /** 用四元数旋转向量（原地修改）。替代 JOML 的 Quaternionf.transform(Vector3f)。 */
    static void transformQuat(Quaternion q, GJKEPA.Vec3 v) {
        float qx = q.getX(), qy = q.getY(), qz = q.getZ(), qw = q.getW();
        float tx = 2 * (qy * v.z - qz * v.y);
        float ty = 2 * (qz * v.x - qx * v.z);
        float tz = 2 * (qx * v.y - qy * v.x);
        v.x += qw * tx + (qy * tz - qz * ty);
        v.y += qw * ty + (qz * tx - qx * tz);
        v.z += qw * tz + (qx * ty - qy * tx);
    }

    /** 把局部 Box（最小角原点）的 8 个角用四元数旋转，再平移到世界坐标 pos，返回包围 AABB。 */
    public static Box rotateBoxAABB(Box local, DecimalBlockPos pos, float qx, float qy, float qz, float qw) {
        Quaternion q = new Quaternion(qx, qy, qz, qw);
        q.normalize();
        double minX = local.minX, minY = local.minY, minZ = local.minZ;
        double maxX = local.maxX, maxY = local.maxY, maxZ = local.maxZ;
        double[] xs = {minX, maxX, minX, maxX, minX, maxX, minX, maxX};
        double[] ys = {minY, minY, maxY, maxY, minY, minY, maxY, maxY};
        double[] zs = {minZ, minZ, minZ, minZ, maxZ, maxZ, maxZ, maxZ};
        double bMinX = Double.POSITIVE_INFINITY, bMinY = Double.POSITIVE_INFINITY, bMinZ = Double.POSITIVE_INFINITY;
        double bMaxX = Double.NEGATIVE_INFINITY, bMaxY = Double.NEGATIVE_INFINITY, bMaxZ = Double.NEGATIVE_INFINITY;
        GJKEPA.Vec3 v = new GJKEPA.Vec3();
        for (int i = 0; i < 8; i++) {
            v.set((float) xs[i], (float) ys[i], (float) zs[i]);
            transformQuat(q, v);
            double wx = v.x + pos.x(), wy = v.y + pos.y(), wz = v.z + pos.z();
            if (wx < bMinX) bMinX = wx; if (wx > bMaxX) bMaxX = wx;
            if (wy < bMinY) bMinY = wy; if (wy > bMaxY) bMaxY = wy;
            if (wz < bMinZ) bMinZ = wz; if (wz > bMaxZ) bMaxZ = wz;
        }
        return new Box(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
    }

    /** 计算旋转后 8 个角的世界坐标（用于描边线框）。
     *  角顺序：(minX,minY,minZ),(maxX,minY,minZ),(minX,maxY,minZ),(maxX,maxY,minZ),
     *         (minX,minY,maxZ),(maxX,minY,maxZ),(minX,maxY,maxZ),(maxX,maxY,maxZ) */
    public static double[][] rotatedCorners(Box local, DecimalBlockPos pos, float qx, float qy, float qz, float qw) {
        Quaternion q = new Quaternion(qx, qy, qz, qw);
        q.normalize();
        double[] xs = {local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX};
        double[] ys = {local.minY, local.minY, local.maxY, local.maxY, local.minY, local.minY, local.maxY, local.maxY};
        double[] zs = {local.minZ, local.minZ, local.minZ, local.minZ, local.maxZ, local.maxZ, local.maxZ, local.maxZ};
        double[][] out = new double[8][3];
        GJKEPA.Vec3 v = new GJKEPA.Vec3();
        for (int i = 0; i < 8; i++) {
            v.set((float) xs[i], (float) ys[i], (float) zs[i]);
            transformQuat(q, v);
            out[i][0] = v.x + pos.x();
            out[i][1] = v.y + pos.y();
            out[i][2] = v.z + pos.z();
        }
        return out;
    }

    // ---------- 邻居更新（子系统 6） ----------

    /** 自由方块变化时触发：通知周围整数网格位置 + 周围自由方块。 */
    public static void onFreeBlockChanged(World world, DecimalBlockPos pos, BlockState newState) {
        // 1) 通知整数网格上的 6 个邻居方块（原版 Block.NeighborNotifier）
        BlockPos sourceBp = pos.toBlockPos();
        for (Direction d : Direction.values()) {
            BlockPos np = sourceBp.offset(d);
            world.updateNeighbor(np, newState.getBlock(), sourceBp);
        }
        // 2) 通知附近的自由方块（使其状态可响应）：遍历 ±1.5 格内的自由方块
        Box around = new Box(pos.x()-1.5, pos.y()-1.5, pos.z()-1.5, pos.x()+2.5, pos.y()+2.5, pos.z()+2.5);
        forEachInBox(world, around, (fp, fs) -> {
            if (fp.equals(pos)) return;
            try {
                if (fs.isOf(Blocks.REDSTONE_WIRE)) {
                    // 红石粉：直接重算 power（跳过 canPlaceAt/dropStacks，因为自由方块不在整数网格）
                    recomputeWirePower(world, fp);
                } else {
                    // 其它方块：用暂存-捕获-回写模式执行 neighborUpdate
                    neighborUpdateWithCapture(world, fp, fs, newState.getBlock(), sourceBp);
                }
            } catch (Throwable ignored) {
                // 单个自由方块的更新失败不影响整体
            }
        });
    }

    /**
     * 子系统6 接入点：当原版整数网格方块通知邻居时（见 WorldMixin），顺带让附近自由方块
     * 的 BlockState 执行其 neighborUpdate，使红石元件、活塞等能响应整数网格邻居的变化。
     */
    public static void notifyNeighborToFreeBlocks(World world, BlockPos vanillaPos,
                                                  Block sourceBlock, BlockPos sourcePos) {
        // 防止 neighborUpdateWithCapture 内部的 setBlockState 触发递归
        if (inStateCapture.get()) return;
        Box around = new Box(vanillaPos.getX() - 1.5, vanillaPos.getY() - 1.5, vanillaPos.getZ() - 1.5,
                vanillaPos.getX() + 2.5, vanillaPos.getY() + 2.5, vanillaPos.getZ() + 2.5);
        forEachInBox(world, around, (fp, fs) -> {
            try {
                if (fs.isOf(Blocks.REDSTONE_WIRE)) {
                    recomputeWirePower(world, fp);
                } else {
                    neighborUpdateWithCapture(world, fp, fs, sourceBlock, sourcePos);
                }
            } catch (Throwable ignored) {
                // 单个自由方块的更新失败不应影响整体流程
            }
        });
    }

    /**
     * 执行自由方块的 neighborUpdate，捕获状态变化并回写到 FreeBlockLayer。
     *
     * 自由方块的 neighborUpdate（如铜灯、活塞）内部会调用 {@code world.setBlockState(pos, newState)}
     * 来改变自身状态，但 pos 是整数化的坐标，setBlockState 会写到整数网格而非 FreeBlockLayer。
     * 此方法采用"暂存-执行-捕获-回写-恢复"模式：
     *   1. 暂存整数网格原状态
     *   2. 将自由方块临时放到整数网格（FORCE_STATE|SKIP_DROPS，不通知邻居）
     *   3. 执行 neighborUpdate（内部 setBlockState 会更新临时方块）
     *   4. 捕获变化后的状态，回写到 FreeBlockLayer
     *   5. 恢复整数网格原状
     *
     * 期间设置 {@link #inStateCapture} 标志，阻止 setBlockState(NOTIFY_ALL) 触发的
     * updateNeighbor → notifyNeighborToFreeBlocks 造成递归。
     */
    private static void neighborUpdateWithCapture(World world, DecimalBlockPos fp, BlockState fs,
                                                   Block sourceBlock, BlockPos sourcePos) {
        BlockPos bp = fp.toBlockPos();
        BlockState vanillaBefore = world.getBlockState(bp);
        // 临时放置到整数网格（不通知邻居，不掉落）
        world.setBlockState(bp, fs, Block.FORCE_STATE | Block.SKIP_DROPS);
        inStateCapture.set(true);
        try {
            // 调试：检查 power 值
            boolean receiving = world.isReceivingRedstonePower(bp);
            int receivedPower = world.getReceivedRedstonePower(bp);
            PlaceAnywhereMod.LOGGER.info("[PA-Neighbor] {} @ {},{} calling neighborUpdate (isReceivingPower={}, receivedPower={})",
                    fs.getBlock(), fp.x(), fp.y(), receiving, receivedPower);
            fs.neighborUpdate(world, bp, sourceBlock, sourcePos, false);
            BlockState after = world.getBlockState(bp);
            // 活塞伸出依赖 addSyncedBlockEvent（异步 tick 处理），但 capture 模式下事件绑定到临时整数网格
            // 位置，finally 恢复原状后事件失效。故对活塞手动切换 EXTENDED：仅视觉伸出，不推动方块。
            if (after == fs && fs.getBlock() instanceof PistonBlock && fs.contains(PistonBlock.EXTENDED)) {
                boolean extended = fs.get(PistonBlock.EXTENDED);
                if (receiving != extended) {
                    after = fs.with(PistonBlock.EXTENDED, receiving);
                    world.setBlockState(bp, after, Block.FORCE_STATE | Block.SKIP_DROPS);
                }
            }
            PlaceAnywhereMod.LOGGER.info("[PA-Neighbor] {} @ {},{} after neighborUpdate: {} -> {} (same={})",
                    fs.getBlock(), fp.x(), fp.y(), fs, after, after == fs);
            if (after != fs) {
                // 状态变化了，回写到 FreeBlockLayer
                updateBlockState(world, fp.x(), fp.y(), fp.z(), after);
                PlaceAnywhereMod.LOGGER.info("[PA-Neighbor] {} @ {},{},{} state changed: {} -> {}",
                        fs.getBlock(), fp.x(), fp.y(), fp.z(), fs, after);
            }
        } catch (Throwable ignored) {
        } finally {
            inStateCapture.set(false);
            // 恢复整数网格原状
            world.setBlockState(bp, vanillaBefore, Block.FORCE_STATE | Block.SKIP_DROPS);
        }
    }

    // ---------- 红石（子系统 7） ----------

    /** 返回某点 6 个面相邻位置上自由方块提供的弱红石信号强度（最大值）。
     *  <p>端口模型：只查询与接收方块共享面的自由方块（类似漏斗端口），不做球体邻近查询。
     *  当 {@code computingWirePower} 为 true 时跳过红石粉（模拟原版 wiresGivePower=false）。 */
    public static int getEmittedRedstoneAround(World world, Vec3d point, double radius) {
        final int[] max = { 0 };
        for (Direction d : Direction.values()) {
            Vec3d adj = point.add(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            Box adjBox = new Box(adj.x - 0.5, adj.y - 0.5, adj.z - 0.5,
                                 adj.x + 0.5, adj.y + 0.5, adj.z + 0.5);
            forEachInBox(world, adjBox, (pos, state) -> {
                if (computingWirePower && state.isOf(Blocks.REDSTONE_WIRE)) return;
                int p = state.getWeakRedstonePower(world, pos.toBlockPos(), d);
                if (p > max[0]) max[0] = p;
            });
        }
        return max[0];
    }

    /** 返回某点 6 个面相邻位置上自由方块提供的强红石信号强度（最大值）。 */
    public static int getStrongRedstoneAround(World world, Vec3d point, double radius) {
        final int[] max = { 0 };
        for (Direction d : Direction.values()) {
            Vec3d adj = point.add(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            Box adjBox = new Box(adj.x - 0.5, adj.y - 0.5, adj.z - 0.5,
                                 adj.x + 0.5, adj.y + 0.5, adj.z + 0.5);
            forEachInBox(world, adjBox, (pos, state) -> {
                if (computingWirePower && state.isOf(Blocks.REDSTONE_WIRE)) return;
                int p = state.getStrongRedstonePower(world, pos.toBlockPos(), d);
                if (p > max[0]) max[0] = p;
            });
        }
        return max[0];
    }

    /** 返回某邻居位置上自由方块在指定方向上发射的红石信号强度（最大值）。
     *  <p>供 {@code getEmittedRedstonePower} 注入使用。只查询恰在该整数网格位置上的自由方块
     *  （0.5 半径 = 1×1×1 盒），不做球体邻近查询——避免"隔一格仍通电"的问题。 */
    public static int getEmittedRedstoneFromDirection(World world, Vec3d point, Direction direction, double radius) {
        Box box = new Box(point.x - 0.5, point.y - 0.5, point.z - 0.5,
                          point.x + 0.5, point.y + 0.5, point.z + 0.5);
        final int[] max = { 0 };
        forEachInBox(world, box, (pos, state) -> {
            if (computingWirePower && state.isOf(Blocks.REDSTONE_WIRE)) return;
            int p = state.getWeakRedstonePower(world, pos.toBlockPos(), direction);
            if (p > max[0]) max[0] = p;
        });
        return max[0];
    }

    /** 判断查询点是否被任意自由方块"强充能"——框架实现：附近有红石源即视为被充能。 */
    public static boolean isPoweredByFreeBlocks(World world, Vec3d point, double radius) {
        return getEmittedRedstoneAround(world, point, radius) > 0;
    }

    /**
     * 判断某方块状态是否可被红石粉连接（对应原版 {@code RedstoneWireBlock.connectsTo}）。
     * 因 Mixin @Invoker 无法解析重载方法名冲突，此处自行实现等效逻辑。
     */
    private static boolean wireConnectsTo(BlockState state, Direction dir) {
        if (state.isAir()) return false;
        if (state.isOf(Blocks.REDSTONE_WIRE)) return true;
        // 有朝向的红石元件：需检查 facing
        if (state.isOf(Blocks.REPEATER)) {
            return dir != null && state.get(Properties.HORIZONTAL_FACING) == dir;
        }
        if (state.isOf(Blocks.COMPARATOR)) {
            return dir != null && state.get(Properties.HORIZONTAL_FACING) != dir.getOpposite();
        }
        if (state.isOf(Blocks.OBSERVER)) {
            return dir != null && state.get(Properties.FACING) == dir.getOpposite();
        }
        // 无朝向的红石元件：始终连接
        if (state.isOf(Blocks.LEVER) || state.isOf(Blocks.REDSTONE_LAMP) ||
                state.isOf(Blocks.REDSTONE_TORCH) || state.isOf(Blocks.REDSTONE_WALL_TORCH) ||
                state.isOf(Blocks.DAYLIGHT_DETECTOR) || state.isOf(Blocks.TARGET) ||
                state.isOf(Blocks.LECTERN)) {
            return true;
        }
        // 默认：不透明方块（石头、泥土等固体方块）可连接（红石粉可沿其爬坡）
        return state.isOpaque();
    }

    /**
     * 重算自由红石粉的连接方向属性（WIRE_CONNECTION_NORTH/EAST/SOUTH/WEST）。
     *
     * 原版通过 {@code getDefaultWireState} 查整数网格邻居，但自由方块不在网格上，
     * 导致连接方向全是 NONE，视觉上无连线。此方法扫描附近自由方块 + 整数网格方块，
     * 为每个水平方向设置正确的 WireConnection。
     *
     * @return 更新了连接属性的 BlockState（如果无变化则返回原 state）
     */
    private static BlockState recomputeWireConnections(World world, DecimalBlockPos wirePos, BlockState state) {
        Vec3d center = new Vec3d(wirePos.x() + 0.5, wirePos.y() + 0.5, wirePos.z() + 0.5);
        // 搜索范围：水平 ±1.5，垂直 ±1.5
        Box searchBox = new Box(center.x - 1.5, center.y - 1.5, center.z - 1.5,
                               center.x + 1.5, center.y + 1.5, center.z + 1.5);

        // 每个水平方向的最近连接距离和是否UP。索引：N=0, E=1, S=2, W=3
        double[] bestDist = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
        boolean[] isUp = { false, false, false, false };

        // 1) 扫描自由方块
        forEachInBox(world, searchBox, (fp, fs) -> {
            if (fp.equals(wirePos)) return;
            if (!wireConnectsTo(fs, null)) return;
            double dx = (fp.x() + 0.5) - center.x;
            double dy = (fp.y() + 0.5) - center.y;
            double dz = (fp.z() + 0.5) - center.z;
            double adx = Math.abs(dx), adz = Math.abs(dz);
            double hDist = Math.max(adx, adz);
            if (hDist > 1.5 || hDist < 0.1) return;
            if (Math.abs(dy) > 1.5) return;
            // 判断主要方向（正交性：次要分量须 < 0.5 半格）
            int dirIdx;
            double major;
            if (adx > adz) {
                dirIdx = dx > 0 ? 1 : 3; // E=1, W=3
                major = adx;
                if (adz > 0.5) return;
            } else {
                dirIdx = dz > 0 ? 2 : 0; // S=2, N=0
                major = adz;
                if (adx > 0.5) return;
            }
            if (major < bestDist[dirIdx]) {
                bestDist[dirIdx] = major;
                isUp[dirIdx] = dy > 0.5;
            }
        });

        // 2) 扫描整数网格方块（原版会查，但自由红石粉不经过原版流程，需自己查）
        BlockPos wireBp = wirePos.toBlockPos();
        Direction[] horizontals = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        for (int i = 0; i < 4; i++) {
            Direction d = horizontals[i];
            BlockPos np = wireBp.offset(d);
            BlockState ns = world.getBlockState(np);
            if (wireConnectsTo(ns, d)) {
                if (1.0 < bestDist[i]) {
                    bestDist[i] = 1.0;
                    // UP 连接：固体方块上方有可连接方块时，红石粉沿方块爬坡
                    if (ns.isSolidBlock(world, np)) {
                        BlockState upState = world.getBlockState(np.up());
                        if (wireConnectsTo(upState, null)) {
                            isUp[i] = true;
                        }
                    }
                }
            }
        }

        // 3) 构建新状态
        WireConnection[] conns = new WireConnection[4];
        for (int i = 0; i < 4; i++) {
            conns[i] = bestDist[i] == Double.MAX_VALUE
                    ? WireConnection.NONE
                    : (isUp[i] ? WireConnection.UP : WireConnection.SIDE);
        }

        return state
                .with(RedstoneWireBlock.WIRE_CONNECTION_NORTH, conns[0])
                .with(RedstoneWireBlock.WIRE_CONNECTION_EAST, conns[1])
                .with(RedstoneWireBlock.WIRE_CONNECTION_SOUTH, conns[2])
                .with(RedstoneWireBlock.WIRE_CONNECTION_WEST, conns[3]);
    }

    /**
     * 重算自由红石粉的 power 并在变化时回写 + 传播。
     *
     * 原版 {@code RedstoneWireBlock.update} 通过 {@code world.setBlockState} 回写 power，
     * 但自由方块不在整数网格，{@code world.getBlockState(pos) != state} 导致回写被跳过。
     * 此方法接管 power 计算，直接写入 FreeBlockLayer 并触发邻居传播。
     * 同时重算 WIRE_CONNECTION 连接方向属性（视觉连线）。
     *
     * @param wirePos 自由红石粉的世界坐标
     */
    public static void recomputeWirePower(World world, DecimalBlockPos wirePos) {
        // 取最新状态（forEachInBox 传入的 state 可能是迭代开始时的快照）
        PlacedFreeBlock current = getBlockAt(world, wirePos.x(), wirePos.y(), wirePos.z(), 0.5);
        if (current == null || !current.state().isOf(Blocks.REDSTONE_WIRE)) return;
        BlockState state = current.state();
        int oldPower = state.get(RedstoneWireBlock.POWER);

        // 先重算连接方向（视觉连线）
        BlockState connState = recomputeWireConnections(world, wirePos, state);
        boolean connChanged = !connState.equals(state);
        state = connState;

        // 1) 非红石粉信号源功率：临时关闭 wire 发射，查询 world.getReceivedRedstonePower
        //    （RedstoneViewMixin 会叠加自由方块信号，computingWirePower 让其跳过红石粉）
        RedstoneWireBlockAccessor acc = (RedstoneWireBlockAccessor) Blocks.REDSTONE_WIRE;
        boolean prevFlag = acc.placeanywhere$getWiresGivePower();
        acc.placeanywhere$setWiresGivePower(false);
        computingWirePower = true;
        int nonWirePower;
        try {
            nonWirePower = world.getReceivedRedstonePower(wirePos.toBlockPos());
        } finally {
            acc.placeanywhere$setWiresGivePower(prevFlag);
            computingWirePower = false;
        }

        // 2) wire-to-wire：扫描附近自由红石粉，读取其 POWER 属性（模拟原版 increasePower）
        int maxWirePower = 0;
        Vec3d center = new Vec3d(wirePos.x() + 0.5, wirePos.y() + 0.5, wirePos.z() + 0.5);
        Box wBox = new Box(center.x - 1.5, center.y - 1.5, center.z - 1.5,
                center.x + 1.5, center.y + 1.5, center.z + 1.5);
        final int[] mw = { 0 };
        forEachInBox(world, wBox, (fp, fs) -> {
            if (fp.equals(wirePos)) return;
            if (fs.isOf(Blocks.REDSTONE_WIRE)) {
                int p = fs.get(RedstoneWireBlock.POWER);
                if (p > mw[0]) mw[0] = p;
            }
        });
        maxWirePower = mw[0];

        int newPower = Math.max(nonWirePower, maxWirePower > 0 ? maxWirePower - 1 : 0);
        if (newPower > 15) newPower = 15;

        if (newPower != oldPower || connChanged) {
            BlockState updated = state.with(RedstoneWireBlock.POWER, newPower);
            updateBlockState(world, wirePos.x(), wirePos.y(), wirePos.z(), updated);
            if (newPower != oldPower) {
                PlaceAnywhereMod.LOGGER.info("[PA-Redstone] wire @ {},{},{} power {} -> {}",
                        wirePos.x(), wirePos.y(), wirePos.z(), oldPower, newPower);
                // 只有 power 变化才递归传播（避免纯连接更新引发无限递归）
                onFreeBlockChanged(world, wirePos, updated);
            } else if (connChanged) {
                PlaceAnywhereMod.LOGGER.info("[PA-Redstone] wire @ {},{},{} connections updated",
                        wirePos.x(), wirePos.y(), wirePos.z());
            }
        }
    }

    // ---------- 内部工具 ----------

    private static WorldChunk getChunk(World world, int cx, int cz) {
        Chunk c = world.getChunk(cx, cz);
        if (c instanceof WorldChunk wc) return wc;
        return null;
    }

    // ---------- 测试命令 ----------

    public static void registerCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        // /placefree x y z <block[state=val]> [qx qy qz qw]
        // 使用原版 BlockStateArgumentType，支持 minecraft:piston[facing=east] 等原生语法
        dispatcher.register(literal("placefree")
                .then(argument("x", DoubleArgumentType.doubleArg())
                        .then(argument("y", DoubleArgumentType.doubleArg())
                                .then(argument("z", DoubleArgumentType.doubleArg())
                                        .then(argument("block", BlockStateArgumentType.blockState())
                                                .executes(ctx -> execPlace(ctx, false))
                                                .then(argument("qx", FloatArgumentType.floatArg())
                                                        .then(argument("qy", FloatArgumentType.floatArg())
                                                                .then(argument("qz", FloatArgumentType.floatArg())
                                                                        .then(argument("qw", FloatArgumentType.floatArg())
                                                                                .executes(ctx -> execPlace(ctx, true)))))))))));
        dispatcher.register(literal("removefree")
                .then(argument("x", DoubleArgumentType.doubleArg())
                        .then(argument("y", DoubleArgumentType.doubleArg())
                                .then(argument("z", DoubleArgumentType.doubleArg())
                                        .executes(FreeBlocks::execRemove)))));
        dispatcher.register(literal("listfree")
                .executes(FreeBlocks::execListNear));
    }

    /** 查询出生点附近 32 格内的自由方块，反馈给命令发送者。 */
    private static int execListNear(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        Vec3d pos = src.getPosition();
        Box box = new Box(pos.x - 32, pos.y - 32, pos.z - 32, pos.x + 32, pos.y + 32, pos.z + 32);
        List<PlacedFreeBlock> blocks = getInBox(world, box);
        if (blocks.isEmpty()) {
            src.sendFeedback(new net.minecraft.text.LiteralText("附近无自由方块"), false);
        } else {
            src.sendFeedback(new net.minecraft.text.LiteralText("找到 " + blocks.size() + " 个自由方块:"), false);
            for (PlacedFreeBlock fb : blocks) {
                src.sendFeedback(new net.minecraft.text.LiteralText(
                        "  @ " + fb.pos().x() + "," + fb.pos().y() + "," + fb.pos().z()
                        + " = " + fb.state()), false);
            }
        }
        return blocks.size();
    }

    /** /placefree 执行：从 BlockStateArgument 取状态，可选四元数。 */
    private static int execPlace(CommandContext<ServerCommandSource> ctx, boolean hasQuat) throws CommandSyntaxException {
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        BlockState state = ctx.getArgument("block", BlockStateArgument.class).getBlockState();
        float qx = 0f, qy = 0f, qz = 0f, qw = 1f;
        if (hasQuat) {
            qx = FloatArgumentType.getFloat(ctx, "qx");
            qy = FloatArgumentType.getFloat(ctx, "qy");
            qz = FloatArgumentType.getFloat(ctx, "qz");
            qw = FloatArgumentType.getFloat(ctx, "qw");
        }
        boolean ok = placeBlock(ctx.getSource().getWorld(), x, y, z, qx, qy, qz, qw, state);
        final String displayName = state.toString() + (hasQuat ? " q=(" + qx + "," + qy + "," + qz + "," + qw + ")" : "");
        ctx.getSource().sendFeedback(new net.minecraft.text.LiteralText(
                ok ? "已放置自由方块 " + displayName + " @ " + x + "," + y + "," + z : "放置失败（区块未加载？）"), false);
        return ok ? 1 : 0;
    }

    private static int execRemove(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        BlockState removed = removeBlockAt(ctx.getSource().getWorld(), x, y, z, 0.5);
        ctx.getSource().sendFeedback(new net.minecraft.text.LiteralText(
                removed == null ? "未找到自由方块" : "已移除: " + removed), false);
        return removed == null ? 0 : 1;
    }

    // ---------- 调试命令 ----------
    // /padebug info    — 显示执行者位置、AABB、onGround 状态
    // /padebug obb     — 列出附近旋转方块的 OBB 信息（中心、半长、包围 AABB）
    // /padebug test <x> <y> <z> — 在指定位置放一个测试 AABB，检测是否与 OBB 相交
    // /padebug movetest <dx> <dy> <dz> — 模拟移动并返回裁剪后的 movement
    // /padebug ground  — 检测执行者下方 0.1 格内是否有 OBB（地面检测）

    public static void registerDebugCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("padebug")
                .then(literal("info").executes(FreeBlocks::execDebugInfo))
                .then(literal("obb").executes(FreeBlocks::execDebugOBB))
                .then(literal("ground").executes(FreeBlocks::execDebugGround))
                .then(literal("test")
                        .then(argument("x", DoubleArgumentType.doubleArg())
                                .then(argument("y", DoubleArgumentType.doubleArg())
                                        .then(argument("z", DoubleArgumentType.doubleArg())
                                                .then(argument("sx", DoubleArgumentType.doubleArg())
                                                        .then(argument("sy", DoubleArgumentType.doubleArg())
                                                                .then(argument("sz", DoubleArgumentType.doubleArg())
                                                                        .executes(FreeBlocks::execDebugTest))))))))
                .then(literal("movetest")
                        .then(argument("dx", DoubleArgumentType.doubleArg())
                                .then(argument("dy", DoubleArgumentType.doubleArg())
                                        .then(argument("dz", DoubleArgumentType.doubleArg())
                                                .executes(FreeBlocks::execDebugMoveTest))))));
    }

    /** /padebug info — 显示执行者位置、AABB、onGround 状态 */
    private static int execDebugInfo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(new LiteralText("§c此命令需要由实体执行"), false);
            return 0;
        }
        net.minecraft.entity.Entity ent = src.getEntity();
        Box aabb = ent.getBoundingBox();
        boolean onGround = ent.isOnGround();
        src.sendFeedback(new LiteralText(String.format(
                "§a[PA-Debug] 实体信息:\n"
                + "  位置: (%.4f, %.4f, %.4f)\n"
                + "  AABB: [%.4f,%.4f,%.4f] → [%.4f,%.4f,%.4f]\n"
                + "  AABB尺寸: %.4f x %.4f x %.4f\n"
                + "  onGround: %s\n"
                + "  fallDistance: %.2f",
                ent.getX(), ent.getY(), ent.getZ(),
                aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ,
                aabb.maxX - aabb.minX, aabb.maxY - aabb.minY, aabb.maxZ - aabb.minZ,
                onGround, ent.fallDistance)), false);
        return 1;
    }

    /** /padebug obb — 列出附近旋转方块的 OBB 信息 */
    private static int execDebugOBB(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        Vec3d pos = src.getPosition();
        Box searchBox = new Box(pos.x - 16, pos.y - 16, pos.z - 16, pos.x + 16, pos.y + 16, pos.z + 16);
        List<PlacedFreeBlock> blocks = getInBox(world, searchBox);
        int rotated = 0;
        for (PlacedFreeBlock fb : blocks) {
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (!hasRotation) continue;
            rotated++;
            Box localBox = fb.state().getCollisionShape(world, fb.pos().toBlockPos()).getBoundingBox();
            if (localBox == null) localBox = new Box(0, 0, 0, 1, 1, 1);
            Box rotatedBBox = rotateBoxAABB(localBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
            // 检测与玩家 AABB 是否相交
            boolean intersect = false;
            if (src.getEntity() != null) {
                intersect = aabbIntersectsOBB(world, src.getEntity().getBoundingBox(), fb);
            }
            final Box lb = localBox;
            final Box rb = rotatedBBox;
            final boolean inter = intersect;
            src.sendFeedback(new LiteralText(String.format(
                    "§e[OBB] %s @ (%.3f,%.3f,%.3f) q=(%.3f,%.3f,%.3f,%.3f)\n"
                    + "  localBox: [%.3f,%.3f,%.3f]→[%.3f,%.3f,%.3f]\n"
                    + "  包围AABB: [%.3f,%.3f,%.3f]→[%.3f,%.3f,%.3f]\n"
                    + "  与实体相交: %s",
                    fb.state(), fb.pos().x(), fb.pos().y(), fb.pos().z(),
                    fb.qx(), fb.qy(), fb.qz(), fb.qw(),
                    lb.minX, lb.minY, lb.minZ, lb.maxX, lb.maxY, lb.maxZ,
                    rb.minX, rb.minY, rb.minZ, rb.maxX, rb.maxY, rb.maxZ,
                    inter ? "§c是" : "§a否")), false);
        }
        if (rotated == 0) {
            src.sendFeedback(new LiteralText("§a附近无旋转自由方块"), false);
        }
        return rotated;
    }

    /** /padebug test <x> <y> <z> <sx> <sy> <sz> — 在指定位置放测试 AABB，检测是否与 OBB 相交 */
    private static int execDebugTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        double sx = DoubleArgumentType.getDouble(ctx, "sx");
        double sy = DoubleArgumentType.getDouble(ctx, "sy");
        double sz = DoubleArgumentType.getDouble(ctx, "sz");
        Box testBox = new Box(x, y, z, x + sx, y + sy, z + sz);
        boolean hit = intersectsAnyRotatedOBB(src.getWorld(), testBox);
        src.sendFeedback(new LiteralText(String.format(
                "§a[PA-Debug] 测试AABB [%.3f,%.3f,%.3f]→[%.3f,%.3f,%.3f]\n  与OBB相交: %s",
                testBox.minX, testBox.minY, testBox.minZ, testBox.maxX, testBox.maxY, testBox.maxZ,
                hit ? "§c是" : "§a否")), false);
        return hit ? 1 : 0;
    }

    /** /padebug movetest <dx> <dy> <dz> — 模拟移动并返回裁剪后的 movement */
    private static int execDebugMoveTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(new LiteralText("§c此命令需要由实体执行"), false);
            return 0;
        }
        double dx = DoubleArgumentType.getDouble(ctx, "dx");
        double dy = DoubleArgumentType.getDouble(ctx, "dy");
        double dz = DoubleArgumentType.getDouble(ctx, "dz");
        net.minecraft.entity.Entity ent = src.getEntity();
        Vec3d original = new Vec3d(dx, dy, dz);
        Vec3d clipped = clipMovement(ent, original);
        src.sendFeedback(new LiteralText(String.format(
                "§a[PA-Debug] 移动测试:\n"
                + "  原始movement: (%.4f, %.4f, %.4f)\n"
                + "  裁剪后movement: (%.4f, %.4f, %.4f)\n"
                + "  X轴裁剪: %s  Y轴裁剪: %s  Z轴裁剪: %s",
                original.x, original.y, original.z,
                clipped.x, clipped.y, clipped.z,
                Math.abs(original.x - clipped.x) > 1e-6 ? "§c是" : "§a否",
                Math.abs(original.y - clipped.y) > 1e-6 ? "§c是" : "§a否",
                Math.abs(original.z - clipped.z) > 1e-6 ? "§c是" : "§a否")), false);
        return 1;
    }

    /** /padebug ground — 检测执行者下方 0.1 格内是否有 OBB（地面检测） */
    private static int execDebugGround(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(new LiteralText("§c此命令需要由实体执行"), false);
            return 0;
        }
        net.minecraft.entity.Entity ent = src.getEntity();
        Box aabb = ent.getBoundingBox();
        // 向下扩展 0.1 格检测
        Box groundBox = new Box(aabb.minX, aabb.minY - 0.1, aabb.minZ, aabb.maxX, aabb.minY, aabb.maxZ);
        boolean hasGround = intersectsAnyRotatedOBB(src.getWorld(), groundBox);
        src.sendFeedback(new LiteralText(String.format(
                "§a[PA-Debug] 地面检测:\n"
                + "  实体AABB底部Y: %.4f\n"
                + "  检测框: [%.4f,%.4f,%.4f]→[%.4f,%.4f,%.4f]\n"
                + "  下方有OBB地面: %s\n"
                + "  onGround: %s",
                aabb.minY,
                groundBox.minX, groundBox.minY, groundBox.minZ, groundBox.maxX, groundBox.maxY, groundBox.maxZ,
                hasGround ? "§a是" : "§c否",
                ent.isOnGround())), false);
        return hasGround ? 1 : 0;
    }
}
