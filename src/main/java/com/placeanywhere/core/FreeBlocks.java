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









public final class FreeBlocks {
    
    public static final double BLOCK_SIZE = 1.0;

    



    private static boolean computingWirePower = false;

    



    private static final ThreadLocal<Boolean> inStateCapture = ThreadLocal.withInitial(() -> false);

    
    public static final ThreadLocal<PlacedFreeBlock> lastRaycastHit = new ThreadLocal<>();

    



    public static final ThreadLocal<java.util.Map<Long, BlockState>> renderNeighborMap = new ThreadLocal<>();

    private FreeBlocks() {}

    

    
    public record PlaceResult(BlockState state, net.minecraft.nbt.NbtCompound nbt) {}

    
    public interface PlaceCallback {
        PlaceResult onPlace(World world, double x, double y, double z,
                            float qx, float qy, float qz, float qw, BlockState state);
    }

    
    public static PlaceCallback placeCallback = null;

    
    public static final ThreadLocal<net.minecraft.util.math.Direction> placeFacing = new ThreadLocal<>();

    

    public static ChunkFreeData dataOf(Chunk chunk) {
        return ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
    }

    
    public static boolean placeBlock(World world, double x, double y, double z, BlockState state) {
        return placeBlock(world, x, y, z, 0f, 0f, 0f, 1f, state);
    }

    
    public static boolean placeBlock(World world, double x, double y, double z,
                                     float rqx, float rqy, float rqz, float rqw, BlockState state) {
        if (state == null || state.isAir()) return false;

        
        net.minecraft.nbt.NbtCompound customNbt = null;
        if (placeCallback != null) {
            try {
                PlaceResult result = placeCallback.onPlace(world, x, y, z, rqx, rqy, rqz, rqw, state);
                if (result != null) {
                    state = result.state();
                    customNbt = result.nbt();
                }
            } catch (Throwable t) {
                PlaceAnywhereMod.LOGGER.error("[PA-Store] placeCallback 异常", t);
            }
        }

        
        float qlen = (float) Math.sqrt(rqx * rqx + rqy * rqy + rqz * rqz + rqw * rqw);
        if (qlen > 1e-6f) {
            rqx /= qlen; rqy /= qlen; rqz /= qlen; rqw /= qlen;
        } else {
            rqx = 0f; rqy = 0f; rqz = 0f; rqw = 1f; 
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

        
        
        
        if (customNbt != null) {
            setBlockNbt(world, x, y, z, customNbt);
        } else if (state.getBlock() instanceof net.minecraft.block.BlockEntityProvider bep) {
            try {
                var be = bep.createBlockEntity(BlockPos.ofFloored(x, y, z), state);
                if (be != null) {
                    be.setWorld(world);
                    var defaultNbt = be.createNbtWithId(world.getRegistryManager());
                    setBlockNbt(world, x, y, z, defaultNbt);
                }
            } catch (Throwable ignored) {}
        }

        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        data.markSectionDirty(sy);
        PlaceAnywhereMod.LOGGER.debug("[PA-Store] placeBlock @ {},{},{} = {} (chunk {},{} layer size={})",
                x, y, z, state, chunk.getPos().x, chunk.getPos().z, layer.size());
        DecimalBlockPos pos = new DecimalBlockPos(x, y, z);
        
        onFreeBlockChanged(world, pos, state);
        
        if (state.isOf(Blocks.REDSTONE_WIRE)) {
            recomputeWirePower(world, pos);
        }
        
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        return true;
    }

    

    public static boolean placeMultiBlock(World world, double x, double y, double z,
                                          float rqx, float rqy, float rqz, float rqw,
                                          BlockState state, Direction facing) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        boolean isDoor = block instanceof net.minecraft.block.DoorBlock;
        boolean isBed = block instanceof net.minecraft.block.BedBlock;
        if (!isDoor && !isBed) {
            
            return placeBlock(world, x, y, z, rqx, rqy, rqz, rqw, state);
        }
        
        state = state.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, facing);
        
