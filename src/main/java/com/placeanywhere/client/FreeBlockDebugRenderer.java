package com.placeanywhere.client;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.DecimalBlockPos;
import com.placeanywhere.core.FreeBlocks;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Quaternionf;

import java.util.LinkedHashMap;
import java.util.Map;


















public final class FreeBlockDebugRenderer {
    private static final double RANGE = 64.0;
    private static final Random RAND = Random.create();

    private static long lastDiagLogMs = 0L;
    private static int diagFrameCount = 0;




    private record ModelCache(BakedModel model, RenderLayer layer) {}
    private static final Map<BlockState, ModelCache> MODEL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();



    private record PosKey(double x, double y, double z) {}
    private record BECache(BlockEntity be, BlockState state, NbtCompound nbt) {}

    private static final int BE_CACHE_MAX = 64;
    private static final Map<PosKey, BECache> BE_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PosKey, BECache> eldest) {
            return size() > BE_CACHE_MAX;
        }
    };


    private static final Map<Long, Integer> LIGHT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private FreeBlockDebugRenderer() {}

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        VertexConsumerProvider consumers = context.consumers();
        MatrixStack matrices = context.matrixStack();
        if (consumers == null || matrices == null) return;
        if (context.camera() == null) return;

        Vec3d cam = context.camera().getPos();
        Box range = new Box(cam.x - RANGE, cam.y - RANGE, cam.z - RANGE,
                cam.x + RANGE, cam.y + RANGE, cam.z + RANGE);

        BlockRenderManager brm = client.getBlockRenderManager();
        BlockEntityRenderDispatcher berDispatcher = client.getBlockEntityRenderDispatcher();
        int[] rendered = { 0 };


        LIGHT_CACHE.clear();








        java.util.Map<Long, BlockState> neighborMap = new java.util.HashMap<>();
        FreeBlocks.forEachPlaced(client.world, range, fb -> {
            double x = fb.pos().x(), y = fb.pos().y(), z = fb.pos().z();
            boolean aligned = x == Math.floor(x) && y == Math.floor(y) && z == Math.floor(z);
            boolean noRotation = fb.qx() == 0f && fb.qy() == 0f && fb.qz() == 0f && fb.qw() == 1f;
            if (aligned && noRotation && isFullCube(fb.state())) {
                neighborMap.put(fb.pos().toBlockPos().asLong(), fb.state());
            }
        });
        FreeBlocks.renderNeighborMap.set(neighborMap);

        matrices.push();
        try {
            FreeBlocks.forEachPlaced(client.world, range, fb -> {
                BlockState state = fb.state();
                DecimalBlockPos pos = fb.pos();
                BlockPos bpos = pos.toBlockPos();


                int light = LIGHT_CACHE.computeIfAbsent(bpos.asLong(),
                        k -> WorldRenderer.getLightmapCoordinates(client.world, bpos));

                matrices.push();

                matrices.translate(pos.x() - cam.x + 0.5, pos.y() - cam.y + 0.5, pos.z() - cam.z + 0.5);
                Quaternionf q = new Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw());
                q.normalize();
                matrices.multiply(q);
                matrices.translate(-0.5, -0.5, -0.5);


                if (state.getRenderType() != BlockRenderType.ENTITYBLOCK_ANIMATED
                        || state.getBlock() instanceof net.minecraft.block.BedBlock) {
                    ModelCache mc = MODEL_CACHE.get(state);
                    if (mc == null) {
                        BakedModel model = brm.getModel(state);
                        RenderLayer layer = RenderLayers.getBlockLayer(state);
                        mc = new ModelCache(model, layer);
                        MODEL_CACHE.put(state, mc);
                    }
                    if (mc.model() != null) {
                        VertexConsumer vc = consumers.getBuffer(mc.layer());


                        double fx = pos.x(), fy = pos.y(), fz = pos.z();
                        boolean aligned = fx == Math.floor(fx) && fy == Math.floor(fy) && fz == Math.floor(fz);
                        boolean noRotation = fb.qx() == 0f && fb.qy() == 0f && fb.qz() == 0f && fb.qw() == 1f;
                        boolean cull = aligned && noRotation && isFullCube(state);
                        brm.getModelRenderer().render(
                                client.world, mc.model(), state, bpos,
                                matrices, vc,
                                cull, RAND,
                                bpos.asLong(), light);
                    }
                }


                try {
                    if (state.hasBlockEntity() && state.getBlock() instanceof net.minecraft.block.BlockEntityProvider bep) {
                        NbtCompound fbNbt = fb.nbt();
                        PosKey key = new PosKey(pos.x(), pos.y(), pos.z());
                        BECache cached = BE_CACHE.get(key);
                        BlockEntity be;

                        if (cached != null && cached.state() == state
                                && cached.nbt() == fbNbt
                                && cached.be().getWorld() == client.world) {
                            be = cached.be();
                        } else {
                            be = bep.createBlockEntity(bpos, state);
                            if (be != null) {
                                be.setWorld(client.world);
                                if (fbNbt != null && !fbNbt.isEmpty()) {
                                    be.read(fbNbt, client.world.getRegistryManager());
                                }
                                BE_CACHE.put(key, new BECache(be, state, fbNbt));
                            }
                        }
                        if (be != null) {
                            berDispatcher.renderEntity(
                                    be, matrices, consumers, light, OverlayTexture.DEFAULT_UV);
                        }
                    }
                } catch (Throwable t) {
                    PlaceAnywhereMod.LOGGER.error("[PA-Render] BE 渲染失败: {} @ {},{},{}",
                            state.getBlock(), pos.x(), pos.y(), pos.z(), t);
                }

                matrices.pop();
                rendered[0]++;
            });

            if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
                immediate.draw();
            }
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[Place Anywhere] 渲染自由方块时出错", t);
        } finally {
            matrices.pop();

            FreeBlocks.renderNeighborMap.remove();
        }

        diagFrameCount++;
        logDiag("帧#{} 已渲染 {} 个自由方块", diagFrameCount, rendered[0]);
    }


    public static void clearAllCache() {
        MODEL_CACHE.clear();
        BE_CACHE.clear();
        LIGHT_CACHE.clear();
        FULL_CUBE_CACHE.clear();
    }


    private static final Map<BlockState, Boolean> FULL_CUBE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();



    private static boolean isFullCube(BlockState state) {
        return FULL_CUBE_CACHE.computeIfAbsent(state, s -> {
            try {
                var shape = s.getOutlineShape((net.minecraft.world.BlockView) null, BlockPos.ORIGIN);
                return shape == net.minecraft.util.shape.VoxelShapes.fullCube();
            } catch (Throwable t) {
                return false;
            }
        });
    }

    private static void logDiag(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLogMs < 1000L) return;
        lastDiagLogMs = now;
        PlaceAnywhereMod.LOGGER.info("[PA-Render] " + fmt, args);
    }
}
