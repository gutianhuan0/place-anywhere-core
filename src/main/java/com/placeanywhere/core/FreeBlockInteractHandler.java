package com.placeanywhere.core;

import com.placeanywhere.PlaceAnywhereMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端自由方块交互处理。
 *
 * 接收客户端发来的 {@link FreeBlockInteractPayload}，权威执行：
 *   MINE  —— 移除自由方块并掉落对应物品（创造模式不掉落）
 *   PLACE —— 拿玩家手持 BlockItem，在命中面外侧放置新自由方块
 *   USE   —— 构造 BlockHitResult 调用方块的 onUse / onUseWithItem，触发门/按钮等交互
 *
 * 容器类方块（箱子/制箭台/熔炉等）的处理：
 *   1. 用 NOTIFY_ALL 把方块临时放到整数网格位置（客户端看到方块+BE → 制箭台能打开/箱子有贴图）
 *   2. 打开 GUI 前，从自由方块层恢复 BE NBT 数据（箱子里的物品等）
 *   3. GUI 关闭后，把 BE NBT 保存回自由方块层，再用 NOTIFY_ALL 恢复整数网格原状
 */
public final class FreeBlockInteractHandler {
    private FreeBlockInteractHandler() {}

    /** 待恢复的整数网格位置（GUI 打开期间保持方块，关闭后恢复）。
     *  fx/fy/fz 为自由方块的世界坐标（用于保存 BE NBT）；fx<0 表示不需要保存 NBT（支撑方块等）。 */
    private record PendingRestore(ServerWorld world, BlockPos pos, BlockState vanilla,
                                  ServerPlayerEntity player, double fx, double fy, double fz) {}
    private static final List<PendingRestore> pendingRestores = new ArrayList<>();

    /** 当前正在打开 GUI 的自由方块 bpos 集合。
     *  ScreenHandlerMixin 检查此集合，跳过 canUse 原版检查。 */
    private static final Set<BlockPos> activeGuiPos = ConcurrentHashMap.newKeySet();

