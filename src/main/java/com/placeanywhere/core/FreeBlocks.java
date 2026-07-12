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
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
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

    /** 渲染/碰撞剔除时考虑旋转的余量。旋转后方块对角线 sqrt(3)≈1.73，
     *  方块角落可能超出 1×1×1 范围 (sqrt(3)/2 - 0.5 ≈ 0.37)，向上取 0.5 避免遗漏。 */
    private static final double CULL_MARGIN = 0.5;

    /** 水平碰撞检测时底部上移量。让站在 OBB 上的实体能水平滑动，
     *  避免脚部与 OBB 边缘误判相交导致平地卡。0.2 格足以覆盖边缘抖动。 */
    private static final double STEP_MARGIN = 0.2;

    /** Y 轴向下裁剪时额外保留的间隙。让实体停在离 OBB 顶部稍高的位置，
     *  确保实体底部与 OBB 顶部接触（避免 SAT 精度容差导致悬空间隙）。 */
    private static final double GROUND_PADDING = 0.05;

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
                        // 旋转后方块角落可能超出 [wx, wx+1] 范围，加 CULL_MARGIN 避免剔除遗漏
                        if (wx + BLOCK_SIZE + CULL_MARGIN <= box.minX || wx - CULL_MARGIN >= box.maxX) continue;
                        if (wy + BLOCK_SIZE + CULL_MARGIN <= box.minY || wy - CULL_MARGIN >= box.maxY) continue;
                        if (wz + BLOCK_SIZE + CULL_MARGIN <= box.minZ || wz - CULL_MARGIN >= box.maxZ) continue;
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

    /** ThreadLocal 标志：模拟器运行时设为 true，开启详细诊断日志。不影响正常游戏。 */
    private static final ThreadLocal<Boolean> simDebug = ThreadLocal.withInitial(() -> false);

    /** 收集非旋转自由方块的 VoxelShape 碰撞形状，注入原版碰撞系统。
     *  <p>非旋转方块交给原版碰撞系统处理（精确、稳定、支持站立、步进、摩擦力）。
     *  <p>旋转方块由 {@link #clipMovement} 在 adjustMovementForCollisions RETURN 处
     *  用 OBB SAT 精确裁剪处理。
     *  <p>注意：必须用 VoxelShape.offset() 而非 VoxelShapes.cuboid(Box)，
     *  因为后者内部使用 bit-packed VoxelSet，坐标超出 [0,16] 范围（如 Y=64）会返回 empty()。 */
    public static List<VoxelShape> collectCollisionShapes(World world, Box queryBox) {
        List<VoxelShape> shapes = new ArrayList<>();
        forEachPlaced(world, queryBox, fb -> {
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (hasRotation) return; // 旋转方块由 clipMovement 处理
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            // VoxelShape.offset() 返回 OffsetVoxelShape，不受坐标范围限制
            shapes.add(base.offset(fb.pos().x(), fb.pos().y(), fb.pos().z()));
        });
        return shapes;
    }

    /** 预移动裁剪：逐轴二分法找到最大可移动距离，防止穿入旋转 OBB。
     *  处理顺序 Y → X → Z（与原版一致，Y 优先支持站立）。
     *  <p>Y 轴向下裁剪时加 GROUND_PADDING，让实体停在离 OBB 顶部稍高的位置，
     *  确保实体底部与 OBB 顶部接触（避免 SAT 精度容差导致悬空间隙）。
     *  <p>Y 轴向上裁剪时，如果实体当前已陷进 OBB，允许向上移动（脱陷），
     *  避免跳跃被卡住。
     *  <p>水平检测时底部上移 STEP_MARGIN，避免脚部与 OBB 边缘误判相交导致平地卡。 */
    public static Vec3d clipMovement(net.minecraft.entity.Entity entity, Vec3d movement) {
        return clipMovementBox(entity.getWorld(), entity.getBoundingBox(), movement);
    }

    /** 原版 step-up 高度。玩家可以自动走上不超过此高度的台阶/斜坡。 */
    private static final double STEP_UP_HEIGHT = 0.6;

    /** clipMovement 的纯逻辑版本，不依赖 Entity（供 debug 命令和自动化测试使用）。
     *  逻辑与 {@link #clipMovement} 完全一致。
     *  <p>斜坡模式：当水平移动被旋转 OBB 阻挡时，允许完整水平移动并寻找最小抬升高度
     *  使实体不与任何 OBB 相交。若抬升在 STEP_UP_HEIGHT 内则接受（斜坡/台阶），
     *  否则保持裁剪（墙壁）。 */
    public static Vec3d clipMovementBox(net.minecraft.world.World world, Box entityBox, Vec3d movement) {
        yClippedDown.set(false); // 重置标志
        if (movement.lengthSquared() < 1e-10) return movement;
        if (world == null) return movement;

        double mx = movement.x, my = movement.y, mz = movement.z;
        boolean dbg = simDebug.get();

        // Y 轴：二分法找最大可移动距离（用完整 entityBox，检测全身）
        if (my != 0) {
            boolean stuckInOBB = intersectsAnyRotatedOBB(world, entityBox);
            double allowed = binaryClipAxis(world, entityBox, 0, my, 0);
            if (my < 0) {
                if (allowed > my + 1e-6) {
                    yClippedDown.set(true);
                    double beforePad = allowed;
                    allowed = Math.min(allowed + GROUND_PADDING, 0);
                    if (dbg) PlaceAnywhereMod.LOGGER.info(String.format(
                            "[PA-Sim-DIAG] Y下落裁剪: my=%.5f allowed=%.5f padded=%.5f box.minY=%.4f stuckInOBB=%s",
                            my, beforePad, allowed, entityBox.minY, stuckInOBB));
                }
            } else if (my > 0 && stuckInOBB) {
                allowed = my; // 脱陷：允许向上移动
            }
            my = allowed;
        }

        // X 轴：水平检测时底部上移 STEP_MARGIN，避免脚部与 OBB 边缘误判
        double origMx = mx;
        if (mx != 0) {
            Box groundedBox = shrinkY(entityBox.offset(0, my, 0), STEP_MARGIN);
            mx = binaryClipAxis(world, groundedBox, mx, 0, 0);
        }
        // 斜坡模式：X 被裁剪 OR 完整 box 穿入 OBB 时，尝试允许完整移动 + 找最小抬升
        if (origMx != 0) {
            boolean xClipped = Math.abs(mx) < Math.abs(origMx) - 1e-6;
            Box fullMovedBox = entityBox.offset(mx, my, 0);
            boolean fullPenetrates = intersectsAnyRotatedOBB(world, fullMovedBox);
            if (xClipped || fullPenetrates) {
                Box slopeBox = entityBox.offset(origMx, my, 0);
                double lift = findSurfaceHeight(world, slopeBox);
                if (lift >= 0) {
                    if (dbg) PlaceAnywhereMod.LOGGER.info(String.format(
                            "[PA-Sim-DIAG] X斜坡模式: mx=%.5f→%.5f lift=%.4f my=%.5f→%.5f clipped=%s penetrates=%s",
                            origMx, origMx, lift, my, my + lift, xClipped, fullPenetrates));
                    mx = origMx;
                    my += lift;
                    yClippedDown.set(true);
                } else if (dbg) {
                    PlaceAnywhereMod.LOGGER.info(String.format(
                            "[PA-Sim-DIAG] X斜坡模式拒绝(墙壁): mx=%.5f→%.5f", origMx, mx));
                }
            }
        }

        // Z 轴：同上
        double origMz = mz;
        if (mz != 0) {
            Box groundedBox = shrinkY(entityBox.offset(mx, my, 0), STEP_MARGIN);
            mz = binaryClipAxis(world, groundedBox, 0, 0, mz);
        }
        if (origMz != 0) {
            boolean zClipped = Math.abs(mz) < Math.abs(origMz) - 1e-6;
            Box fullMovedBox = entityBox.offset(mx, my, mz);
            boolean fullPenetrates = intersectsAnyRotatedOBB(world, fullMovedBox);
            if (zClipped || fullPenetrates) {
                Box slopeBox = entityBox.offset(mx, my, origMz);
                double lift = findSurfaceHeight(world, slopeBox);
                if (lift >= 0) {
                    if (dbg) PlaceAnywhereMod.LOGGER.info(String.format(
                            "[PA-Sim-DIAG] Z斜坡模式: mz=%.5f→%.5f lift=%.4f my=%.5f→%.5f clipped=%s penetrates=%s",
                            origMz, origMz, lift, my, my + lift, zClipped, fullPenetrates));
                    mz = origMz;
                    my += lift;
                    yClippedDown.set(true);
                } else if (dbg) {
                    PlaceAnywhereMod.LOGGER.info(String.format(
                            "[PA-Sim-DIAG] Z斜坡模式拒绝(墙壁): mz=%.5f→%.5f", origMz, mz));
                }
            }
        }

        return new Vec3d(mx, my, mz);
    }

    /** 二分法寻找最小向上抬升量，使 box 抬升后不与任何旋转 OBB 相交。
     *  返回 -1 表示在 STEP_UP_HEIGHT 内找不到无穿入位置（墙壁）。
     *  返回 0 表示无需抬升（当前已不穿入）。
     *  返回 >0 表示需要抬升该量才能走上斜坡/台阶。 */
    private static double findSurfaceHeight(World world, Box box) {
        if (!intersectsAnyRotatedOBB(world, box)) return 0;
        double lo = 0, hi = STEP_UP_HEIGHT;
        for (int i = 0; i < 14; i++) {
            double mid = (lo + hi) * 0.5;
            Box testBox = box.offset(0, mid, 0);
            if (!intersectsAnyRotatedOBB(world, testBox)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        Box finalBox = box.offset(0, hi, 0);
        if (intersectsAnyRotatedOBB(world, finalBox)) {
            if (simDebug.get()) PlaceAnywhereMod.LOGGER.info(String.format(
                    "[PA-Sim-DIAG] findSurfaceHeight: 墙壁(lo=%.5f hi=%.5f box.minY=%.4f)",
                    lo, hi, box.minY));
            return -1; // 墙壁
        }
        return hi;
    }

    /** 将 Box 的 Y 范围向上收缩 amount（底部上移），用于水平碰撞检测时避免脚部卡住。 */
    private static Box shrinkY(Box box, double amount) {
        return new Box(box.minX, box.minY + amount, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** 二分法裁剪：找到沿单轴最大可移动距离，使移动后 AABB 不与任何 OBB 相交。
     *  初始检测全距离是否可行，如可行直接返回；否则二分搜索。
     *  精度 1/16384（约 0.00006 格），迭代最多 14 次。 */
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
        for (int i = 0; i < 14; i++) {
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

    /** 查找实体正下方接触（支撑实体）的自由方块状态。
     *  用于摩擦力/滑度查询：原版走整数网格 getBlockPos().down()，
     *  但自由方块不在原版网格中，所以需要这里手动查找。
     *  查询范围：实体 AABB 底部下方 0.1 格内的薄层。 */
    public static BlockState findSupportingFreeBlock(World world, Box entityBox) {
        // 在实体底部下方 0.1 格范围内查找接触的自由方块
        Box queryBox = new Box(
                entityBox.minX, entityBox.minY - 0.1, entityBox.minZ,
                entityBox.maxX, entityBox.minY + 0.05, entityBox.maxZ);
        BlockState[] result = { null };
        forEachPlaced(world, queryBox, fb -> {
            if (result[0] != null) return;
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (hasRotation) {
                // 旋转方块：用 OBB 相交检测
                if (aabbIntersectsOBB(world, queryBox, fb)) {
                    result[0] = fb.state();
                }
            } else {
                // 非旋转方块：直接判断包围盒相交
                VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
                if (base.isEmpty()) base = VoxelShapes.fullCube();
                Box localBox = base.getBoundingBox();
                if (localBox == null) return;
                Box worldBox = localBox.offset(fb.pos().x(), fb.pos().y(), fb.pos().z());
                if (worldBox.intersects(queryBox)) {
                    result[0] = fb.state();
                }
            }
        });
        return result[0];
    }

    /** 检查实体是否正站在旋转 OBB 上（底部下方 0.15 格内有 OBB 接触）。
     *  用于在水平移动时也保持 onGround=true，防止 travel() 用空中摩擦力导致滑行。 */
    private static boolean isStandingOnOBB(net.minecraft.entity.Entity entity) {
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return false;
        Box box = entity.getBoundingBox();
        // 检查实体底部下方 0.15 格内是否有 OBB
        Box footBox = new Box(
                box.minX, box.minY - 0.15, box.minZ,
                box.maxX, box.minY + 0.01, box.maxZ);
        return intersectsAnyRotatedOBB(world, footBox);
    }

    /** 检查给定 AABB 是否与任何旋转自由方块的 OBB 相交（SAT 15 轴检测）。 */
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

    /** SAT 检测结果：最小穿透深度 + 对应分离轴 + OBB 中心（世界坐标）。null 表示分离。 */
    private static final class SATResult {
        final float overlap;
        final float ax, ay, az;     // 单位分离轴
        final float cx, cy, cz;     // OBB 中心（世界坐标）
        SATResult(float overlap, float ax, float ay, float az, float cx, float cy, float cz) {
            this.overlap = overlap;
            this.ax = ax; this.ay = ay; this.az = az;
            this.cx = cx; this.cy = cy; this.cz = cz;
        }
    }

    /** OBB vs AABB 的完整 SAT 检测（15 条分离轴）。
     *  <p>3 条 AABB 面法线 + 3 条 OBB 面法线 + 9 条边叉积。
     *  旧代码只检查 6 条轴，缺少 9 条边叉积会导致旋转方块边缘附近假阳性碰撞。
     *  返回最小穿透轴和深度，null 表示不相交。 */
    private static SATResult satAabbObb(Box aabb, double px, double py, double pz,
                                         Box localBox, float qx, float qy, float qz, float qw) {
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw);
        q.normalize();

        org.joml.Vector3f obbX = new org.joml.Vector3f(1, 0, 0);
        org.joml.Vector3f obbY = new org.joml.Vector3f(0, 1, 0);
        org.joml.Vector3f obbZ = new org.joml.Vector3f(0, 0, 1);
        q.transform(obbX);
        q.transform(obbY);
        q.transform(obbZ);

        float lcX = (float) ((localBox.minX + localBox.maxX) * 0.5);
        float lcY = (float) ((localBox.minY + localBox.maxY) * 0.5);
        float lcZ = (float) ((localBox.minZ + localBox.maxZ) * 0.5);
        // OBB 中心 = 方块位置 + 局部中心（绕方块中心旋转，不旋转中心点）
        // 旋转只改变 OBB 的朝向（obbX/Y/Z 轴向量），不改变中心位置
        org.joml.Vector3f obbCenter = new org.joml.Vector3f(
                (float) px + lcX, (float) py + lcY, (float) pz + lcZ);

        float obbHalfX = (float) ((localBox.maxX - localBox.minX) * 0.5);
        float obbHalfY = (float) ((localBox.maxY - localBox.minY) * 0.5);
        float obbHalfZ = (float) ((localBox.maxZ - localBox.minZ) * 0.5);

        float aabbCx = (float) ((aabb.minX + aabb.maxX) * 0.5);
        float aabbCy = (float) ((aabb.minY + aabb.maxY) * 0.5);
        float aabbCz = (float) ((aabb.minZ + aabb.maxZ) * 0.5);
        float aabbHalfX = (float) ((aabb.maxX - aabb.minX) * 0.5);
        float aabbHalfY = (float) ((aabb.maxY - aabb.minY) * 0.5);
        float aabbHalfZ = (float) ((aabb.maxZ - aabb.minZ) * 0.5);

        // 15 条分离轴：3 AABB 面法线 + 3 OBB 面法线 + 9 条边叉积
        org.joml.Vector3f[] axes = {
            new org.joml.Vector3f(1, 0, 0), new org.joml.Vector3f(0, 1, 0), new org.joml.Vector3f(0, 0, 1),
            obbX, obbY, obbZ,
            crossAABB(0, obbX), crossAABB(0, obbY), crossAABB(0, obbZ), // (1,0,0)×obbX/Y/Z
            crossAABB(1, obbX), crossAABB(1, obbY), crossAABB(1, obbZ), // (0,1,0)×obbX/Y/Z
            crossAABB(2, obbX), crossAABB(2, obbY), crossAABB(2, obbZ)  // (0,0,1)×obbX/Y/Z
        };

        float minOverlap = Float.MAX_VALUE;
        float bestAx = 0, bestAy = 0, bestAz = 0;

        for (org.joml.Vector3f axis : axes) {
            float len = axis.length();
            if (len < 1e-6f) continue; // 平行边叉积为零向量，跳过
            float ax = axis.x / len, ay = axis.y / len, az = axis.z / len;

            float aabbRadius = aabbHalfX * Math.abs(ax) + aabbHalfY * Math.abs(ay) + aabbHalfZ * Math.abs(az);
            float obbRadius = obbHalfX * Math.abs(obbX.x * ax + obbX.y * ay + obbX.z * az)
                            + obbHalfY * Math.abs(obbY.x * ax + obbY.y * ay + obbY.z * az)
                            + obbHalfZ * Math.abs(obbZ.x * ax + obbZ.y * ay + obbZ.z * az);
            float aabbProj = aabbCx * ax + aabbCy * ay + aabbCz * az;
            float obbProj = obbCenter.x * ax + obbCenter.y * ay + obbCenter.z * az;

            float diff = Math.abs(aabbProj - obbProj);
            float overlap = aabbRadius + obbRadius - diff;
            if (overlap <= 1e-4f) return null; // 此轴分离（含浮点精度容差 0.1mm）
            if (overlap < minOverlap) {
                minOverlap = overlap;
                bestAx = ax; bestAy = ay; bestAz = az;
            }
        }

        return new SATResult(minOverlap, bestAx, bestAy, bestAz,
                obbCenter.x, obbCenter.y, obbCenter.z);
    }

    /** 计算 AABB 第 axisIdx 条边与 OBB 轴 b 的叉积。axisIdx: 0=X, 1=Y, 2=Z。 */
    private static org.joml.Vector3f crossAABB(int axisIdx, org.joml.Vector3f b) {
        return switch (axisIdx) {
            case 0 -> new org.joml.Vector3f(0, -b.z, b.y);   // (1,0,0)×b
            case 1 -> new org.joml.Vector3f(b.z, 0, -b.x);   // (0,1,0)×b
            default -> new org.joml.Vector3f(-b.y, b.x, 0);   // (0,0,1)×b
        };
    }

    /** SAT OBB vs AABB 相交检测。 */
    private static boolean aabbIntersectsOBB(World world, Box aabb, PlacedFreeBlock fb) {
        VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
        if (base.isEmpty()) base = VoxelShapes.fullCube();
        Box localBox = base.getBoundingBox();
        if (localBox == null) return false;
        SATResult sat = satAabbObb(aabb, fb.pos().x(), fb.pos().y(), fb.pos().z(),
                          localBox, fb.qx(), fb.qy(), fb.qz(), fb.qw());
        return sat != null;
    }

    /** move() RETURN 后的 onGround 安全网。
     *  <p>新方案中 onGround 主要由原版 move() 通过比较 movement.y 和 adjusted.y 自动设置。
     *  但在某些边缘情况（如 movement.y=0 的纯水平移动、或 OBB 检测边缘抖动）下，
     *  原版可能设置 onGround=false。此方法作为安全网，检测实体是否站在 OBB 上，
     *  如果是则强制 onGround=true 并清零 vel.y，防止"掉下去"。
     *  <p>不做残穿推回（旧方案的推回会导致实体"飘起来"）。 */
    public static void resolveRotatedCollisions(net.minecraft.entity.Entity entity) {
        // yClippedDown: Y 轴向下移动被裁剪（刚落地）
        // isStandingOnOBB: 实体底部与 OBB 接触（已经在 OBB 上站立/滑行）
        boolean standingOnOBB = isStandingOnOBB(entity);
        if (yClippedDown.get() || standingOnOBB) {
            entity.setOnGround(true);
            entity.fallDistance = 0f;
            // 清零 Y 速度，防止重力跨 tick 累积导致"下落速度过快"
            net.minecraft.util.math.Vec3d vel = entity.getVelocity();
            if (vel.y < 0) {
                entity.setVelocity(vel.x, 0, vel.z);
            }
        }
        yClippedDown.set(false);
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
     *  精确射线-OBB 检测：把射线变换到 OBB 局部空间做 ray-AABB，
     *  再把命中点/法线变换回世界空间。比旧的包围 AABB 近似更准确。 */
    public static Optional<FreeBlockHit> raycast(World world, Vec3d start, Vec3d end) {
        Box queryBox = new Box(start, end).expand(1.0);
        FreeBlockHit[] best = { null };
        int[] count = { 0 };
        forEachPlaced(world, queryBox, fb -> {
            count[0]++;
            VoxelShape shape = fb.state().getOutlineShape(world, fb.pos().toBlockPos());
            Box localBox;
            if (shape.isEmpty()) {
                localBox = VoxelShapes.fullCube().getBoundingBox();
            } else {
                localBox = shape.getBoundingBox();
            }
            if (localBox == null) return;

            // 精确射线-OBB：把射线变换到 OBB 局部空间做 ray-AABB（绕方块中心旋转）
            org.joml.Quaternionf q = new org.joml.Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw());
            q.normalize();
            org.joml.Quaternionf invQ = new org.joml.Quaternionf(q).invert();

            // 世界射线 → 减去 pos+0.5（中心） → 逆旋转 → 局部射线（相对于中心）
            org.joml.Vector3f ls = new org.joml.Vector3f(
                    (float)(start.x - fb.pos().x() - 0.5), (float)(start.y - fb.pos().y() - 0.5), (float)(start.z - fb.pos().z() - 0.5));
            invQ.transform(ls);
            org.joml.Vector3f le = new org.joml.Vector3f(
                    (float)(end.x - fb.pos().x() - 0.5), (float)(end.y - fb.pos().y() - 0.5), (float)(end.z - fb.pos().z() - 0.5));
            invQ.transform(le);

            // 局部碰撞箱也改为相对于中心 [-0.5,-0.5,-0.5]→[0.5,0.5,0.5]
            Box localBoxCentered = new Box(
                    localBox.minX - 0.5, localBox.minY - 0.5, localBox.minZ - 0.5,
                    localBox.maxX - 0.5, localBox.maxY - 0.5, localBox.maxZ - 0.5);
            double[] t = rayAABB(new Vec3d(ls.x, ls.y, ls.z), new Vec3d(le.x, le.y, le.z), localBoxCentered);
            if (t == null) return;

            // 命中点世界坐标 = pos + 0.5 + q.rotate(localHit)
            float lhx = ls.x + (le.x - ls.x) * (float) t[0];
            float lhy = ls.y + (le.y - ls.y) * (float) t[0];
            float lhz = ls.z + (le.z - ls.z) * (float) t[0];
            org.joml.Vector3f worldHit = new org.joml.Vector3f(lhx, lhy, lhz);
            q.transform(worldHit);
            Vec3d hit = new Vec3d(worldHit.x + fb.pos().x() + 0.5, worldHit.y + fb.pos().y() + 0.5, worldHit.z + fb.pos().z() + 0.5);

            double distSq = start.squaredDistanceTo(hit);
            if (best[0] == null || distSq < best[0].distanceSq()) {
                // 局部法线变换到世界空间，找最接近的 Direction
                Direction localSide = Direction.byId((int) t[1]);
                Direction side = transformDirection(localSide, q);
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

    /** 把局部 Box（最小角原点）的 8 个角用四元数旋转，再平移到世界坐标 pos，返回包围 AABB。 */
    public static Box rotateBoxAABB(Box local, DecimalBlockPos pos, float qx, float qy, float qz, float qw) {
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw);
        q.normalize();
        double minX = local.minX, minY = local.minY, minZ = local.minZ;
        double maxX = local.maxX, maxY = local.maxY, maxZ = local.maxZ;
        double[] xs = {minX, maxX, minX, maxX, minX, maxX, minX, maxX};
        double[] ys = {minY, minY, maxY, maxY, minY, minY, maxY, maxY};
        double[] zs = {minZ, minZ, minZ, minZ, maxZ, maxZ, maxZ, maxZ};
        double bMinX = Double.POSITIVE_INFINITY, bMinY = Double.POSITIVE_INFINITY, bMinZ = Double.POSITIVE_INFINITY;
        double bMaxX = Double.NEGATIVE_INFINITY, bMaxY = Double.NEGATIVE_INFINITY, bMaxZ = Double.NEGATIVE_INFINITY;
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            // 绕方块中心 (0.5,0.5,0.5) 旋转：先减中心，旋转，加回中心，加 pos
            v.set((float) (xs[i] - 0.5), (float) (ys[i] - 0.5), (float) (zs[i] - 0.5));
            q.transform(v);
            double wx = v.x + 0.5 + pos.x(), wy = v.y + 0.5 + pos.y(), wz = v.z + 0.5 + pos.z();
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
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw);
        q.normalize();
        double[] xs = {local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX};
        double[] ys = {local.minY, local.minY, local.maxY, local.maxY, local.minY, local.minY, local.maxY, local.maxY};
        double[] zs = {local.minZ, local.minZ, local.minZ, local.minZ, local.maxZ, local.maxZ, local.maxZ, local.maxZ};
        double[][] out = new double[8][3];
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            // 绕方块中心 (0.5,0.5,0.5) 旋转
            v.set((float) (xs[i] - 0.5), (float) (ys[i] - 0.5), (float) (zs[i] - 0.5));
            q.transform(v);
            out[i][0] = v.x + 0.5 + pos.x();
            out[i][1] = v.y + 0.5 + pos.y();
            out[i][2] = v.z + 0.5 + pos.z();
        }
        return out;
    }

    /** 把局部 Direction 用四元数旋转到世界空间，返回最接近的 Direction。 */
    private static Direction transformDirection(Direction localDir, org.joml.Quaternionf q) {
        org.joml.Vector3f v = new org.joml.Vector3f(
                localDir.getOffsetX(), localDir.getOffsetY(), localDir.getOffsetZ());
        q.transform(v);
        float absX = Math.abs(v.x), absY = Math.abs(v.y), absZ = Math.abs(v.z);
        if (absX >= absY && absX >= absZ) {
            return v.x > 0 ? Direction.EAST : Direction.WEST;
        } else if (absY >= absZ) {
            return v.y > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return v.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
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

    public static void registerCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
                                       CommandRegistryAccess registryAccess) {
        // /placefree x y z <block[state=val]> [qx qy qz qw]
        // 使用原版 BlockStateArgumentType，支持 minecraft:piston[facing=east] 等原生语法
        dispatcher.register(literal("placefree")
                .then(argument("x", DoubleArgumentType.doubleArg())
                        .then(argument("y", DoubleArgumentType.doubleArg())
                                .then(argument("z", DoubleArgumentType.doubleArg())
                                        .then(argument("block", BlockStateArgumentType.blockState(registryAccess))
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
            src.sendFeedback(() -> net.minecraft.text.Text.literal("附近无自由方块"), false);
        } else {
            src.sendFeedback(() -> net.minecraft.text.Text.literal("找到 " + blocks.size() + " 个自由方块:"), false);
            for (PlacedFreeBlock fb : blocks) {
                src.sendFeedback(() -> net.minecraft.text.Text.literal(
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
        ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(
                ok ? "已放置自由方块 " + displayName + " @ " + x + "," + y + "," + z : "放置失败（区块未加载？）"), false);
        return ok ? 1 : 0;
    }

    private static int execRemove(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        BlockState removed = removeBlockAt(ctx.getSource().getWorld(), x, y, z, 0.5);
        ctx.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(
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
                                                .executes(FreeBlocks::execDebugMoveTest)))))
                .then(literal("collision")
                        .then(argument("scenario", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(FreeBlocks::execDebugCollision)
                                .then(argument("x", DoubleArgumentType.doubleArg())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(FreeBlocks::execDebugCollision)))))));
    }

    /** /padebug info — 显示执行者位置、AABB、onGround 状态 */
    private static int execDebugInfo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(() -> Text.literal("§c此命令需要由实体执行"), false);
            return 0;
        }
        net.minecraft.entity.Entity ent = src.getEntity();
        Box aabb = ent.getBoundingBox();
        boolean onGround = ent.isOnGround();
        src.sendFeedback(() -> Text.literal(String.format(
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
            src.sendFeedback(() -> Text.literal(String.format(
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
            src.sendFeedback(() -> Text.literal("§a附近无旋转自由方块"), false);
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
        src.sendFeedback(() -> Text.literal(String.format(
                "§a[PA-Debug] 测试AABB [%.3f,%.3f,%.3f]→[%.3f,%.3f,%.3f]\n  与OBB相交: %s",
                testBox.minX, testBox.minY, testBox.minZ, testBox.maxX, testBox.maxY, testBox.maxZ,
                hit ? "§c是" : "§a否")), false);
        return hit ? 1 : 0;
    }

    /** /padebug movetest <dx> <dy> <dz> — 模拟移动并返回裁剪后的 movement */
    private static int execDebugMoveTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(() -> Text.literal("§c此命令需要由实体执行"), false);
            return 0;
        }
        double dx = DoubleArgumentType.getDouble(ctx, "dx");
        double dy = DoubleArgumentType.getDouble(ctx, "dy");
        double dz = DoubleArgumentType.getDouble(ctx, "dz");
        net.minecraft.entity.Entity ent = src.getEntity();
        Vec3d original = new Vec3d(dx, dy, dz);
        Vec3d clipped = clipMovement(ent, original);
        src.sendFeedback(() -> Text.literal(String.format(
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
            src.sendFeedback(() -> Text.literal("§c此命令需要由实体执行"), false);
            return 0;
        }
        net.minecraft.entity.Entity ent = src.getEntity();
        Box aabb = ent.getBoundingBox();
        // 向下扩展 0.1 格检测
        Box groundBox = new Box(aabb.minX, aabb.minY - 0.1, aabb.minZ, aabb.maxX, aabb.minY, aabb.maxZ);
        boolean hasGround = intersectsAnyRotatedOBB(src.getWorld(), groundBox);
        src.sendFeedback(() -> Text.literal(String.format(
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

    // ---------- /padebug collision —— 虚拟玩家碰撞模拟器 ----------
    // 用途：在服务端纯逻辑模拟玩家碰撞，不需要真实玩家在线。
    // 场景：
    //   flat    - 非旋转方块平台，测试下落站立 + 水平行走
    //   rotated - 绕 Y 轴旋转 30° 的方块，测试旋转方块站立 + 跳跃
    //   slope   - 多个旋转方块形成斜坡，测试上坡行走
    // 输出：每 tick 的 pos/box/velocity/裁剪中间值/onGround 写入日志，
    //       聊天框输出汇总结论。

    /** 玩家碰撞箱尺寸（与原版 PlayerEntity 一致）。 */
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    /** /padebug collision <scenario> [x y z] 入口。 */
    private static int execDebugCollision(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        String scenario = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "scenario").toLowerCase();
        double x, y, z;
        try {
            x = DoubleArgumentType.getDouble(ctx, "x");
            y = DoubleArgumentType.getDouble(ctx, "y");
            z = DoubleArgumentType.getDouble(ctx, "z");
        } catch (IllegalArgumentException e) {
            Vec3d pos = src.getPosition();
            x = Math.floor(pos.x) + 0.5;
            y = Math.floor(pos.y);
            z = Math.floor(pos.z) + 0.5;
        }

        // 清理附近（10 格内）已有的测试方块，避免干扰
        cleanupTestArea(world, x, y, z);

        // lambda 引用需要 final 副本
        final double fx = x, fy = y, fz = z;

        // 根据场景放置测试方块
        List<double[]> placedBlocks = new ArrayList<>(); // 记录放置的方块坐标，用于清理
        switch (scenario) {
            case "flat" -> {
                // 10×1×10 非旋转石头平台（足够大，玩家走 40 tick 不会出界）
                for (int dx = -4; dx <= 5; dx++)
                    for (int dz = -4; dz <= 5; dz++)
                        placeTestBlock(world, x + dx, y, z + dz, 0f, 0f, 0f, 1f, placedBlocks);
                src.sendFeedback(() -> Text.literal("§a[PA-Sim] 场景 flat：10×10 非旋转平台 @" + fmt(fx) + "," + fmt(fy) + "," + fmt(fz)), false);
            }
            case "rotated" -> {
                // 10×10 绕 Y 轴旋转 30° 的平台（与 flat 同尺寸，确保玩家走 40 tick 不会出界）
                double rad = Math.toRadians(30);
                float qy = (float) Math.sin(rad / 2);
                float qw = (float) Math.cos(rad / 2);
                for (int dx = -4; dx <= 5; dx++)
                    for (int dz = -4; dz <= 5; dz++)
                        placeTestBlock(world, x + dx, y, z + dz, 0f, qy, 0f, qw, placedBlocks);
                src.sendFeedback(() -> Text.literal("§a[PA-Sim] 场景 rotated：10×10 30° Y 旋转平台 @" + fmt(fx) + "," + fmt(fy) + "," + fmt(fz)), false);
            }
            case "slope" -> {
                // 8 个绕 Z 轴旋转的方块，形成上坡（每个倾角 20°，逐级升高）
                double rad = Math.toRadians(20);
                float qz = (float) Math.sin(rad / 2);
                float qw = (float) Math.cos(rad / 2);
                for (int i = 0; i < 8; i++) {
                    placeTestBlock(world, x + i, y + i * 0.4, z, 0f, 0f, qz, qw, placedBlocks);
                }
                src.sendFeedback(() -> Text.literal("§a[PA-Sim] 场景 slope：20° Z 旋转斜坡（8格）@" + fmt(fx) + "," + fmt(fy) + "," + fmt(fz)), false);
            }
            default -> {
                src.sendFeedback(() -> Text.literal("§c未知场景: " + scenario + "（可用: flat / rotated / slope）"), false);
                return 0;
            }
        }

        // 运行模拟
        SimResult result = runCollisionSim(world, x, y, z, scenario, src);

        // 输出汇总
        final SimResult r = result;
        src.sendFeedback(() -> Text.literal(String.format(
                "§e[PA-Sim] 模拟完成（%d tick）:\n"
                + "  §7初始: pos=(%.3f,%.3f,%.3f)\n"
                + "  §7结束: pos=(%.3f,%.3f,%.3f)\n"
                + "  §7最终 onGround: %s\n"
                + "  §7下落稳定 tick: %d\n"
                + "  §7水平移动总位移: (%.3f, %.3f, %.3f)\n"
                + "  §7跳跃成功: %s\n"
                + "  §7穿入 OBB 次数: %d\n"
                + "  §b详细日志见 latest.log（搜索 [PA-Sim]）",
                r.ticks, r.startX, r.startY, r.startZ,
                r.endX, r.endY, r.endZ,
                r.endOnGround ? "§a是" : "§c否",
                r.landedTick,
                r.walkDx, r.walkDy, r.walkDz,
                r.jumpSuccess ? "§a是" : "§c否",
                r.obbPenetrationCount)), false);

        // 清理测试方块
        for (double[] p : placedBlocks) {
            removeBlockAt(world, p[0], p[1], p[2], 0.5);
        }
        src.sendFeedback(() -> Text.literal("§7[PA-Sim] 已清理测试方块"), false);
        return 1;
    }

    /** 在指定位置放置测试方块并记录坐标。 */
    private static void placeTestBlock(ServerWorld world, double x, double y, double z,
                                        float qx, float qy, float qz, float qw,
                                        List<double[]> placed) {
        placeBlock(world, x, y, z, qx, qy, qz, qw, net.minecraft.block.Blocks.STONE.getDefaultState());
        placed.add(new double[]{x, y, z});
    }

    /** 清理附近 10 格内的所有自由方块（测试前预处理）。 */
    private static void cleanupTestArea(ServerWorld world, double cx, double cy, double cz) {
        Box cleanBox = new Box(cx - 10, cy - 10, cz - 10, cx + 10, cy + 10, cz + 10);
        List<PlacedFreeBlock> existing = getInBox(world, cleanBox);
        for (PlacedFreeBlock fb : existing) {
            removeBlockAt(world, fb.pos().x(), fb.pos().y(), fb.pos().z(), 0.5);
        }
    }

    /** 模拟结果。 */
    private record SimResult(int ticks, double startX, double startY, double startZ,
                              double endX, double endY, double endZ,
                              boolean endOnGround, int landedTick,
                              double walkDx, double walkDy, double walkDz,
                              boolean jumpSuccess, int obbPenetrationCount) {}

    /** 虚拟玩家碰撞模拟器。
     *  模拟原版 PlayerEntity 的物理：重力、摩擦力、碰撞裁剪、onGround 判断。
     *  碰撞裁剪顺序与真实游戏一致：
     *    1. 非旋转自由方块：collectCollisionShapes + 简化 VoxelShape 裁剪
     *    2. 旋转自由方块：clipMovementBox（OBB SAT 二分法）
     *  onGround 判断：movement.y != clipped.y && movement.y < 0（原版逻辑） */
    private static SimResult runCollisionSim(ServerWorld world, double bx, double by, double bz,
                                              String scenario, ServerCommandSource src) {
        simDebug.set(true); // 开启详细诊断日志（仅本线程）
        try {
        // 玩家脚部位置
        double px = bx + 0.0; // 站在方块中心上方
        double py = by + 3.0;  // 从方块上方 3 格下落
        double pz = bz + 0.0;
        double vx = 0, vy = 0, vz = 0;
        boolean onGround = false;
        int landedTick = -1;
        int obbPenetrationCount = 0;
        boolean jumpSuccess = false;

        double startX = px, startY = py, startZ = pz;
        // 水平行走累计位移（tick 20 之后开始走）
        double walkStartX = 0, walkStartZ = 0;
        double walkDx = 0, walkDy = 0, walkDz = 0;

        final int TOTAL_TICKS = 60;
        final double GRAVITY = -0.08;
        final double DRAG = 0.98;
        final double WALK_SPEED = 0.2;

        for (int tick = 0; tick < TOTAL_TICKS; tick++) {
            // === 输入控制 ===
            // tick 0-19: 自由下落
            // tick 20-49: 水平行走（+X 方向）
            // tick 50: 尝试跳跃
            // tick 51-59: 继续行走
            if (tick >= 20 && tick < 60) {
                vx = WALK_SPEED;
            } else {
                vx = 0;
            }
            if (tick == 50 && onGround) {
                vy = 0.42; // 跳跃初速度
                jumpSuccess = true;
            }

            // === 物理：重力 + 阻力（始终加重力，与原版一致）===
            vy += GRAVITY;
            vy *= DRAG;
            // 水平阻力（原版地面摩擦 0.6，空气 0.91）
            double horizDrag = onGround ? 0.6 : 0.91;
            vx *= horizDrag;
            vz *= horizDrag;

            // === 碰撞裁剪 ===
            Box playerBox = makePlayerBox(px, py, pz);
            Vec3d movement = new Vec3d(vx, vy, vz);

            // 1. 非旋转自由方块裁剪（简化版原版 VoxelShape 裁剪）
            List<VoxelShape> shapes = collectCollisionShapes(world, playerBox);
            Vec3d afterVoxel = clipAgainstVoxelShapes(playerBox, movement, shapes);

            // 2. 旋转自由方块裁剪（OBB SAT 二分法）
            Vec3d afterOBB = clipMovementBox(world, playerBox, afterVoxel);

            Vec3d clipped = afterOBB;
            boolean yClipped = Math.abs(movement.y - clipped.y) > 1e-6;
            boolean yClippedDown = movement.y < 0 && yClipped;

            // === onGround 判断（原版逻辑）===
            boolean newOnGround = movement.y != clipped.y && movement.y < 0;
            if (newOnGround && landedTick < 0) landedTick = tick;

            // === 穿入检测 ===
            boolean penetrated = intersectsAnyRotatedOBB(world, makePlayerBox(
                    px + clipped.x, py + clipped.y, pz + clipped.z));
            if (penetrated) obbPenetrationCount++;

            // === 记录行走位移 ===
            if (tick == 20) { walkStartX = px; walkStartZ = pz; }
            if (tick == TOTAL_TICKS - 1) {
                walkDx = px - walkStartX;
                walkDy = 0;
                walkDz = pz - walkStartZ;
            }

            // === 日志输出（每 5 tick 或关键 tick）===
            if (tick % 5 == 0 || tick == landedTick || tick == 50 || (tick < 25 && tick >= 18)) {
                PlaceAnywhereMod.LOGGER.info(String.format(
                        "[PA-Sim] tick=%d pos=(%.3f,%.3f,%.3f) box=[%.3f,%.3f,%.3f→%.3f,%.3f,%.3f]",
                        tick, px, py, pz,
                        playerBox.minX, playerBox.minY, playerBox.minZ, playerBox.maxX, playerBox.maxY, playerBox.maxZ));
                PlaceAnywhereMod.LOGGER.info(String.format(
                        "[PA-Sim]   vel=(%.4f,%.4f,%.4f) shapes=%d afterVoxel=(%.4f,%.4f,%.4f) afterOBB=(%.4f,%.4f,%.4f)",
                        vx, vy, vz, shapes.size(),
                        afterVoxel.x, afterVoxel.y, afterVoxel.z,
                        afterOBB.x, afterOBB.y, afterOBB.z));
                PlaceAnywhereMod.LOGGER.info(String.format(
                        "[PA-Sim]   onGround=%s→%s yClippedDown=%s penetrated=%s",
                        onGround, newOnGround, yClippedDown, penetrated));
            }

            // === 应用移动 ===
            px += clipped.x;
            py += clipped.y;
            pz += clipped.z;
            onGround = newOnGround;
            // 水平速度被裁剪时清零（原版行为）
            if (Math.abs(vx - clipped.x) > 1e-6) vx = 0;
            if (Math.abs(vz - clipped.z) > 1e-6) vz = 0;
            if (yClippedDown) vy = 0;
        }

        return new SimResult(TOTAL_TICKS, startX, startY, startZ, px, py, pz,
                onGround, landedTick, walkDx, walkDy, walkDz, jumpSuccess, obbPenetrationCount);
        } finally {
            simDebug.set(false); // 关闭诊断日志
        }
    }

    /** 构造玩家碰撞箱（脚部在 (px,py,pz)，向上延伸 PLAYER_HEIGHT，水平居中 PLAYER_WIDTH）。 */
    private static Box makePlayerBox(double px, double py, double pz) {
        double hw = PLAYER_WIDTH / 2.0;
        return new Box(px - hw, py, pz - hw, px + hw, py + PLAYER_HEIGHT, pz + hw);
    }

    /** 简化的 VoxelShape 碰撞裁剪（模拟原版 adjustMovementForCollisions 的单轴裁剪）。
     *  逐轴处理：Y → X → Z。每轴找到最大可移动距离使移动后 AABB 不与任何 shape 相交。
     *  不完全等价于原版（原版有步进、更复杂），但足以验证 collectCollisionShapes 是否正确。 */
    private static Vec3d clipAgainstVoxelShapes(Box box, Vec3d movement, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) return movement;
        // 预提取每个 shape 的 Box（避免重复计算）
        List<Box> shapeBoxes = new ArrayList<>(shapes.size());
        for (VoxelShape shape : shapes) {
            Box sb = shape.getBoundingBox();
            if (sb != null) shapeBoxes.add(sb);
        }
        if (shapeBoxes.isEmpty()) return movement;

        double mx = movement.x, my = movement.y, mz = movement.z;

        // Y 轴
        if (my != 0) {
            Box moved = box.offset(0, my, 0);
            for (Box sb : shapeBoxes) {
                if (!moved.intersects(sb)) continue;
                if (my < 0) {
                    my = Math.max(my, sb.maxY - box.minY);
                } else {
                    my = Math.min(my, sb.minY - box.maxY);
                }
                moved = box.offset(0, my, 0);
            }
        }
        // X 轴
        if (mx != 0) {
            Box moved = box.offset(mx, my, 0);
            for (Box sb : shapeBoxes) {
                if (!moved.intersects(sb)) continue;
                if (mx < 0) {
                    mx = Math.max(mx, sb.maxX - box.minX);
                } else {
                    mx = Math.min(mx, sb.minX - box.maxX);
                }
                moved = box.offset(mx, my, 0);
            }
        }
        // Z 轴
        if (mz != 0) {
            Box moved = box.offset(mx, my, mz);
            for (Box sb : shapeBoxes) {
                if (!moved.intersects(sb)) continue;
                if (mz < 0) {
                    mz = Math.max(mz, sb.maxZ - box.minZ);
                } else {
                    mz = Math.min(mz, sb.minZ - box.maxZ);
                }
                moved = box.offset(mx, my, mz);
            }
        }
        return new Vec3d(mx, my, mz);
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
