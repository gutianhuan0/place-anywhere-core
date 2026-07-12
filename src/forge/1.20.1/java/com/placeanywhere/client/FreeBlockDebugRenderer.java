package com.placeanywhere.client;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.DecimalBlockPos;
import com.placeanywhere.core.FreeBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private static final RandomSource RAND = RandomSource.createNewThreadLocalInstance();

    private static long lastDiagLogMs = 0L;
    private static int diagFrameCount = 0;

    private FreeBlockDebugRenderer() {}

    public static void render(PoseStack matrices, MultiBufferSource.BufferSource consumers, Vec3 cam, Level level) {
        if (level == null || consumers == null || matrices == null) return;
        Minecraft mc = Minecraft.getInstance();

        AABB range = new AABB(cam.x - RANGE, cam.y - RANGE, cam.z - RANGE,
                cam.x + RANGE, cam.y + RANGE, cam.z + RANGE);

        BlockRenderDispatcher brm = mc.getBlockRenderer();
        BlockEntityRenderDispatcher berDispatcher = mc.getBlockEntityRenderDispatcher();
        int[] rendered = { 0 };

        matrices.pushPose();
        try {
            FreeBlocks.forEachPlaced(level, range, fb -> {
                BlockState state = fb.state();
                DecimalBlockPos pos = fb.pos();
                BlockPos bpos = pos.toBlockPos();
                int light = LevelRenderer.getLightColor(level, bpos);

                matrices.pushPose();
                matrices.translate(pos.x() - cam.x, pos.y() - cam.y, pos.z() - cam.z);
                Quaternionf q = new Quaternionf(fb.qx(), fb.qy(), fb.qz(), fb.qw());
                q.normalize();
                matrices.mulPose(q);

                // 1. 渲染方块模型（非 ENTITYBLOCK_ANIMATED 类型）
                if (state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) {
                    BakedModel model = brm.getBlockModel(state);
                    if (model != null) {
                        RenderType layer = ItemBlockRenderTypes.getRenderType(state, true);
                        VertexConsumer vc = consumers.getBuffer(layer);
                        brm.getModelRenderer().tesselateBlock(
                                level, model, state, bpos,
                                matrices, vc,
                                false, RAND,
                                0L, light);
                    }
                }

                // 2. 渲染 BlockEntityRenderer（箱子/木桶/潜影盒等）
                try {
                    if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock eb) {
                        BlockEntity be = eb.newBlockEntity(bpos, state);
                        if (be != null) {
                            be.setLevel(level);
                            // 如果有保存的 NBT，加载
                            if (fb.nbt() != null && !fb.nbt().isEmpty()) {
                                be.load(fb.nbt());
                            }
                            // 用 render 直接渲染（内部会查找 renderer）
                            berDispatcher.render(be, 0f, matrices, consumers);
                        }
                    }
                } catch (Throwable t) {
                    // BE 渲染失败不影响方块模型渲染
                }

                matrices.popPose();
                rendered[0]++;
            });

            consumers.endBatch();
        } catch (Throwable t) {
            PlaceAnywhereMod.LOGGER.error("[Place Anywhere] 渲染自由方块时出错", t);
        } finally {
            matrices.popPose();
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