    /** 从 ScreenHandlerContext 提取 BlockPos。
     *  ScreenHandlerContext 没有 getPos() 方法，用 get(BiFunction) 官方 API 获取。
     *  之前的反射方式在匿名内部类上不可靠，导致 canUse 检查失败、GUI 闪退。 */
    public static BlockPos getActiveGuiPos(ScreenHandlerContext context) {
        try {
            return context.get((world, pos) -> pos).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 检查 pos 是否在 activeGuiPos 集合中。 */
    public static boolean isActiveGuiPos(BlockPos pos) {
        return activeGuiPos.contains(pos);
    }

    static {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingRestores.isEmpty()) return;
            pendingRestores.removeIf(p -> {
                boolean guiClosed = p.player.isRemoved()
                        || p.player.networkHandler == null
                        || p.player.currentScreenHandler == p.player.playerScreenHandler;
                if (guiClosed) {
                    // GUI 已关闭：先保存 BE NBT（如果是自由方块位置），再移除 BE，最后恢复整数网格
                    if (p.fx >= 0) {
                        saveBeNbtIfExists(p.world, p.pos, p.fx, p.fy, p.fz);
                    }
                    // 关键：setBlockState 恢复 air 时会触发 onStateReplaced，
                    // 如果 BE 还在，某些方块（如潜影盒）会洒物品导致复制。
                    // 先移除 BE，onStateReplaced 时没有 BE 就不会洒物品。
                    p.world.removeBlockEntity(p.pos);
                    // 用 FORCE_STATE 恢复（客户端从未收到方块变更，无需同步）
                    p.world.setBlockState(p.pos, p.vanilla, net.minecraft.block.Block.FORCE_STATE);
                    activeGuiPos.remove(p.pos);
                    return true;
                }
                return false;
            });
        });
    }

    /** 把整数网格 bpos 处的 BE NBT 保存到自由方块层。
     *  用 createNbtWithId（含类型 ID），createFromNbt 恢复时需要类型 ID。 */
    private static void saveBeNbtIfExists(ServerWorld world, BlockPos bpos, double fx, double fy, double fz) {
        BlockEntity be = world.getBlockEntity(bpos);
        if (be != null) {
            NbtCompound nbt = be.createNbtWithId();
            FreeBlocks.setBlockNbt(world, fx, fy, fz, nbt);
            PlaceAnywhereMod.LOGGER.info("[PA-BE] 保存 BE NBT @ {},{},{} keys={} nbt={}", fx, fy, fz, nbt.getKeys(), nbt);
        } else {
            PlaceAnywhereMod.LOGGER.warn("[PA-BE] 保存失败：bpos={} 处没有 BE", bpos);
        }
    }

    /** 向所有追踪 bpos 的玩家发送 BlockEntityUpdateS2C 包，同步 BE NBT 数据。 */
    private static void sendBeUpdate(ServerWorld world, BlockPos bpos, BlockEntity be) {
        try {
            var packet = net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket.create(be);
            world.getPlayers().forEach(p -> {
                if (p.getBlockPos().isWithinDistance(bpos, 64)) {
                    p.networkHandler.sendPacket(packet);
                }
            });
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[PA-BE] 发送 BE 更新包失败", t);
        }
    }

    public static void handle(FreeBlockInteractPayload payload, ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        double px = payload.hitX(), py = payload.hitY(), pz = payload.hitZ();
        Direction side = payload.side();
        Hand hand = payload.handId() == 1 ? Hand.OFF_HAND : Hand.MAIN_HAND;
        PlaceAnywhereMod.LOGGER.info("[PA-Server] 收到交互 action={}, @ {},{},{}",
                payload.action(), px, py, pz);

        switch (payload.action()) {
            case FreeBlockInteractPayload.ACTION_MINE -> handleMine(world, player, px, py, pz);
            case FreeBlockInteractPayload.ACTION_PLACE -> handlePlace(world, player, px, py, pz, side, hand);
            case FreeBlockInteractPayload.ACTION_USE -> handleUse(world, player, px, py, pz, side, hand);
            case FreeBlockInteractPayload.ACTION_PLACE_FREE -> handlePlaceFree(world, player, px, py, pz, hand,
                    payload.qx(), payload.qy(), payload.qz(), payload.qw());
        }
    }

    /** 挖掘：移除自由方块，生存模式掉落对应物品。 */
    private static void handleMine(ServerWorld world, ServerPlayerEntity player, double px, double py, double pz) {
        BlockState removed = FreeBlocks.removeBlockAt(world, px, py, pz, 0.5);
        PlaceAnywhereMod.LOGGER.info("[PA-Server] MINE 结果: {}", removed == null ? "未找到" : removed);
        if (removed == null || removed.isAir()) return;
        if (!player.isCreative()) {
            BlockPos dropPos = BlockPos.ofFloored(px, py, pz);
            net.minecraft.block.Block.dropStacks(removed, world, dropPos, null, player, player.getMainHandStack());
        }
        player.addExhaustion(0.005F);
    }

    /** 贴着放置：在命中面外侧放置手持 BlockItem 对应的方块。
     *  继承被贴方块的旋转四元数；偏移方向也用四元数旋转，使新方块贴在旋转后的表面上。 */
    private static void handlePlace(ServerWorld world, ServerPlayerEntity player,
                                    double px, double py, double pz, Direction side, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        PlacedFreeBlock base = FreeBlocks.getBlockAt(world, px, py, pz, 0.5);
        float qx = 0f, qy = 0f, qz = 0f, qw = 1f;
        double offX = side.getOffsetX(), offY = side.getOffsetY(), offZ = side.getOffsetZ();
        if (base != null) {
            qx = base.qx(); qy = base.qy(); qz = base.qz(); qw = base.qw();
            org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw).normalize();
            org.joml.Vector3f dir = new org.joml.Vector3f(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
            q.transform(dir);
            offX = dir.x; offY = dir.y; offZ = dir.z;
        }
        double nx = px + offX;
        double ny = py + offY;
        double nz = pz + offZ;
        BlockState state = blockItem.getBlock().getDefaultState();
        boolean ok = FreeBlocks.placeBlock(world, nx, ny, nz, qx, qy, qz, qw, state);
        if (ok && !player.isCreative()) {
            stack.decrement(1);
        }
    }

    /** 自由放置模式：在指定坐标放置手持 BlockItem，带四元数旋转。 */
    private static void handlePlaceFree(ServerWorld world, ServerPlayerEntity player,
                                        double px, double py, double pz, Hand hand,
                                        float qx, float qy, float qz, float qw) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        BlockState state = blockItem.getBlock().getDefaultState();
        boolean ok = FreeBlocks.placeBlock(world, px, py, pz, qx, qy, qz, qw, state);
        if (ok && !player.isCreative()) {
            stack.decrement(1);
        }
    }

    /** 交互：调用方块的 onUse / onUseWithItem。
     *
     *  关键设计：用 NOTIFY_ALL（而非 FORCE_STATE）把方块临时放到整数网格位置。
     *  这样客户端能看到方块+BlockEntity，从而：
     *    - 制箭台/工作台/熔炉的 GUI 能正常打开
     *    - 箱子/木桶等容器方块有正确贴图
     *    - 容器内物品通过 BE NBT 保存/恢复实现持久化
     *
     *  ScreenHandlerMixin 注入 canUse，对 activeGuiPos 中的位置返回 true → 绕过距离检查。
     *  非交互式操作（按钮/门等）：onUse 后立即恢复整数网格。
     *  交互式 GUI（工作台/熔炉等）：GUI 关闭后才恢复整数网格，恢复前保存 BE NBT。
     *  铁砧等 FallingBlock：放支撑方块防止掉落。 */
    private static void handleUse(ServerWorld world, ServerPlayerEntity player,
                                  double px, double py, double pz, Direction side, Hand hand) {
        PlacedFreeBlock block = FreeBlocks.getBlockAt(world, px, py, pz, 0.5);
        if (block == null) return;
        BlockPos bpos = block.pos().toBlockPos();

        // 铁砧等 FallingBlock 特殊处理：下方放支撑方块防止掉落
        boolean isFalling = block.state().getBlock() instanceof FallingBlock;
        BlockPos supportPos = bpos.down();
        BlockState supportVanilla = null;
        if (isFalling) {
            supportVanilla = world.getBlockState(supportPos);
            if (supportVanilla.isAir()) {
                world.setBlockState(supportPos, net.minecraft.block.Blocks.STONE.getDefaultState(),
                        net.minecraft.block.Block.FORCE_STATE);
            }
        }

        // 用 FORCE_STATE 写入（客户端不收到方块更新，避免整数网格箱子渲染）。
        // 服务端仍创建 BE，GUI 能正常打开。客户端看到的是自由方块渲染。
        BlockState vanillaBefore = world.getBlockState(bpos);
        world.setBlockState(bpos, block.state(), net.minecraft.block.Block.FORCE_STATE);
        // 关键：setBlockState 可能创建空 BE，需要先移除再用恢复的 NBT 替换
        world.removeBlockEntity(bpos);

        // 如果有保存的 BE NBT 数据，用 createFromNbt 创建新 BE 并替换
        if (block.nbt() != null && !block.nbt().isEmpty()) {
            BlockEntity be = BlockEntity.createFromNbt(bpos, block.state(), block.nbt());
            if (be != null) {
                be.setWorld(world);
                WorldChunk chunk = world.getWorldChunk(bpos);
                chunk.setBlockEntity(be);
                be.markDirty();
                // 不发送 BE 更新包——客户端不需要看到整数网格上的 BE，
                // 自由方块的 BE 渲染由 FreeBlockDebugRenderer 处理
                BlockEntity verifyBe = world.getBlockEntity(bpos);
                PlaceAnywhereMod.LOGGER.info("[PA-BE] 恢复 BE NBT @ {} verify={} same={}",
                        bpos, verifyBe != null ? verifyBe.getClass().getSimpleName() : "null", verifyBe == be);
            }
        }

        // 把 bpos 加入 activeGuiPos，让 ScreenHandlerMixin 跳过 canUse 检查
        activeGuiPos.add(bpos);
        PlaceAnywhereMod.LOGGER.info("[PA-Use] handleUse bpos={} block={} activeGuiPos大小={}",
                bpos, block.state().getBlock(), activeGuiPos.size());

        // BlockHitResult 指向 bpos
        Vec3d hitPoint = new Vec3d(
                bpos.getX() + 0.5 + side.getOffsetX() * 0.5,
                bpos.getY() + 0.5 + side.getOffsetY() * 0.5,
                bpos.getZ() + 0.5 + side.getOffsetZ() * 0.5);
        BlockHitResult bhr = new BlockHitResult(hitPoint, side, bpos, false);
        ItemStack stack = player.getStackInHand(hand);

        try {
            var itemResult = block.state().onUse(world, player, hand, bhr);
            PlaceAnywhereMod.LOGGER.info("[PA-Use] onUse 返回 {}", itemResult);
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[Place Anywhere] 自由方块 onUse 出错", t);
        } finally {
            // 捕获 onUse 后的状态（可能是按钮按下等新状态）
            BlockState after = world.getBlockState(bpos);
            if (after != block.state()) {
                FreeBlocks.updateBlockState(world, px, py, pz, after);
            }
            boolean guiOpened = (player.currentScreenHandler != player.playerScreenHandler);
            PlaceAnywhereMod.LOGGER.info("[PA-Use] GUI是否打开={} screenHandler={}", guiOpened,
                    guiOpened ? player.currentScreenHandler.getClass().getSimpleName() : "无");
            if (!guiOpened) {
                // 没打开 GUI（按钮/门等）：保存 BE NBT（如果有），移除 BE，立即恢复
                saveBeNbtIfExists(world, bpos, px, py, pz);
                world.removeBlockEntity(bpos);
                world.setBlockState(bpos, vanillaBefore, net.minecraft.block.Block.FORCE_STATE);
                activeGuiPos.remove(bpos);
                if (isFalling && supportVanilla != null) {
                    world.setBlockState(supportPos, supportVanilla, net.minecraft.block.Block.FORCE_STATE);
                }
            } else {
                // GUI 打开了：等 GUI 关闭后再恢复（tick handler 会保存 BE NBT 并恢复）
                pendingRestores.add(new PendingRestore(world, bpos, vanillaBefore, player, px, py, pz));
                if (isFalling && supportVanilla != null) {
                    pendingRestores.add(new PendingRestore(world, supportPos, supportVanilla, player, -1, -1, -1));
                }
            }
        }
    }
}