        double dx2 = 0, dy2 = 0, dz2 = 0;
        BlockState state1, state2;
        if (isDoor) {
            
            dy2 = 1.0;
            state1 = state.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER);
            state2 = state.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER);
        } else {
            
            
            dx2 = -facing.getOffsetX();
            dz2 = -facing.getOffsetZ();
            state1 = state.with(net.minecraft.state.property.Properties.BED_PART, net.minecraft.block.enums.BedPart.HEAD);
            state2 = state.with(net.minecraft.state.property.Properties.BED_PART, net.minecraft.block.enums.BedPart.FOOT);
        }
        
        boolean ok1 = placeBlock(world, x, y, z, rqx, rqy, rqz, rqw, state1);
        boolean ok2 = placeBlock(world, x + dx2, y + dy2, z + dz2, rqx, rqy, rqz, rqw, state2);
        return ok1 || ok2;
    }

    
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
        float removedX = layer.x(best), removedY = layer.y(best), removedZ = layer.z(best);
        layer.remove(best);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        data.markSectionDirty(sy);
        onFreeBlockChanged(world, new DecimalBlockPos(x, y, z), Blocks.AIR.getDefaultState());
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        
        removeMultiBlockPartner(world, cx * 16.0 + removedX, sy * 16.0 + removedY, cz * 16.0 + removedZ, removed);
        return removed;
    }

    


    private static void removeMultiBlockPartner(World world, double wx, double wy, double wz, BlockState removed) {
        Block block = removed.getBlock();
        if (block instanceof net.minecraft.block.DoorBlock) {
            var half = removed.get(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF);
            double partnerY = half == net.minecraft.block.enums.DoubleBlockHalf.LOWER ? wy + 1.0 : wy - 1.0;
            
            silentlyRemovePartner(world, wx, partnerY, wz);
        } else if (block instanceof net.minecraft.block.BedBlock) {
            var part = removed.get(net.minecraft.state.property.Properties.BED_PART);
            Direction facing = removed.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
            double px2 = wx, pz2 = wz;
            if (part == net.minecraft.block.enums.BedPart.HEAD) {
                
                px2 = wx - facing.getOffsetX();
                pz2 = wz - facing.getOffsetZ();
            } else {
                
                px2 = wx + facing.getOffsetX();
                pz2 = wz + facing.getOffsetZ();
            }
            silentlyRemovePartner(world, px2, wy, pz2);
        }
    }

    
    private static void silentlyRemovePartner(World world, double x, double y, double z) {
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
        layer.remove(best);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        data.markSectionDirty(sy);
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
    }

    
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
        data.markSectionDirty(sy);
    }

    

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
        double bestD = 0.25; 
        for (int i = 0; i < layer.size(); i++) {
            double dx = layer.x(i) - lx, dy = layer.y(i) - ly, dz = layer.z(i) - lz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = i; }
        }
        if (best < 0) return null;
        BlockState old = layer.state(best);
        layer.setState(best, newState);
        ((FreeBlockChunkAccess) chunk).placeanywhere_markFreeDirty();
        data.markSectionDirty(sy);
        
        if (world instanceof ServerWorld sw) {
            FreeBlockNetworking.sendToTrackers(sw, chunk.getPos(), data);
        }
        return old;
    }

    
    public static List<PlacedFreeBlock> getInBox(World world, Box box) {
        List<PlacedFreeBlock> out = new ArrayList<>();
        forEachPlaced(world, box, out::add);
        return out;
    }

    public static void forEachInBox(World world, Box box, BiConsumer<DecimalBlockPos, BlockState> action) {
        forEachPlaced(world, box, fb -> action.accept(fb.pos(), fb.state()));
    }

    


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
                    layer.forEachInBox(ox, oy, oz,
                            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, action);
                }
            }
        }
    }

    
    
    
    
    
    

    
    private static final ThreadLocal<Boolean> yClippedDown = ThreadLocal.withInitial(() -> false);

    


    public static List<VoxelShape> collectCollisionShapes(World world, Box queryBox) {
        List<VoxelShape> shapes = new ArrayList<>();
        forEachPlaced(world, queryBox, fb -> {
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            if (hasRotation) return; 
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            
            
            shapes.add(base.offset(fb.pos().x(), fb.pos().y(), fb.pos().z()));
        });
        return shapes;
    }

    



    public static Vec3d clipMovement(net.minecraft.entity.Entity entity, Vec3d movement) {
        yClippedDown.set(false);
        if (movement.lengthSquared() < 1e-10) return movement;
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return movement;

        Box entityBox = entity.getBoundingBox();
        double mx = movement.x, my = movement.y, mz = movement.z;
        
        
        
        
        
        final double SLOPE_RATIO = 1.0;
        final double SLOPE_TOLERANCE = 0.3;
        final double MAX_SLOPE_LIFT = 1.5;
        final double STEP_MARGIN = 0.2;

        
        
        
        
        if (my != 0) {
            Box yEntityBox = entityBox;
            if (my < 0 && (mx != 0 || mz != 0)) {
                double capX = Math.abs(mx) > 1.0 ? Math.signum(mx) : mx;
                double capZ = Math.abs(mz) > 1.0 ? Math.signum(mz) : mz;
                yEntityBox = entityBox.union(entityBox.offset(capX, 0, capZ));
            }
            double allowed = binaryClipAxis(world, yEntityBox, 0, my, 0);
            if (my < 0 && allowed > my + 1e-6) {
                yClippedDown.set(true);
            }
            my = allowed;
        }

        
        if (mx != 0) {
            Box fullBox = entityBox.offset(0, my, 0);
            Box pathQuery = fullBox.union(fullBox.offset(mx, 0, 0));
            boolean onlySlopes = pathHasAlignedSlopes(world, pathQuery, true);
            if (onlySlopes) {
                
                
                double neededLift = findSurfaceHeight(world, fullBox, mx, 0, MAX_SLOPE_LIFT);
                if (neededLift > 0.02) {
                    double smoothMaxLift = Math.abs(mx) * SLOPE_RATIO + 0.05;
                    double actualLift = Math.min(neededLift, smoothMaxLift);
                    my += actualLift;
                    yClippedDown.set(true);
                    
                    if (neededLift > smoothMaxLift + 0.01) {
                        Box liftedBox = fullBox.offset(0, actualLift, 0);
                        mx = binaryClipAxis(world, liftedBox, mx, 0, 0);
                    }
                } else if (neededLift < 0.005) {
                    
                    mx = binaryClipAxis(world, shrinkY(fullBox, STEP_MARGIN), mx, 0, 0);
                } else {
                    
                    if (my < 0) my = 0;
                    yClippedDown.set(true);
                }
            } else {
                
                
                Box groundedBox = shrinkY(fullBox, STEP_MARGIN);
                double allowed = binaryClipAxis(world, groundedBox, mx, 0, 0);
                boolean isBot = entity.getName().getString().toLowerCase().contains("bot");
                if (isBot && Math.abs(mx) > 1e-6) {
                    PlaceAnywhereMod.LOGGER.info("[PA-DEBUG] X-stepup: entY={} fullBox.minY={} mx={} allowed={} fullBox=[{}~{}]",
                            entity.getY(), fullBox.minY, mx, allowed, fullBox.minX, fullBox.maxX);
                }
                if (Math.abs(allowed) < Math.abs(mx) - 1e-6) {
                    double maxLift = 0.6;
                    double lift = findSurfaceHeight(world, fullBox, mx, 0, maxLift);
                    if (isBot) {
                        PlaceAnywhereMod.LOGGER.info("[PA-DEBUG] X-stepup ENTER: lift={} maxLift={}", lift, maxLift);
                    }
                    if (lift > 0) {
                        my += lift;
                        yClippedDown.set(true);
                        Box liftedBox = fullBox.offset(0, lift, 0);
                        mx = binaryClipAxis(world, liftedBox, mx, 0, 0);
                        if (isBot) {
                            PlaceAnywhereMod.LOGGER.info("[PA-DEBUG] X-stepup DONE: newMy={} newMx={} liftedBox.minY={}",
                                    my, mx, liftedBox.minY);
                        }
                    } else {
                        mx = allowed;
                        if (isBot) {
                            PlaceAnywhereMod.LOGGER.info("[PA-DEBUG] X-stepup FAIL: lift=0, mx=allowed={}", mx);
                        }
                    }
                } else {
                    mx = allowed;
                }
            }
        }
        
        if (mz != 0) {
            Box fullBox = entityBox.offset(mx, my, 0);
            Box pathQuery = fullBox.union(fullBox.offset(0, 0, mz));
            boolean onlySlopes = pathHasAlignedSlopes(world, pathQuery, false);
            if (onlySlopes) {
                
                double neededLift = findSurfaceHeight(world, fullBox, 0, mz, MAX_SLOPE_LIFT);
                if (neededLift > 0.02) {
                    double smoothMaxLift = Math.abs(mz) * SLOPE_RATIO + 0.05;
                    double actualLift = Math.min(neededLift, smoothMaxLift);
                    my += actualLift;
                    yClippedDown.set(true);
                    if (neededLift > smoothMaxLift + 0.01) {
                        Box liftedBox = fullBox.offset(0, actualLift, 0);
                        mz = binaryClipAxis(world, liftedBox, 0, 0, mz);
                    }
                } else if (neededLift < 0.005) {
                    mz = binaryClipAxis(world, shrinkY(fullBox, STEP_MARGIN), 0, 0, mz);
                } else {
                    
                    if (my < 0) my = 0;
                    yClippedDown.set(true);
                }
            } else {
                Box groundedBox = shrinkY(fullBox, STEP_MARGIN);
                double allowed = binaryClipAxis(world, groundedBox, 0, 0, mz);
                if (Math.abs(allowed) < Math.abs(mz) - 1e-6) {
                    double maxLift = 0.6;
                    double lift = findSurfaceHeight(world, fullBox, 0, mz, maxLift);
                    if (lift > 0) {
                        my += lift;
                        yClippedDown.set(true);
                        Box liftedBox = fullBox.offset(0, lift, 0);
                        mz = binaryClipAxis(world, liftedBox, 0, 0, mz);
                    } else {
                        mz = allowed;
                    }
                } else {
                    mz = allowed;
                }
            }
        }

        
        if (my == 0 && !yClippedDown.get()) {
            Box belowBox = entityBox.offset(mx, my, mz).offset(0, -0.1, 0);
            if (intersectsAnyRotatedOBB(world, belowBox)) {
                yClippedDown.set(true);
            }
        }

        return new Vec3d(mx, my, mz);
    }

    




    private static double findSurfaceHeight(World world, Box baseBox, double dx, double dz, double maxLift) {
        
        
        Box liftedBase = baseBox.offset(0, maxLift, 0);
        Box liftedFull = liftedBase.offset(dx, 0, dz);
        Box liftedPath = liftedBase.union(liftedFull);
        if (intersectsAnyRotatedOBB(world, liftedFull, liftedPath)) {
            return 0; 
        }
        
        double lo = 0, hi = maxLift;
        for (int i = 0; i < 8; i++) {
            double mid = (lo + hi) * 0.5;
            Box midBase = baseBox.offset(0, mid, 0);
            Box testBox = midBase.offset(dx, 0, dz);
            Box testPath = midBase.union(testBox);
            if (intersectsAnyRotatedOBB(world, testBox, testPath)) {
                lo = mid; 
            } else {
                hi = mid; 
            }
        }
        
        return Math.min(hi + 0.01, maxLift);
    }

    



    private static double binaryClipAxis(World world, Box entityBox, double dx, double dy, double dz) {
        
        Box testBox = entityBox.offset(dx, dy, dz);
        Box pathBox = entityBox.union(testBox);
        double fullMove = dx != 0 ? dx : (dy != 0 ? dy : dz);
        
        if (!intersectsAnyRotatedOBB(world, testBox, pathBox)) {
            
            Box midBox = entityBox.offset(dx * 0.5, dy * 0.5, dz * 0.5);
            if (!intersectsAnyRotatedOBB(world, midBox, pathBox)) {
                return fullMove; 
            }
            
        }
        
        double sign = Math.signum(fullMove);
        double absHi = Math.abs(fullMove);
        double lo = 0;
        for (int i = 0; i < 8; i++) {
            double mid = (lo + absHi) * 0.5;
            Box midBox = entityBox.offset(dx != 0 ? sign * mid : 0, dy != 0 ? sign * mid : 0, dz != 0 ? sign * mid : 0);
            Box midPath = entityBox.union(midBox);
            if (!intersectsAnyRotatedOBB(world, midBox, midPath)) {
                lo = mid;
            } else {
                absHi = mid;
            }
        }
        return sign * lo;
    }

    








    private static boolean pathHasAlignedSlopes(World world, Box queryBox, boolean xMoving) {
        boolean[] hasSlope = { false };
        boolean[] hasWall = { false };
        Box searchBox = queryBox.expand(0.3);
        forEachPlaced(world, searchBox, fb -> {
            if (hasWall[0]) return;
            boolean intersects = aabbIntersectsOBB(world, queryBox, fb);
            if (!intersects) return;
            
            if (isAxisAlignedRotation(fb.qx(), fb.qy(), fb.qz(), fb.qw())) {
                hasWall[0] = true;
                return;
            }
            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            
            
            
            boolean isAlignedSlope = hasRotation && (
                (xMoving && Math.abs(fb.qz()) > 0.05f) ||
                (!xMoving && Math.abs(fb.qx()) > 0.05f)
            );
            if (isAlignedSlope) {
                hasSlope[0] = true;
            } else {
                hasWall[0] = true;
            }
        });
        return hasSlope[0] && !hasWall[0];
    }

    
    private static Box shrinkY(Box box, double amount) {
        return new Box(box.minX, box.minY + amount, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    

    public static boolean intersectsAnyRotatedOBB(World world, Box aabb) {
        return intersectsAnyRotatedOBB(world, aabb, aabb.expand(0.3));
    }

    
    public static boolean intersectsAnyRotatedOBB(World world, Box aabb, Box searchBox) {
        boolean[] hit = { false };
        forEachPlaced(world, searchBox, fb -> {
            if (hit[0]) return;
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;
            float qx = fb.qx(), qy = fb.qy(), qz = fb.qz(), qw = fb.qw();
            if (isAxisAlignedRotation(qx, qy, qz, qw)) {
                
                Box worldBox = computeWorldAABB(localBox, qx, qy, qz, qw,
                        fb.pos().x(), fb.pos().y(), fb.pos().z());
                if (aabb.maxX > worldBox.minX && aabb.minX < worldBox.maxX
                        && aabb.maxY > worldBox.minY && aabb.minY < worldBox.maxY
                        && aabb.maxZ > worldBox.minZ && aabb.minZ < worldBox.maxZ) {
                    hit[0] = true;
                }
            } else {
                
                if (aabbIntersectsOBB(world, aabb, fb)) hit[0] = true;
            }
        });
        return hit[0];
    }

    
    
    
    
    

    
    
    
    private static final ThreadLocal<org.joml.Quaternionf> SAT_Q = ThreadLocal.withInitial(org.joml.Quaternionf::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_V1 = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_V2 = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_V3 = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_OBBX = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_OBBY = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_OBBZ = ThreadLocal.withInitial(org.joml.Vector3f::new);
    private static final ThreadLocal<org.joml.Vector3f> SAT_CENTER = ThreadLocal.withInitial(org.joml.Vector3f::new);
    
    private static final ThreadLocal<org.joml.Vector3f[]> SAT_AXES = ThreadLocal.withInitial(() -> {
        org.joml.Vector3f[] arr = new org.joml.Vector3f[15];
        for (int i = 0; i < 15; i++) arr[i] = new org.joml.Vector3f();
        return arr;
    });

    

    private static boolean isAxisAlignedRotation(float qx, float qy, float qz, float qw) {
        
        if (qx == 0f && qy == 0f && qz == 0f && (qw == 1f || qw == -1f)) return true;
        
        org.joml.Quaternionf q = SAT_Q.get();
        q.set(qx, qy, qz, qw);
        q.normalize();
        org.joml.Vector3f x = SAT_OBBX.get(); x.set(1, 0, 0);
        org.joml.Vector3f y = SAT_OBBY.get(); y.set(0, 1, 0);
        org.joml.Vector3f z = SAT_OBBZ.get(); z.set(0, 0, 1);
        q.transform(x);
        q.transform(y);
        q.transform(z);
        return isUnitAxisAligned(x) && isUnitAxisAligned(y) && isUnitAxisAligned(z);
    }

    
    private static boolean isUnitAxisAligned(org.joml.Vector3f v) {
        float ax = Math.abs(v.x), ay = Math.abs(v.y), az = Math.abs(v.z);
        return (ax > 0.99f && ay < 0.01f && az < 0.01f)
            || (ay > 0.99f && ax < 0.01f && az < 0.01f)
            || (az > 0.99f && ax < 0.01f && ay < 0.01f);
    }

    




    private static Box computeWorldAABB(Box localBox, float qx, float qy, float qz, float qw,
                                        double px, double py, double pz) {
        org.joml.Quaternionf q = SAT_Q.get();
        q.set(qx, qy, qz, qw);
        q.normalize();
        
        double[] cs = {localBox.minX - 0.5, localBox.maxX - 0.5};
        double[] ds = {localBox.minY - 0.5, localBox.maxY - 0.5};
        double[] es = {localBox.minZ - 0.5, localBox.maxZ - 0.5};
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        org.joml.Vector3f corner = SAT_V1.get();
        for (double cx : cs) for (double cy : ds) for (double cz : es) {
            corner.set((float) cx, (float) cy, (float) cz);
            q.transform(corner);
            corner.add((float) (px + 0.5), (float) (py + 0.5), (float) (pz + 0.5));
            if (corner.x < minX) minX = corner.x;
            if (corner.y < minY) minY = corner.y;
            if (corner.z < minZ) minZ = corner.z;
            if (corner.x > maxX) maxX = corner.x;
            if (corner.y > maxY) maxY = corner.y;
            if (corner.z > maxZ) maxZ = corner.z;
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    


    private static boolean aabbIntersectsOBB(World world, Box aabb, PlacedFreeBlock fb) {
        VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
        if (base.isEmpty()) base = VoxelShapes.fullCube();
        Box localBox = base.getBoundingBox();
        if (localBox == null) return false;

        float qx = fb.qx(), qy = fb.qy(), qz = fb.qz(), qw = fb.qw();
        
        if (isAxisAlignedRotation(qx, qy, qz, qw)) {
            Box worldBox = computeWorldAABB(localBox, qx, qy, qz, qw,
                    fb.pos().x(), fb.pos().y(), fb.pos().z());
            return aabb.maxX > worldBox.minX && aabb.minX < worldBox.maxX
                && aabb.maxY > worldBox.minY && aabb.minY < worldBox.maxY
                && aabb.maxZ > worldBox.minZ && aabb.minZ < worldBox.maxZ;
        }

        org.joml.Quaternionf q = SAT_Q.get();
        q.set(qx, qy, qz, qw);
        q.normalize();

        org.joml.Vector3f obbX = SAT_OBBX.get(); obbX.set(1, 0, 0);
        org.joml.Vector3f obbY = SAT_OBBY.get(); obbY.set(0, 1, 0);
        org.joml.Vector3f obbZ = SAT_OBBZ.get(); obbZ.set(0, 0, 1);
        q.transform(obbX);
        q.transform(obbY);
        q.transform(obbZ);

        double px = fb.pos().x(), py = fb.pos().y(), pz = fb.pos().z();
        float lcX = (float) ((localBox.minX + localBox.maxX) * 0.5);
        float lcY = (float) ((localBox.minY + localBox.maxY) * 0.5);
        float lcZ = (float) ((localBox.minZ + localBox.maxZ) * 0.5);
        
        org.joml.Vector3f obbCenter = SAT_CENTER.get();
        obbCenter.set(lcX - 0.5f, lcY - 0.5f, lcZ - 0.5f);
        q.transform(obbCenter);
        obbCenter.add((float) px + 0.5f, (float) py + 0.5f, (float) pz + 0.5f);

        float obbHalfX = (float) ((localBox.maxX - localBox.minX) * 0.5);
        float obbHalfY = (float) ((localBox.maxY - localBox.minY) * 0.5);
        float obbHalfZ = (float) ((localBox.maxZ - localBox.minZ) * 0.5);

        float aabbCx = (float) ((aabb.minX + aabb.maxX) * 0.5);
        float aabbCy = (float) ((aabb.minY + aabb.maxY) * 0.5);
        float aabbCz = (float) ((aabb.minZ + aabb.maxZ) * 0.5);
        float aabbHalfX = (float) ((aabb.maxX - aabb.minX) * 0.5);
        float aabbHalfY = (float) ((aabb.maxY - aabb.minY) * 0.5);
        float aabbHalfZ = (float) ((aabb.maxZ - aabb.minZ) * 0.5);

        
        
        
        
        org.joml.Vector3f[] axes = SAT_AXES.get();
        int axisCount = 0;
        axes[axisCount++].set(1, 0, 0);
        axes[axisCount++].set(0, 1, 0);
        axes[axisCount++].set(0, 0, 1);
        axes[axisCount++].set(obbX);
        axes[axisCount++].set(obbY);
        axes[axisCount++].set(obbZ);
        
        for (int a = 0; a < 3; a++) {
            for (int o = 0; o < 3; o++) {
                org.joml.Vector3f cross = axes[axisCount];
                axes[a].cross(axes[3 + o], cross);
                if (cross.lengthSquared() > 1e-12f) axisCount++;
            }
        }

        for (int i = 0; i < axisCount; i++) {
            org.joml.Vector3f axis = axes[i];
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
            if (aabbRadius + obbRadius <= diff) return false; 
        }
        return true; 
    }

    


    public static void resolveRotatedCollisions(net.minecraft.entity.Entity entity) {
        
        if (yClippedDown.get()) {
            entity.setOnGround(true);
            entity.fallDistance = 0f;
            yClippedDown.set(false);
        }

        
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return;
        Box[] currentBox = { entity.getBoundingBox() };
        Box searchBox = currentBox[0].expand(0.3);

        forEachPlaced(world, searchBox, fb -> {
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;

            double px = fb.pos().x(), py = fb.pos().y(), pz = fb.pos().z();
            float qx = fb.qx(), qy = fb.qy(), qz = fb.qz(), qw = fb.qw();
            Box box = currentBox[0];

            
            if (isAxisAlignedRotation(qx, qy, qz, qw)) {
                Box worldBox = computeWorldAABB(localBox, qx, qy, qz, qw, px, py, pz);
                double bMinX = worldBox.minX, bMinY = worldBox.minY, bMinZ = worldBox.minZ;
                double bMaxX = worldBox.maxX, bMaxY = worldBox.maxY, bMaxZ = worldBox.maxZ;
                
                if (box.maxX <= bMinX || box.minX >= bMaxX) return;
                if (box.maxZ <= bMinZ || box.minZ >= bMaxZ) return;
                if (box.maxY <= bMinY || box.minY >= bMaxY) return;
                double overlapTop = bMaxY - box.minY;     
                double overlapBottom = box.maxY - bMinY;  
                
                
                
                double botCenterX = (box.minX + box.maxX) * 0.5;
                double botCenterZ = (box.minZ + box.maxZ) * 0.5;
                boolean centeredX = botCenterX > bMinX && botCenterX < bMaxX;
                boolean centeredZ = botCenterZ > bMinZ && botCenterZ < bMaxZ;
                boolean isBot = entity.getName().getString().toLowerCase().contains("bot");
                if (isBot) {
                    PlaceAnywhereMod.LOGGER.info("[PA-DEBUG] resolveRot: entY={} box.minY={} bMinY={} bMaxY={} overlapTop={} centeredX={} centeredZ={} blockPos=({},{},{})",
                            entity.getY(), box.minY, bMinY, bMaxY, overlapTop, centeredX, centeredZ, px, py, pz);
                }
                if (centeredX && centeredZ) {
                    
                    double pushY = overlapTop < overlapBottom ? overlapTop : -overlapBottom;
                    if (Math.abs(pushY) > 0.3f) return; 
                    entity.setPos(entity.getX(), entity.getY() + pushY, entity.getZ());
                    currentBox[0] = entity.getBoundingBox();
                    if (pushY > 0.001f) {
                        entity.setOnGround(true);
                        entity.fallDistance = 0f;
                    }
                } else {
                    
                    double distXPos = bMaxX - box.minX;
                    double distXNeg = box.maxX - bMinX;
                    double distZPos = bMaxZ - box.minZ;
                    double distZNeg = box.maxZ - bMinZ;
                    double minDist = distXPos;
                    double pushX = distXPos, pushZ = 0;
                    if (distXNeg < minDist) { minDist = distXNeg; pushX = -distXNeg; pushZ = 0; }
                    if (distZPos < minDist) { minDist = distZPos; pushX = 0; pushZ = distZPos; }
                    if (distZNeg < minDist) { minDist = distZNeg; pushX = 0; pushZ = -distZNeg; }
                    if (minDist > 0.3f) return; 
                    entity.setPos(entity.getX() + pushX, entity.getY(), entity.getZ() + pushZ);
                    currentBox[0] = entity.getBoundingBox();
                }
                return;
            }

            org.joml.Quaternionf q = SAT_Q.get();
            q.set(qx, qy, qz, qw);
            q.normalize();

            org.joml.Vector3f obbX = SAT_OBBX.get(); obbX.set(1, 0, 0);
            org.joml.Vector3f obbY = SAT_OBBY.get(); obbY.set(0, 1, 0);
            org.joml.Vector3f obbZ = SAT_OBBZ.get(); obbZ.set(0, 0, 1);
            q.transform(obbX);
            q.transform(obbY);
            q.transform(obbZ);

            float lcX = (float) ((localBox.minX + localBox.maxX) * 0.5);
            float lcY = (float) ((localBox.minY + localBox.maxY) * 0.5);
            float lcZ = (float) ((localBox.minZ + localBox.maxZ) * 0.5);
            
            org.joml.Vector3f obbCenter = SAT_CENTER.get();
            obbCenter.set(lcX - 0.5f, lcY - 0.5f, lcZ - 0.5f);
            q.transform(obbCenter);
            obbCenter.add((float) px + 0.5f, (float) py + 0.5f, (float) pz + 0.5f);

            float obbHalfX = (float) ((localBox.maxX - localBox.minX) * 0.5);
            float obbHalfY = (float) ((localBox.maxY - localBox.minY) * 0.5);
            float obbHalfZ = (float) ((localBox.maxZ - localBox.minZ) * 0.5);

            float aabbCx = (float) ((box.minX + box.maxX) * 0.5);
            float aabbCy = (float) ((box.minY + box.maxY) * 0.5);
            float aabbCz = (float) ((box.minZ + box.maxZ) * 0.5);
            float aabbHalfX = (float) ((box.maxX - box.minX) * 0.5);
            float aabbHalfY = (float) ((box.maxY - box.minY) * 0.5);
            float aabbHalfZ = (float) ((box.maxZ - box.minZ) * 0.5);

            
            org.joml.Vector3f[] axes = SAT_AXES.get();
            int axisCount = 0;
            axes[axisCount++].set(1, 0, 0);
            axes[axisCount++].set(0, 1, 0);
            axes[axisCount++].set(0, 0, 1);
            axes[axisCount++].set(obbX);
            axes[axisCount++].set(obbY);
            axes[axisCount++].set(obbZ);
            for (int a = 0; a < 3; a++) {
                for (int o = 0; o < 3; o++) {
                    org.joml.Vector3f cross = axes[axisCount];
                    axes[a].cross(axes[3 + o], cross);
                    if (cross.lengthSquared() > 1e-12f) axisCount++;
                }
            }

            float minOverlap = Float.MAX_VALUE;
            org.joml.Vector3f minAxis = SAT_V1.get();
            boolean found = false;

            for (int i = 0; i < axisCount; i++) {
                org.joml.Vector3f axis = axes[i];
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
                if (overlap <= 0f) return; 
                if (overlap < minOverlap) {
                    minOverlap = overlap;
                    minAxis.set(ax, ay, az);
                    found = true;
                }
            }

            if (!found) return;
            
            if (minOverlap > 1.0f) return;

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

            if (pushY > 0.001f) {
                entity.setOnGround(true);
                entity.fallDistance = 0f;
            }
        });
    }

    

    








    public static BlockState findSupportingFreeBlock(World world, Box entityBox) {
        
        Box searchBox = new Box(
                entityBox.minX - 0.5, entityBox.minY - 0.5, entityBox.minZ - 0.5,
                entityBox.maxX + 0.5, entityBox.minY + 0.1, entityBox.maxZ + 0.5);
        BlockState[] result = { null };
        forEachPlaced(world, searchBox, fb -> {
            if (result[0] != null) return;
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;

            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            double topY, fbMinX, fbMaxX, fbMinZ, fbMaxZ;
            if (hasRotation) {
                Box obbAABB = rotateBoxAABB(localBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
                topY = obbAABB.maxY;
                fbMinX = obbAABB.minX; fbMaxX = obbAABB.maxX;
                fbMinZ = obbAABB.minZ; fbMaxZ = obbAABB.maxZ;
            } else {
                topY = fb.pos().y() + localBox.maxY;
                fbMinX = fb.pos().x() + localBox.minX;
                fbMaxX = fb.pos().x() + localBox.maxX;
                fbMinZ = fb.pos().z() + localBox.minZ;
                fbMaxZ = fb.pos().z() + localBox.maxZ;
            }

            
            if (topY > entityBox.minY - 0.3 && topY < entityBox.minY + 0.1) {
                
                if (fbMaxX > entityBox.minX && fbMinX < entityBox.maxX &&
                    fbMaxZ > entityBox.minZ && fbMinZ < entityBox.maxZ) {
                    result[0] = fb.state();
                }
            }
        });
        return result[0];
    }

    
    private static final ThreadLocal<java.util.WeakHashMap<net.minecraft.entity.Entity, BlockState>> supportCacheTL =
        ThreadLocal.withInitial(java.util.WeakHashMap::new);
    private static long lastSupportTick = -1;

    







    public static BlockState findSupportingFreeBlock(World world, net.minecraft.entity.Entity entity) {
        long tick = world.getTime();
        if (tick != lastSupportTick) {
            supportCacheTL.get().clear();
            lastSupportTick = tick;
        }
        BlockState cached = supportCacheTL.get().get(entity);
        if (cached != null) {
            return cached == net.minecraft.block.Blocks.AIR.getDefaultState() ? null : cached;
        }
        BlockState result = findSupportingFreeBlock(world, entity.getBoundingBox());
        BlockState toCache = (result == null) ? net.minecraft.block.Blocks.AIR.getDefaultState() : result;
        supportCacheTL.get().put(entity, toCache);
        return result;
    }

    







    public static boolean hasAutoJumpObstacle(net.minecraft.entity.Entity entity) {
        net.minecraft.world.World world = entity.getWorld();
        if (world == null) return false;

        Box playerBox = entity.getBoundingBox();
        
        float yaw = entity.getYaw();
        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));

        double stepHeight = entity.getStepHeight();
        double feetY = entity.getY();
        double obstacleThreshold = feetY + stepHeight + 0.1;

        
        double fMin = 0.1, fMax = 0.8;
        double minX = Math.min(playerBox.minX + dx * fMin, playerBox.maxX + dx * fMax);
        double maxX = Math.max(playerBox.minX + dx * fMin, playerBox.maxX + dx * fMax);
        double minZ = Math.min(playerBox.minZ + dz * fMin, playerBox.maxZ + dz * fMax);
        double maxZ = Math.max(playerBox.minZ + dz * fMin, playerBox.maxZ + dz * fMax);

        Box forwardBox = new Box(
                minX, playerBox.minY, minZ,
                maxX, playerBox.maxY, maxZ);

        boolean[] hasObstacle = { false };
        forEachPlaced(world, forwardBox, fb -> {
            if (hasObstacle[0]) return;
            VoxelShape base = fb.state().getCollisionShape(world, fb.pos().toBlockPos());
            if (base.isEmpty()) base = VoxelShapes.fullCube();
            Box localBox = base.getBoundingBox();
            if (localBox == null) return;

            boolean hasRotation = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
            double topY;
            if (hasRotation) {
                Box obbAABB = rotateBoxAABB(localBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
                topY = obbAABB.maxY;
            } else {
                topY = fb.pos().y() + localBox.maxY;
            }

            if (topY > obstacleThreshold) {
                hasObstacle[0] = true;
            }
        });
        return hasObstacle[0];
    }

    

    
    public record FreeBlockHit(DecimalBlockPos pos, BlockState state, Vec3d point, Direction side, double distanceSq,
                               float qx, float qy, float qz, float qw) {
        
        public FreeBlockHit(DecimalBlockPos pos, BlockState state, Vec3d point, Direction side, double distanceSq) {
            this(pos, state, point, side, distanceSq, 0f, 0f, 0f, 1f);
        }
    }

    












    public static Optional<FreeBlockHit> raycast(World world, Vec3d start, Vec3d end) {
        Box queryBox = new Box(start, end).expand(1.0);
        FreeBlockHit[] best = { null };
        int[] count = { 0 };
        forEachPlaced(world, queryBox, fb -> {
            count[0]++;
            VoxelShape shape = fb.state().getOutlineShape(world, fb.pos().toBlockPos());
            Box blockBox;
            if (shape.isEmpty()) {
                blockBox = VoxelShapes.fullCube().getBoundingBox();
            } else {
                blockBox = shape.getBoundingBox();
            }
            if (blockBox == null) return;
            
            Box worldBox = rotateBoxAABB(blockBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
            if (rayAABB(start, end, worldBox) == null) return;
            
            org.joml.Quaternionf q = new org.joml.Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw()).normalize();
            org.joml.Quaternionf qInv = new org.joml.Quaternionf(q).invert();
            double cx = fb.pos().x() + 0.5, cy = fb.pos().y() + 0.5, cz = fb.pos().z() + 0.5;
            org.joml.Vector3f ls = new org.joml.Vector3f(
                    (float)(start.x - cx), (float)(start.y - cy), (float)(start.z - cz));
            org.joml.Vector3f le = new org.joml.Vector3f(
                    (float)(end.x - cx), (float)(end.y - cy), (float)(end.z - cz));
            qInv.transform(ls);
            qInv.transform(le);
            
            Box localAABB = new Box(
                    blockBox.minX - 0.5, blockBox.minY - 0.5, blockBox.minZ - 0.5,
                    blockBox.maxX - 0.5, blockBox.maxY - 0.5, blockBox.maxZ - 0.5);
            double[] t = rayAABB(new Vec3d(ls.x, ls.y, ls.z), new Vec3d(le.x, le.y, le.z), localAABB);
            if (t == null) return;
            
            float lhx = ls.x + (le.x - ls.x) * (float) t[0];
            float lhy = ls.y + (le.y - ls.y) * (float) t[0];
            float lhz = ls.z + (le.z - ls.z) * (float) t[0];
            org.joml.Vector3f wh = new org.joml.Vector3f(lhx, lhy, lhz);
            q.transform(wh);
            double hitX = wh.x + cx, hitY = wh.y + cy, hitZ = wh.z + cz;
            double distSq = start.squaredDistanceTo(hitX, hitY, hitZ);
            if (best[0] == null || distSq < best[0].distanceSq()) {
                Vec3d hit = new Vec3d(hitX, hitY, hitZ);
                Direction side = Direction.byId((int) t[1]);
                best[0] = new FreeBlockHit(fb.pos(), fb.state(), hit, side, distSq,
                        fb.qx(), fb.qy(), fb.qz(), fb.qw());
            }
        });
        if (best[0] != null) {
            com.placeanywhere.PlaceAnywhereMod.LOGGER.debug(
                    "[PA-Ray] 命中 @ {},{} side={} ({})", best[0].pos().x(), best[0].pos().y(), best[0].pos().z(),
                    best[0].side(), count[0]);
        }
        return Optional.ofNullable(best[0]);
    }

    
    private static double[] rayAABB(Vec3d start, Vec3d end, Box box) {
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        double tmin = 0.0, tmax = 1.0;
        int hitAxis = 0;
        
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
            
            v.set((float) (xs[i] - 0.5), (float) (ys[i] - 0.5), (float) (zs[i] - 0.5));
            q.transform(v);
            double wx = v.x + pos.x() + 0.5, wy = v.y + pos.y() + 0.5, wz = v.z + pos.z() + 0.5;
            if (wx < bMinX) bMinX = wx; if (wx > bMaxX) bMaxX = wx;
            if (wy < bMinY) bMinY = wy; if (wy > bMaxY) bMaxY = wy;
            if (wz < bMinZ) bMinZ = wz; if (wz > bMaxZ) bMaxZ = wz;
        }
        return new Box(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
    }

    


    public static double[][] rotatedCorners(Box local, DecimalBlockPos pos, float qx, float qy, float qz, float qw) {
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw);
        q.normalize();
        double[] xs = {local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX, local.minX, local.maxX};
        double[] ys = {local.minY, local.minY, local.maxY, local.maxY, local.minY, local.minY, local.maxY, local.maxY};
        double[] zs = {local.minZ, local.minZ, local.minZ, local.minZ, local.maxZ, local.maxZ, local.maxZ, local.maxZ};
        double[][] out = new double[8][3];
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            v.set((float) (xs[i] - 0.5), (float) (ys[i] - 0.5), (float) (zs[i] - 0.5));
            q.transform(v);
            out[i][0] = v.x + pos.x() + 0.5;
            out[i][1] = v.y + pos.y() + 0.5;
            out[i][2] = v.z + pos.z() + 0.5;
        }
        return out;
    }

    

    
    public static void onFreeBlockChanged(World world, DecimalBlockPos pos, BlockState newState) {
        
        BlockPos sourceBp = pos.toBlockPos();
        for (Direction d : Direction.values()) {
            BlockPos np = sourceBp.offset(d);
            world.updateNeighbor(np, newState.getBlock(), sourceBp);
        }
        
        Box around = new Box(pos.x()-1.5, pos.y()-1.5, pos.z()-1.5, pos.x()+2.5, pos.y()+2.5, pos.z()+2.5);
        forEachInBox(world, around, (fp, fs) -> {
            if (fp.equals(pos)) return;
            try {
                if (fs.isOf(Blocks.REDSTONE_WIRE)) {
                    
                    recomputeWirePower(world, fp);
                } else if (fs.getBlock() instanceof net.minecraft.block.AbstractRailBlock) {
                    
                    recomputeRailShape(world, fp);
                } else {
                    
                    neighborUpdateWithCapture(world, fp, fs, newState.getBlock(), sourceBp);
                }
            } catch (Throwable ignored) {
                
            }
        });
    }

    



    public static void notifyNeighborToFreeBlocks(World world, BlockPos vanillaPos,
                                                  Block sourceBlock, BlockPos sourcePos) {
        
        if (inStateCapture.get()) return;
        Box around = new Box(vanillaPos.getX() - 1.5, vanillaPos.getY() - 1.5, vanillaPos.getZ() - 1.5,
                vanillaPos.getX() + 2.5, vanillaPos.getY() + 2.5, vanillaPos.getZ() + 2.5);
        forEachInBox(world, around, (fp, fs) -> {
            try {
                if (fs.isOf(Blocks.REDSTONE_WIRE)) {
                    recomputeWirePower(world, fp);
                } else if (fs.getBlock() instanceof net.minecraft.block.AbstractRailBlock) {
                    recomputeRailShape(world, fp);
                } else {
                    neighborUpdateWithCapture(world, fp, fs, sourceBlock, sourcePos);
                }
            } catch (Throwable ignored) {
                
            }
        });
    }

    














    private static void neighborUpdateWithCapture(World world, DecimalBlockPos fp, BlockState fs,
                                                   Block sourceBlock, BlockPos sourcePos) {
        BlockPos bp = fp.toBlockPos();
        BlockState vanillaBefore = world.getBlockState(bp);
        
        world.setBlockState(bp, fs, Block.FORCE_STATE | Block.SKIP_DROPS);
        inStateCapture.set(true);
        try {
            
            boolean receiving = world.isReceivingRedstonePower(bp);
            int receivedPower = world.getReceivedRedstonePower(bp);
            PlaceAnywhereMod.LOGGER.debug("[PA-Neighbor] {} @ {},{} calling neighborUpdate (isReceivingPower={}, receivedPower={})",
                    fs.getBlock(), fp.x(), fp.y(), receiving, receivedPower);
            fs.neighborUpdate(world, bp, sourceBlock, sourcePos, false);
            BlockState after = world.getBlockState(bp);
            
            
            if (after == fs && fs.getBlock() instanceof PistonBlock && fs.contains(PistonBlock.EXTENDED)) {
                boolean extended = fs.get(PistonBlock.EXTENDED);
                if (receiving != extended) {
                    after = fs.with(PistonBlock.EXTENDED, receiving);
                    world.setBlockState(bp, after, Block.FORCE_STATE | Block.SKIP_DROPS);
                }
            }
            PlaceAnywhereMod.LOGGER.debug("[PA-Neighbor] {} @ {},{} after neighborUpdate: {} -> {} (same={})",
                    fs.getBlock(), fp.x(), fp.y(), fs, after, after == fs);
            if (after != fs) {
                
                updateBlockState(world, fp.x(), fp.y(), fp.z(), after);
                PlaceAnywhereMod.LOGGER.debug("[PA-Neighbor] {} @ {},{},{} state changed: {} -> {}",
                        fs.getBlock(), fp.x(), fp.y(), fp.z(), fs, after);
            }
        } catch (Throwable ignored) {
        } finally {
            inStateCapture.set(false);
            
            world.setBlockState(bp, vanillaBefore, Block.FORCE_STATE | Block.SKIP_DROPS);
        }
    }

    

    


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

    
    public static boolean isPoweredByFreeBlocks(World world, Vec3d point, double radius) {
        return getEmittedRedstoneAround(world, point, radius) > 0;
    }

    



    private static boolean wireConnectsTo(BlockState state, Direction dir) {
        if (state.isAir()) return false;
        if (state.isOf(Blocks.REDSTONE_WIRE)) return true;
        
        if (state.isOf(Blocks.REPEATER)) {
            return dir != null && state.get(Properties.HORIZONTAL_FACING) == dir;
        }
        if (state.isOf(Blocks.COMPARATOR)) {
            return dir != null && state.get(Properties.HORIZONTAL_FACING) != dir.getOpposite();
        }
        if (state.isOf(Blocks.OBSERVER)) {
            return dir != null && state.get(Properties.FACING) == dir.getOpposite();
        }
        
        if (state.isOf(Blocks.LEVER) || state.isOf(Blocks.REDSTONE_LAMP) ||
                state.isOf(Blocks.REDSTONE_TORCH) || state.isOf(Blocks.REDSTONE_WALL_TORCH) ||
                state.isOf(Blocks.DAYLIGHT_DETECTOR) || state.isOf(Blocks.TARGET) ||
                state.isOf(Blocks.LECTERN)) {
            return true;
        }
        
        return state.isOpaque();
    }

    








    private static BlockState recomputeWireConnections(World world, DecimalBlockPos wirePos, BlockState state) {
        Vec3d center = new Vec3d(wirePos.x() + 0.5, wirePos.y() + 0.5, wirePos.z() + 0.5);
        
        Box searchBox = new Box(center.x - 1.5, center.y - 1.5, center.z - 1.5,
                               center.x + 1.5, center.y + 1.5, center.z + 1.5);

        
        double[] bestDist = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
        boolean[] isUp = { false, false, false, false };

        
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
            
            int dirIdx;
            double major;
            if (adx > adz) {
                dirIdx = dx > 0 ? 1 : 3; 
                major = adx;
                if (adz > 0.5) return;
            } else {
                dirIdx = dz > 0 ? 2 : 0; 
                major = adz;
                if (adx > 0.5) return;
            }
            if (major < bestDist[dirIdx]) {
                bestDist[dirIdx] = major;
                isUp[dirIdx] = dy > 0.5;
            }
        });

        
        BlockPos wireBp = wirePos.toBlockPos();
        Direction[] horizontals = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        for (int i = 0; i < 4; i++) {
            Direction d = horizontals[i];
            BlockPos np = wireBp.offset(d);
            BlockState ns = world.getBlockState(np);
            if (wireConnectsTo(ns, d)) {
                if (1.0 < bestDist[i]) {
                    bestDist[i] = 1.0;
                    
                    if (ns.isSolidBlock(world, np)) {
                        BlockState upState = world.getBlockState(np.up());
                        if (wireConnectsTo(upState, null)) {
                            isUp[i] = true;
                        }
                    }
                }
            }
        }

        
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

    









    public static void recomputeWirePower(World world, DecimalBlockPos wirePos) {
        
        PlacedFreeBlock current = getBlockAt(world, wirePos.x(), wirePos.y(), wirePos.z(), 0.5);
        if (current == null || !current.state().isOf(Blocks.REDSTONE_WIRE)) return;
        BlockState state = current.state();
        int oldPower = state.get(RedstoneWireBlock.POWER);

        
        BlockState connState = recomputeWireConnections(world, wirePos, state);
        boolean connChanged = !connState.equals(state);
        state = connState;

        
        
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
                PlaceAnywhereMod.LOGGER.debug("[PA-Redstone] wire @ {},{},{} power {} -> {}",
                        wirePos.x(), wirePos.y(), wirePos.z(), oldPower, newPower);
                
                onFreeBlockChanged(world, wirePos, updated);
            } else if (connChanged) {
                PlaceAnywhereMod.LOGGER.debug("[PA-Redstone] wire @ {},{},{} connections updated",
                        wirePos.x(), wirePos.y(), wirePos.z());
            }
        }
    }

    

    


    public static void recomputeRailShape(World world, DecimalBlockPos railPos) {
        PlacedFreeBlock current = getBlockAt(world, railPos.x(), railPos.y(), railPos.z(), 0.5);
        if (current == null || !(current.state().getBlock() instanceof net.minecraft.block.AbstractRailBlock)) return;
        BlockState state = current.state();
        net.minecraft.block.AbstractRailBlock railBlock = (net.minecraft.block.AbstractRailBlock) state.getBlock();
        net.minecraft.block.enums.RailShape oldShape = state.get(railBlock.getShapeProperty());

        boolean[] connected = new boolean[4]; 
        boolean[] ascending = new boolean[4];

        Vec3d center = new Vec3d(railPos.x() + 0.5, railPos.y() + 0.5, railPos.z() + 0.5);

        
        Box searchBox = new Box(center.x - 1.5, center.y - 1.5, center.z - 1.5,
                                center.x + 1.5, center.y + 1.5, center.z + 1.5);
        forEachPlaced(world, searchBox, fb -> {
            if (fb.pos().equals(railPos)) return;
            if (!(fb.state().getBlock() instanceof net.minecraft.block.AbstractRailBlock)) return;
            double dx = (fb.pos().x() + 0.5) - center.x;
            double dy = (fb.pos().y() + 0.5) - center.y;
            double dz = (fb.pos().z() + 0.5) - center.z;
            double adx = Math.abs(dx), adz = Math.abs(dz);
            double hDist = Math.max(adx, adz);
            if (hDist > 1.5 || hDist < 0.3) return;
            if (Math.abs(dy) > 1.5) return;

            int dirIdx;
            if (adx > adz) {
                dirIdx = dx > 0 ? 1 : 3;
                if (adz > 0.5) return;
            } else {
                dirIdx = dz > 0 ? 2 : 0;
                if (adx > 0.5) return;
            }
            connected[dirIdx] = true;
            ascending[dirIdx] = dy > 0.5;
        });

        
        BlockPos railBp = railPos.toBlockPos();
        Direction[] horizontals = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        for (int i = 0; i < 4; i++) {
            if (connected[i]) continue;
            Direction d = horizontals[i];
            BlockPos np = railBp.offset(d);
            BlockState ns = world.getBlockState(np);
            if (ns.getBlock() instanceof net.minecraft.block.AbstractRailBlock) {
                connected[i] = true;
                ascending[i] = np.getY() > railBp.getY();
            }
        }

        net.minecraft.block.enums.RailShape newShape = computeRailShape(state, railBlock, connected, ascending);
        if (newShape != oldShape) {
            BlockState updated = state.with(railBlock.getShapeProperty(), newShape);
            updateBlockState(world, railPos.x(), railPos.y(), railPos.z(), updated);
            PlaceAnywhereMod.LOGGER.debug("[PA-Rail] {} @ {},{} shape {} -> {}",
                    railBlock, railPos.x(), railPos.y(), oldShape, newShape);

            
            forEachPlaced(world, searchBox, fb -> {
                if (fb.pos().equals(railPos)) return;
                if (!(fb.state().getBlock() instanceof net.minecraft.block.AbstractRailBlock)) return;
                recomputeRailShape(world, fb.pos());
            });
        }
    }

    
    private static net.minecraft.block.enums.RailShape computeRailShape(
            BlockState state, net.minecraft.block.AbstractRailBlock railBlock,
            boolean[] connected, boolean[] ascending) {
        
        int count = 0;
        int first = -1, second = -1;
        for (int i = 0; i < 4; i++) {
            if (connected[i]) {
                if (first < 0) first = i;
                else if (second < 0) second = i;
                count++;
            }
        }

        boolean isStraightOnly = state.isOf(Blocks.POWERED_RAIL)
                || state.isOf(Blocks.DETECTOR_RAIL)
                || state.isOf(Blocks.ACTIVATOR_RAIL);

        
        if (count <= 1) {
            if (first == 1 || first == 3) return net.minecraft.block.enums.RailShape.EAST_WEST; 
            
            if (ascending[0]) return net.minecraft.block.enums.RailShape.ASCENDING_NORTH;
            if (ascending[2]) return net.minecraft.block.enums.RailShape.ASCENDING_SOUTH;
            return net.minecraft.block.enums.RailShape.NORTH_SOUTH;
        }

        
        if (count == 2) {
            
            if (ascending[0]) return net.minecraft.block.enums.RailShape.ASCENDING_NORTH;
            if (ascending[1]) return net.minecraft.block.enums.RailShape.ASCENDING_EAST;
            if (ascending[2]) return net.minecraft.block.enums.RailShape.ASCENDING_SOUTH;
            if (ascending[3]) return net.minecraft.block.enums.RailShape.ASCENDING_WEST;

            
            if (first == 0 && second == 2) return net.minecraft.block.enums.RailShape.NORTH_SOUTH;
            if (first == 1 && second == 3) return net.minecraft.block.enums.RailShape.EAST_WEST;

            
            if (!isStraightOnly) {
                
                if ((first == 0 && second == 1) || (first == 1 && second == 0))
                    return net.minecraft.block.enums.RailShape.NORTH_EAST;
                if ((first == 0 && second == 3) || (first == 3 && second == 0))
                    return net.minecraft.block.enums.RailShape.NORTH_WEST;
                if ((first == 2 && second == 1) || (first == 1 && second == 2))
                    return net.minecraft.block.enums.RailShape.SOUTH_EAST;
                if ((first == 2 && second == 3) || (first == 3 && second == 2))
                    return net.minecraft.block.enums.RailShape.SOUTH_WEST;
            }
            
            if (first == 1 || first == 3 || second == 1 || second == 3)
                return net.minecraft.block.enums.RailShape.EAST_WEST;
            return net.minecraft.block.enums.RailShape.NORTH_SOUTH;
        }

        
        if (ascending[0]) return net.minecraft.block.enums.RailShape.ASCENDING_NORTH;
        if (ascending[1]) return net.minecraft.block.enums.RailShape.ASCENDING_EAST;
        if (ascending[2]) return net.minecraft.block.enums.RailShape.ASCENDING_SOUTH;
        if (ascending[3]) return net.minecraft.block.enums.RailShape.ASCENDING_WEST;
        
        return net.minecraft.block.enums.RailShape.NORTH_SOUTH;
    }

    

    private static WorldChunk getChunk(World world, int cx, int cz) {
        Chunk c = world.getChunk(cx, cz);
        if (c instanceof WorldChunk wc) return wc;
        return null;
    }

    

    public static void registerCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
                                       CommandRegistryAccess registryAccess) {
        
        
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
        dispatcher.register(literal("removefreeall")
                .executes(FreeBlocks::execRemoveAll));
        dispatcher.register(literal("listfree")
                .executes(FreeBlocks::execListNear));
    }

    
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
                boolean rotated = fb.qx() != 0f || fb.qy() != 0f || fb.qz() != 0f || fb.qw() != 1f;
                src.sendFeedback(() -> net.minecraft.text.Text.literal(
                        "  @ " + fb.pos().x() + "," + fb.pos().y() + "," + fb.pos().z()
                        + " = " + fb.state()
                        + (rotated ? " q=(" + fb.qx() + "," + fb.qy() + "," + fb.qz() + "," + fb.qw() + ")" : "")), false);
            }
        }
        return blocks.size();
    }

    
    private static int execRemoveAll(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        Vec3d pos = src.getPosition();
        Box box = new Box(pos.x - 128, pos.y - 128, pos.z - 128, pos.x + 128, pos.y + 128, pos.z + 128);
        List<PlacedFreeBlock> toRemove = getInBox(world, box);
        int count = 0;
        for (PlacedFreeBlock fb : toRemove) {
            removeBlockAt(world, fb.pos().x(), fb.pos().y(), fb.pos().z(), 0.5);
            count++;
        }
        final int finalCount = count;
        src.sendFeedback(() -> Text.literal("§e已清除 " + finalCount + " 个自由方块"), false);
        return count;
    }

    
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

    
    
    
    
    
    

    public static void registerDebugCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("padebug")
                .then(literal("info").executes(FreeBlocks::execDebugInfo))
                .then(literal("obb").executes(FreeBlocks::execDebugOBB))
                .then(literal("ground").executes(FreeBlocks::execDebugGround))
                .then(literal("autotest").executes(FreeBlocks::execDebugAutoTest))
                .then(literal("boatlog").executes(FreeBlocks::execDebugBoatLog))
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

    

    private static int execDebugAutoTest(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerWorld world = src.getWorld();
        Vec3d pos = src.getPosition();
        
        double bx = Math.floor(pos.x) + 0.0;
        double by = Math.floor(pos.y - 1) + 0.0;
        double bz = Math.floor(pos.z) + 0.0;

        
        Box clearBox = new Box(bx - 2, by - 2, bz - 2, bx + 3, by + 3, bz + 3);
        List<PlacedFreeBlock> toRemove = getInBox(world, clearBox);
        int removedCount = 0;
        for (PlacedFreeBlock fb : toRemove) {
            removeBlockAt(world, fb.pos().x(), fb.pos().y(), fb.pos().z(), 0.5);
            removedCount++;
        }
        final int finalRemoved = removedCount;
        src.sendFeedback(() -> Text.literal(String.format(
                "§e[AutoTest] 清理区域残留方块 %d 个", finalRemoved)), false);

        
        boolean placed = placeBlock(world, bx, by, bz,
                net.minecraft.block.Blocks.STONE.getDefaultState());
        src.sendFeedback(() -> Text.literal(String.format(
                "§e[AutoTest] 放置石头 @ (%.1f,%.1f,%.1f) 成功=%s", bx, by, bz, placed)), false);

        
        Box verifyBox = new Box(bx - 1, by - 1, bz - 1, bx + 2, by + 2, bz + 2);
        List<PlacedFreeBlock> verifyBlocks = getInBox(world, verifyBox);
        StringBuilder verifyMsg = new StringBuilder();
        for (PlacedFreeBlock fb : verifyBlocks) {
            verifyMsg.append(String.format("(%.2f,%.2f,%.2f) ", fb.pos().x(), fb.pos().y(), fb.pos().z()));
        }
        final String verifyStr = verifyMsg.toString();
        src.sendFeedback(() -> Text.literal(String.format(
                "§e[AutoTest] 验证存储位置: %s", verifyStr.isEmpty() ? "(未找到!)" : verifyStr)), false);

        
        double spawnY = by + 2.5;
        net.minecraft.entity.Entity boat = net.minecraft.entity.EntityType.BOAT.create(world);
        if (boat == null) {
            src.sendFeedback(() -> Text.literal("§c创建 boat 失败"), false);
            return 0;
        }
        boat.setPosition(bx + 0.5, spawnY, bz + 0.5);
        world.spawnEntity(boat);
        final double expectedY = by + 1.0; 
        src.sendFeedback(() -> Text.literal(String.format(
                "§e[AutoTest] 召唤 boat @ (%.1f,%.1f,%.1f)，期望落地Y=%.2f",
                bx + 0.5, spawnY, bz + 0.5, expectedY)), false);

        
        final net.minecraft.entity.Entity boatRef = boat;
        final ServerWorld worldRef = world;
        boatTrackLogQueue.clear();
        boatTrackLogQueue.add(String.format("方块@(%.1f,%.1f,%.1f) boat@(%.1f,%.1f,%.1f) 期望Y=%.2f\n",
                bx, by, bz, bx + 0.5, spawnY, bz + 0.5, expectedY));
        
        for (PlacedFreeBlock fb : verifyBlocks) {
            boatTrackLogQueue.add(String.format("存储方块@(%.4f,%.4f,%.4f) q=(%.1f,%.1f,%.1f,%.1f)\n",
                    fb.pos().x(), fb.pos().y(), fb.pos().z(),
                    fb.qx(), fb.qy(), fb.qz(), fb.qw()));
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                for (int tick = 0; tick <= 10; tick++) {
                    final int t = tick;
                    final String[] line = {""};
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    worldRef.getServer().execute(() -> {
                        try {
                            
                            if (!boatRef.isRemoved()) {
                                boatRef.tick();
                            }
                            if (boatRef.isRemoved()) {
                                line[0] = String.format("t%d:REMOVED(%.1f,%.1f,%.1f)\n",
                                        t, boatRef.getX(), boatRef.getY(), boatRef.getZ());
                            } else {
                                Box aabb = boatRef.getBoundingBox();
                                Vec3d vel = boatRef.getVelocity();
                                line[0] = String.format("t%d:(%.4f,%.4f,%.4f)g=%s minY=%.4f vel=(%.4f,%.4f,%.4f) age=%d\n",
                                        t, boatRef.getX(), boatRef.getY(), boatRef.getZ(),
                                        boatRef.isOnGround() ? "T" : "F", aabb.minY,
                                        vel.x, vel.y, vel.z, boatRef.age);
                            }
                        } catch (Exception ex) {
                            line[0] = "t" + t + ":ERR " + ex.getMessage() + "\n";
                        }
                        latch.countDown();
                    });
                    latch.await(1, java.util.concurrent.TimeUnit.SECONDS);
                    boatTrackLogQueue.add(line[0]);
                    if (boatRef.isRemoved()) break;
                    Thread.sleep(50);
                }
                
                java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);
                worldRef.getServer().execute(() -> {
                    try {
                        Box aabb = boatRef.getBoundingBox();
                        Vec3d vel = boatRef.getVelocity();
                        boatTrackLogQueue.add(String.format("AABB=[%.4f,%.4f,%.4f]-[%.4f,%.4f,%.4f] vel=(%.4f,%.4f,%.4f)\n",
                                aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ,
                                vel.x, vel.y, vel.z));
                        Box belowBox = new Box(aabb.minX, aabb.minY - 0.3, aabb.minZ, aabb.maxX, aabb.minY + 0.1, aabb.maxZ);
                        boatTrackLogQueue.add(String.format("belowBox=[%.4f,%.4f,%.4f]-[%.4f,%.4f,%.4f]\n",
                                belowBox.minX, belowBox.minY, belowBox.minZ, belowBox.maxX, belowBox.maxY, belowBox.maxZ));
                        
                        Box searchBox = belowBox.expand(0.1);
                        boatTrackLogQueue.add(String.format("searchBox=[%.4f,%.4f,%.4f]-[%.4f,%.4f,%.4f]\n",
                                searchBox.minX, searchBox.minY, searchBox.minZ, searchBox.maxX, searchBox.maxY, searchBox.maxZ));
                        final int[] foundCount = {0};
                        forEachPlaced(worldRef, searchBox, fb -> {
                            foundCount[0]++;
                            boatTrackLogQueue.add(String.format("  found block @ (%.4f,%.4f,%.4f) q=(%.1f,%.1f,%.1f,%.1f) state=%s\n",
                                    fb.pos().x(), fb.pos().y(), fb.pos().z(),
                                    fb.qx(), fb.qy(), fb.qz(), fb.qw(), fb.state().getBlock()));
                            boolean hit = aabbIntersectsOBB(worldRef, belowBox, fb);
                            boatTrackLogQueue.add(String.format("  aabbIntersectsOBB=%s\n", hit));
                        });
                        boatTrackLogQueue.add(String.format("foundCount=%d\n", foundCount[0]));
                        boolean satHit = intersectsAnyRotatedOBB(worldRef, belowBox);
                        BlockState support = findSupportingFreeBlock(worldRef, aabb);
                        boatTrackLogQueue.add(String.format("最终:Y=%.4f g=%s rm=%s SAT=%s sup=%s\n",
                                boatRef.getY(), boatRef.isOnGround(), boatRef.isRemoved(),
                                satHit ? "T" : "F", support != null ? support.getBlock() : "null"));
                    } catch (Exception ex) {
                        boatTrackLogQueue.add("最终ERR: " + ex.getMessage() + "\n");
                    }
                    latch2.countDown();
                });
                latch2.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                boatTrackLogQueue.add("ERR: " + e.getMessage() + "\n");
            }
        });
        return 1;
    }

    
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> boatTrackLogQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    
    private static int execDebugBoatLog(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        StringBuilder sb = new StringBuilder();
        for (String line : boatTrackLogQueue) {
            sb.append(line);
        }
        String log = sb.toString();
        if (log.isEmpty()) log = "(无日志，请先执行 padebug autotest)";
        final String finalLog = log;
        ctx.getSource().sendFeedback(() -> Text.literal("§b" + finalLog), false);
        return 1;
    }

    
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

    
    private static int execDebugGround(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (src.getEntity() == null) {
            src.sendFeedback(() -> Text.literal("§c此命令需要由实体执行"), false);
            return 0;
        }
        net.minecraft.entity.Entity ent = src.getEntity();
        Box aabb = ent.getBoundingBox();
        
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
}
