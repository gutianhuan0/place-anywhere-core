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
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Quaternionf;

/**
 * 自由方块渲染：支持普通方块模型 + BlockEntityRenderer（箱子/木桶/潜影盒等）。
 *
 * 渲染流程：
 *   1. 对每个自由方块，先渲染方块模型（BakedModel）
 *   2. 如果方块有对应的 BlockEntityRenderer，创建临时 BE 并调用 renderer.render()
 *
 * BlockEntityRenderer 渲染：箱子、木桶、潜影盒、信标、床等使用特殊渲染器，
 * 不能用 BlockModelRenderer 渲染，必须调用对应的 BlockEntityRenderer。
 */
public final class FreeBlockDebugRenderer {
    private static final double RANGE = 64.0;
    private static final Random RAND = Random.create();

    private static long lastDiagLogMs = 0L;
    private static int diagFrameCount = 0;

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

        matrices.push();
        try {
            FreeBlocks.forEachPlaced(client.world, range, fb -> {
                BlockState state = fb.state();
                DecimalBlockPos pos = fb.pos();
                BlockPos bpos = pos.toBlockPos();
                int light = WorldRenderer.getLightmapCoordinates(client.world, bpos);

                matrices.push();
                matrices.translate(pos.x() - cam.x, pos.y() - cam.y, pos.z() - cam.z);
                Quaternionf q = new Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw());
                q.normalize();
                matrices.multiply(q);

                // 1. 渲染方块模型（非 ENTITYBLOCK_ANIMATED 类型）
                if (state.getRenderType() != BlockRenderType.ENTITYBLOCK_ANIMATED) {
                    BakedModel model = brm.getModel(state);
                    if (model != null) {
                        RenderLayer layer = RenderLayers.getBlockLayer(state);
                        VertexConsumer vc = consumers.getBuffer(layer);
                        brm.getModelRenderer().render(
                                client.world, model, state, bpos,
                                matrices, vc,
                                false, RAND,
                                0L, light);
                    }
                }

                // 2. 渲染 BlockEntityRenderer（箱子/木桶/潜影盒等）
                try {
                    if (state.hasBlockEntity() && state.getBlock() instanceof net.minecraft.block.BlockWithEntity bwe) {
                        BlockEntity be = bwe.createBlockEntity(bpos, state);
                        if (be != null) {
                            be.setWorld(client.world);
                            // 如果有保存的 NBT，加载
                            if (fb.nbt() != null && !fb.nbt().isEmpty()) {
                                be.readNbt(fb.nbt());
                            }
                            // 用 renderEntity 直接渲染（内部会查找 renderer）
                            berDispatcher.renderEntity(
                                    be, matrices, consumers, light, OverlayTexture.DEFAULT_UV);
                        }
                    }
                } catch (Throwable t) {
                    // BE 渲染失败不影响方块模型渲染
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
        }

        diagFrameCount++;
        logDiag("帧#{} 已渲染 {} 个自由方块", diagFrameCount, rendered[0]);
    }

    private static void logDiag(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLogMs < 1000L) return;
        lastDiagLogMs = now;
        PlaceAnywhereMod.LOGGER.info("[PA-Render] " + fmt, args);
    }
}
