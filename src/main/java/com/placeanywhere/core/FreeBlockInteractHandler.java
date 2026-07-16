package com.placeanywhere.core;

import com.placeanywhere.PlaceAnywhereMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
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














public final class FreeBlockInteractHandler {
    private FreeBlockInteractHandler() {}

    

    private record PendingRestore(ServerWorld world, BlockPos pos, BlockState vanilla,
                                  ServerPlayerEntity player, double fx, double fy, double fz) {}
    private static final List<PendingRestore> pendingRestores = new ArrayList<>();

    

    private static final Set<BlockPos> activeGuiPos = ConcurrentHashMap.newKeySet();

    


    public static BlockPos getActiveGuiPos(ScreenHandlerContext context) {
        try {
            return context.get((world, pos) -> pos).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    
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
                    
                    if (p.fx >= 0) {
                        saveBeNbtIfExists(p.world, p.pos, p.fx, p.fy, p.fz);
                    }
                    
                    
                    
                    p.world.removeBlockEntity(p.pos);
                    
                    p.world.setBlockState(p.pos, p.vanilla, net.minecraft.block.Block.FORCE_STATE);
                    activeGuiPos.remove(p.pos);
                    return true;
                }
                return false;
            });
        });
    }

    

    private static void saveBeNbtIfExists(ServerWorld world, BlockPos bpos, double fx, double fy, double fz) {
        BlockEntity be = world.getBlockEntity(bpos);
        if (be != null) {
            NbtCompound nbt = be.createNbtWithId(world.getRegistryManager());
            FreeBlocks.setBlockNbt(world, fx, fy, fz, nbt);
            PlaceAnywhereMod.LOGGER.debug("[PA-BE] 保存 BE NBT @ {},{},{} keys={} nbt={}", fx, fy, fz, nbt.getKeys(), nbt);
        } else {
            PlaceAnywhereMod.LOGGER.warn("[PA-BE] 保存失败：bpos={} 处没有 BE", bpos);
        }
    }

    
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
        PlaceAnywhereMod.LOGGER.debug("[PA-Server] 收到交互 action={}, @ {},{},{}",
                payload.action(), px, py, pz);

        switch (payload.action()) {
            case FreeBlockInteractPayload.ACTION_MINE -> handleMine(world, player, px, py, pz);
            case FreeBlockInteractPayload.ACTION_PLACE -> handlePlace(world, player, px, py, pz, side, hand,
                    payload.pointX(), payload.pointY(), payload.pointZ());
            case FreeBlockInteractPayload.ACTION_USE -> handleUse(world, player, px, py, pz, side, hand,
                    payload.pointX(), payload.pointY(), payload.pointZ());
            case FreeBlockInteractPayload.ACTION_PLACE_FREE -> handlePlaceFree(world, player, px, py, pz, hand,
                    payload.qx(), payload.qy(), payload.qz(), payload.qw());
        }
    }

    
    private static void handleMine(ServerWorld world, ServerPlayerEntity player, double px, double py, double pz) {
        PlacedFreeBlock fb = FreeBlocks.getBlockAt(world, px, py, pz, 0.5);
        BlockState removed = FreeBlocks.removeBlockAt(world, px, py, pz, 0.5);
        PlaceAnywhereMod.LOGGER.debug("[PA-Server] MINE 结果: {}", removed == null ? "未找到" : removed);
        if (removed == null || removed.isAir()) return;
        if (!player.isCreative()) {
            BlockPos dropPos = BlockPos.ofFloored(px, py, pz);
            BlockEntity be = null;
            if (fb != null && fb.nbt() != null && !fb.nbt().isEmpty()) {
                be = BlockEntity.createFromNbt(dropPos, removed, fb.nbt(), world.getRegistryManager());
            }
            net.minecraft.block.Block.dropStacks(removed, world, dropPos, be, player, player.getMainHandStack());
        }
        player.addExhaustion(0.005F);
        
        double cx = px + 0.5, cy = py + 0.5, cz = pz + 0.5;
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, removed),
                cx, cy, cz, 30, 0.3, 0.3, 0.3, 0.1);
        world.playSound(null, cx, cy, cz,
                removed.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    











    private static void handlePlace(ServerWorld world, ServerPlayerEntity player,
                                    double px, double py, double pz, Direction side, Hand hand,
                                    double pointX, double pointY, double pointZ) {
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
        Direction playerFacing = computePlayerFacing(player, qx, qy, qz, qw);
        state = applyFacing(state, playerFacing);
        
        boolean isMulti = state.getBlock() instanceof net.minecraft.block.DoorBlock
                || state.getBlock() instanceof net.minecraft.block.BedBlock;
        boolean ok;
        FreeBlocks.placeFacing.set(playerFacing);
        try {
            if (isMulti) {
                ok = FreeBlocks.placeMultiBlock(world, nx, ny, nz, qx, qy, qz, qw, state, playerFacing);
            } else {
                ok = FreeBlocks.placeBlock(world, nx, ny, nz, qx, qy, qz, qw, state);
            }
        } finally {
            FreeBlocks.placeFacing.remove();
        }
        if (ok) {
            
            if (state.getBlock() instanceof net.minecraft.block.StairsBlock) {
                updateStairShapes(world, nx, ny, nz, qx, qy, qz, qw);
            }
            
            if (state.getBlock() instanceof net.minecraft.block.AbstractRailBlock) {
                FreeBlocks.recomputeRailShape(world, new com.placeanywhere.core.DecimalBlockPos(nx, ny, nz));
            }
            world.playSound(null, nx + 0.5, ny + 0.5, nz + 0.5,
                    state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
            if (!player.isCreative()) {
                stack.decrement(1);
            }
        }
    }

    
    private static void handlePlaceFree(ServerWorld world, ServerPlayerEntity player,
                                        double px, double py, double pz, Hand hand,
                                        float qx, float qy, float qz, float qw) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        BlockState state = blockItem.getBlock().getDefaultState();
        Direction playerFacing = computePlayerFacing(player, qx, qy, qz, qw);
        state = applyFacing(state, playerFacing);
        boolean isMulti = state.getBlock() instanceof net.minecraft.block.DoorBlock
                || state.getBlock() instanceof net.minecraft.block.BedBlock;
        boolean ok;
        FreeBlocks.placeFacing.set(playerFacing);
        try {
            if (isMulti) {
                Direction facing = player.getHorizontalFacing();
                ok = FreeBlocks.placeMultiBlock(world, px, py, pz, qx, qy, qz, qw, state, facing);
            } else {
                ok = FreeBlocks.placeBlock(world, px, py, pz, qx, qy, qz, qw, state);
            }
        } finally {
            FreeBlocks.placeFacing.remove();
        }
        if (ok) {
            
            if (state.getBlock() instanceof net.minecraft.block.StairsBlock) {
                updateStairShapes(world, px, py, pz, qx, qy, qz, qw);
            }
            
            if (state.getBlock() instanceof net.minecraft.block.AbstractRailBlock) {
                FreeBlocks.recomputeRailShape(world, new com.placeanywhere.core.DecimalBlockPos(px, py, pz));
            }
            world.playSound(null, px + 0.5, py + 0.5, pz + 0.5,
                    state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
            if (!player.isCreative()) {
                stack.decrement(1);
            }
        }
    }

    


    private static Direction computePlayerFacing(ServerPlayerEntity player,
                                                  float qx, float qy, float qz, float qw) {
        Direction playerFacing = player.getHorizontalFacing();
        if (qx != 0f || qy != 0f || qz != 0f || qw != 1f) {
            org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw).normalize();
            org.joml.Quaternionf qi = new org.joml.Quaternionf(q).invert();
            org.joml.Vector3f fv = new org.joml.Vector3f(
                    playerFacing.getOffsetX(), 0, playerFacing.getOffsetZ());
            qi.transform(fv);
            playerFacing = closestHorizontalDirection(fv);
        }
        return playerFacing;
    }

    
    private static BlockState applyFacing(BlockState state, Direction facing) {
        if (state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
            state = state.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, facing);
        }
        if (state.contains(net.minecraft.state.property.Properties.FACING)) {
            state = state.with(net.minecraft.state.property.Properties.FACING, facing);
        }
        return state;
    }

    
    private static Direction closestHorizontalDirection(org.joml.Vector3f v) {
        float absX = Math.abs(v.x);
        float absZ = Math.abs(v.z);
        if (absX > absZ) {
            return v.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return v.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    

    



    private static void updateStairShapes(ServerWorld world, double x, double y, double z,
                                           float qx, float qy, float qz, float qw) {
        java.util.Set<String> updated = new java.util.HashSet<>();
        updateStairShapesRecursive(world, x, y, z, qx, qy, qz, qw, updated);
    }

    private static void updateStairShapesRecursive(ServerWorld world, double x, double y, double z,
                                                   float qx, float qy, float qz, float qw,
                                                   java.util.Set<String> updated) {
        String key = x + "," + y + "," + z;
        if (updated.contains(key)) return;
        updated.add(key);

        PlacedFreeBlock fb = FreeBlocks.getBlockAt(world, x, y, z, 0.3);
        if (fb == null) return;
        if (!(fb.state().getBlock() instanceof net.minecraft.block.StairsBlock)) return;

        BlockState computed = computeStairShapeRotated(world, x, y, z,
                fb.state(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
        boolean changed = computed != fb.state();
        if (changed) {
            FreeBlocks.updateBlockState(world, x, y, z, computed);
        }

        if (changed) {
            org.joml.Quaternionf selfQ = new org.joml.Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw()).normalize();
            double[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
            for (double[] d : dirs) {
                org.joml.Vector3f v = new org.joml.Vector3f((float)d[0], (float)d[1], (float)d[2]);
                selfQ.transform(v);
                updateStairShapesRecursive(world, x + v.x, y + v.y, z + v.z,
                        fb.qx(), fb.qy(), fb.qz(), fb.qw(), updated);
            }
        }
    }

    






    private static BlockState computeStairShapeRotated(ServerWorld world, double x, double y, double z,
                                                        BlockState state,
                                                        float qx, float qy, float qz, float qw) {
        Direction facing = state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw).normalize();
        
        org.joml.Vector3f worldFacing = new org.joml.Vector3f(
                facing.getOffsetX(), facing.getOffsetY(), facing.getOffsetZ());
        q.transform(worldFacing);
        Direction worldFacingDir = closestHorizontalDirection(worldFacing);

        
        double[] frontPos = rotatedOffset(x, y, z, facing, q);
        StairNeighbor frontN = getStairNeighborWithRot(world, frontPos[0], frontPos[1], frontPos[2]);
        if (frontN != null && neighborFacesSameDir(frontN, worldFacingDir)) {
            Direction ccw = facing.rotateYCounterclockwise();
            double[] ccwPos = rotatedOffset(x, y, z, ccw, q);
            org.joml.Vector3f worldCcw = new org.joml.Vector3f(ccw.getOffsetX(), ccw.getOffsetY(), ccw.getOffsetZ());
            q.transform(worldCcw);
            Direction worldCcwDir = closestHorizontalDirection(worldCcw);
            StairNeighbor ccwN = getStairNeighborWithRot(world, ccwPos[0], ccwPos[1], ccwPos[2]);
            if (ccwN != null && neighborFacesSameDir(ccwN, worldCcwDir)) {
                return state.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                        net.minecraft.block.enums.StairShape.INNER_LEFT);
            }
            Direction cw = facing.rotateYClockwise();
            double[] cwPos = rotatedOffset(x, y, z, cw, q);
            org.joml.Vector3f worldCw = new org.joml.Vector3f(cw.getOffsetX(), cw.getOffsetY(), cw.getOffsetZ());
            q.transform(worldCw);
            Direction worldCwDir = closestHorizontalDirection(worldCw);
            StairNeighbor cwN = getStairNeighborWithRot(world, cwPos[0], cwPos[1], cwPos[2]);
            if (cwN != null && neighborFacesSameDir(cwN, worldCwDir)) {
                return state.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                        net.minecraft.block.enums.StairShape.INNER_RIGHT);
            }
        }
        
        Direction back = facing.getOpposite();
        double[] backPos = rotatedOffset(x, y, z, back, q);
        org.joml.Vector3f worldBack = new org.joml.Vector3f(back.getOffsetX(), back.getOffsetY(), back.getOffsetZ());
        q.transform(worldBack);
        Direction worldBackDir = closestHorizontalDirection(worldBack);
        StairNeighbor backN = getStairNeighborWithRot(world, backPos[0], backPos[1], backPos[2]);
        if (backN != null && neighborFacesSameDir(backN, worldBackDir)) {
            Direction ccw = facing.rotateYCounterclockwise();
            double[] ccwPos = rotatedOffset(x, y, z, ccw, q);
            org.joml.Vector3f worldCcw = new org.joml.Vector3f(ccw.getOffsetX(), ccw.getOffsetY(), ccw.getOffsetZ());
            q.transform(worldCcw);
            Direction worldCcwDir = closestHorizontalDirection(worldCcw);
            StairNeighbor ccwN = getStairNeighborWithRot(world, ccwPos[0], ccwPos[1], ccwPos[2]);
            if (ccwN != null && neighborFacesSameDir(ccwN, worldCcwDir)) {
                return state.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                        net.minecraft.block.enums.StairShape.OUTER_LEFT);
            }
            Direction cw = facing.rotateYClockwise();
            double[] cwPos = rotatedOffset(x, y, z, cw, q);
            org.joml.Vector3f worldCw = new org.joml.Vector3f(cw.getOffsetX(), cw.getOffsetY(), cw.getOffsetZ());
            q.transform(worldCw);
            Direction worldCwDir = closestHorizontalDirection(worldCw);
            StairNeighbor cwN = getStairNeighborWithRot(world, cwPos[0], cwPos[1], cwPos[2]);
            if (cwN != null && neighborFacesSameDir(cwN, worldCwDir)) {
                return state.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                        net.minecraft.block.enums.StairShape.OUTER_RIGHT);
            }
        }
        return state.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                net.minecraft.block.enums.StairShape.STRAIGHT);
    }

    
    private record StairNeighbor(BlockState state, float qx, float qy, float qz, float qw) {}

    
    private static StairNeighbor getStairNeighborWithRot(ServerWorld world, double x, double y, double z) {
        PlacedFreeBlock fb = FreeBlocks.getBlockAt(world, x, y, z, 0.3);
        if (fb != null && fb.state().getBlock() instanceof net.minecraft.block.StairsBlock) {
            return new StairNeighbor(fb.state(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
        }
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        BlockState vs = world.getBlockState(bp);
        if (vs.getBlock() instanceof net.minecraft.block.StairsBlock) {
            return new StairNeighbor(vs, 0f, 0f, 0f, 1f);
        }
        return null;
    }

    

    private static boolean neighborFacesSameDir(StairNeighbor n, Direction targetDir) {
        Direction nFacing = n.state().get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        if (n.qx() == 0f && n.qy() == 0f && n.qz() == 0f && n.qw() == 1f) {
            return nFacing == targetDir;
        }
        org.joml.Quaternionf nq = new org.joml.Quaternionf(n.qx(), n.qy(), n.qz(), n.qw()).normalize();
        org.joml.Vector3f nv = new org.joml.Vector3f(nFacing.getOffsetX(), nFacing.getOffsetY(), nFacing.getOffsetZ());
        nq.transform(nv);
        return closestHorizontalDirection(nv) == targetDir;
    }

    
    private static double[] rotatedOffset(double x, double y, double z, Direction dir, org.joml.Quaternionf q) {
        org.joml.Vector3f v = new org.joml.Vector3f(
                dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ());
        q.transform(v);
        return new double[]{x + v.x, y + v.y, z + v.z};
    }

    


















    private static void handleUse(ServerWorld world, ServerPlayerEntity player,
                                  double px, double py, double pz, Direction side, Hand hand,
                                  double pointX, double pointY, double pointZ) {
        PlacedFreeBlock block = FreeBlocks.getBlockAt(world, px, py, pz, 0.5);
        if (block == null) return;
        BlockPos bpos = block.pos().toBlockPos();

        
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

        
        
        BlockState vanillaBefore = world.getBlockState(bpos);
        world.setBlockState(bpos, block.state(), net.minecraft.block.Block.FORCE_STATE);
        
        world.removeBlockEntity(bpos);

        
        if (block.nbt() != null && !block.nbt().isEmpty()) {
            BlockEntity be = BlockEntity.createFromNbt(bpos, block.state(), block.nbt(), world.getRegistryManager());
            if (be != null) {
                be.setWorld(world);
                WorldChunk chunk = world.getWorldChunk(bpos);
                chunk.setBlockEntity(be);
                be.markDirty();
                
                
                BlockEntity verifyBe = world.getBlockEntity(bpos);
                PlaceAnywhereMod.LOGGER.debug("[PA-BE] 恢复 BE NBT @ {} verify={} same={}",
                        bpos, verifyBe != null ? verifyBe.getClass().getSimpleName() : "null", verifyBe == be);
            }
        }

        
        activeGuiPos.add(bpos);
        PlaceAnywhereMod.LOGGER.debug("[PA-Use] handleUse bpos={} block={} activeGuiPos大小={}",
                bpos, block.state().getBlock(), activeGuiPos.size());

        
        Vec3d hitPoint = new Vec3d(
                px + 0.5 + side.getOffsetX() * 0.5,
                py + 0.5 + side.getOffsetY() * 0.5,
                pz + 0.5 + side.getOffsetZ() * 0.5);
        BlockHitResult bhr = new BlockHitResult(hitPoint, side, bpos, false);
        ItemStack stack = player.getStackInHand(hand);

        boolean interacted = false;
        try {
            var itemResult = block.state().onUseWithItem(stack, world, player, hand, bhr);
            PlaceAnywhereMod.LOGGER.debug("[PA-Use] onUseWithItem 返回 {}", itemResult);
            if (itemResult.isAccepted()) {
                interacted = true;
            } else {
                var useResult = block.state().onUse(world, player, bhr);
                PlaceAnywhereMod.LOGGER.debug("[PA-Use] onUse 返回 {}", useResult);
                if (useResult.isAccepted()) interacted = true;
            }
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[Place Anywhere] 自由方块 onUse 出错", t);
        } finally {
            
            BlockState after = world.getBlockState(bpos);
            if (after != block.state()) {
                FreeBlocks.updateBlockState(world, px, py, pz, after);
            }
            
            
            
            if (block.state().getBlock() instanceof net.minecraft.block.DoorBlock) {
                var half = block.state().get(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF);
                double partnerY = half == net.minecraft.block.enums.DoubleBlockHalf.LOWER
                        ? py + 1.0 : py - 1.0;
                PlacedFreeBlock partner = FreeBlocks.getBlockAt(world, px, partnerY, pz, 0.5);
                if (partner != null) {
                    boolean newOpen = after.get(net.minecraft.state.property.Properties.OPEN);
                    BlockState partnerState = partner.state()
                            .with(net.minecraft.state.property.Properties.OPEN, newOpen);
                    FreeBlocks.updateBlockState(world, px, partnerY, pz, partnerState);
                    PlaceAnywhereMod.LOGGER.debug("[PA-Door] 同步门另一半 OPEN={} @ {},{}",
                            newOpen, px, partnerY);
                }
            }
            boolean guiOpened = (player.currentScreenHandler != player.playerScreenHandler);
            PlaceAnywhereMod.LOGGER.debug("[PA-Use] GUI是否打开={} screenHandler={}", guiOpened,
                    guiOpened ? player.currentScreenHandler.getClass().getSimpleName() : "无");
            if (!guiOpened) {
                
                saveBeNbtIfExists(world, bpos, px, py, pz);
                world.removeBlockEntity(bpos);
                world.setBlockState(bpos, vanillaBefore, net.minecraft.block.Block.FORCE_STATE);
                activeGuiPos.remove(bpos);
                if (isFalling && supportVanilla != null) {
                    world.setBlockState(supportPos, supportVanilla, net.minecraft.block.Block.FORCE_STATE);
                }
            } else {
                
                pendingRestores.add(new PendingRestore(world, bpos, vanillaBefore, player, px, py, pz));
                if (isFalling && supportVanilla != null) {
                    pendingRestores.add(new PendingRestore(world, supportPos, supportVanilla, player, -1, -1, -1));
                }
            }
        }
        
        
        if (!interacted && stack.getItem() instanceof net.minecraft.item.BlockItem) {
            PlaceAnywhereMod.LOGGER.debug("[PA-Use] 方块未交互，fallback 到 PLACE");
            handlePlace(world, player, px, py, pz, side, hand, pointX, pointY, pointZ);
        }
    }
}
