package com.placeanywhere.client;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockNetworking;
import com.placeanywhere.core.FreeBlocks;
import com.placeanywhere.core.FreeBlocks.FreeBlockHit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.math.Matrix4f;

/**
 * 客户端入口（Forge 版）：注册自由方块的渲染钩子、描边。
 *
 * 渲染：在 AFTER_TRANSLUCENT_BLOCKS 阶段渲染真实贴图（见 FreeBlockDebugRenderer）。
 * 描边：在同一阶段对自由方块做 raycast，命中则画青色描边。
 * 网络同步接收由 FreeBlockNetworking.SyncPacket.handle 处理（已注册在 SimpleChannel）。
 */
@Mod.EventBusSubscriber(modid = PlaceAnywhereMod.MOD_ID, value = Dist.CLIENT)
public class PlaceAnywhereClient {
    /** 玩家交互距离（与 MinecraftClientMixin 一致）。 */
    private static final double REACH = 6.0;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack matrices = event.getPoseStack();
        MultiBufferSource.BufferSource consumers = mc.renderBuffers().bufferSource();
        Vec3 cam = event.getCamera().getPosition();

        // 渲染自由方块模型 + BlockEntityRenderer
        FreeBlockDebugRenderer.render(matrices, consumers, cam, mc.level);

        // 描边：对自由方块做 raycast，命中则画青色描边
        if (mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition(event.getPartialTick());
        Vec3 end = eye.add(mc.player.getLookAngle().scale(REACH));
        var hit = FreeBlocks.raycast(mc.level, eye, end);
        if (hit.isPresent()) {
            drawFreeBlockOutline(matrices, consumers, cam, hit.get(), mc.level);
        }
    }

    /** 画自由方块的描边框（按方块真实 outline shape，旋转后画 OBB 线框）。 */
    private static void drawFreeBlockOutline(PoseStack matrices, MultiBufferSource consumers, Vec3 cam,
                                              FreeBlockHit fb, Level level) {
        VertexConsumer consumer = consumers.getBuffer(RenderType.lines());
        BlockPos bpos = fb.pos().toBlockPos();
        // 取方块的 outline shape（相对最小角）
        VoxelShape shape = fb.state().getShape(level, bpos);
        AABB localBox = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
        if (localBox == null) return;
        // 旋转 8 个角到世界坐标，再减相机
        double[][] c = FreeBlocks.rotatedCorners(localBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
        double[][] p = new double[8][3];
        for (int i = 0; i < 8; i++) {
            p[i][0] = c[i][0] - cam.x;
            p[i][1] = c[i][1] - cam.y;
            p[i][2] = c[i][2] - cam.z;
        }
        // OBB 的 12 条边
        int[][] edges = {
            {0,1},{2,3},{4,5},{6,7}, // 沿 X 的 4 条
            {0,2},{1,3},{4,6},{5,7}, // 沿 Y 的 4 条
            {0,4},{1,5},{2,6},{3,7}  // 沿 Z 的 4 条
        };
        matrices.pushPose();
        Matrix4f pose = matrices.last().pose();
        for (int[] e : edges) {
            float x1 = (float) p[e[0]][0], y1 = (float) p[e[0]][1], z1 = (float) p[e[0]][2];
            float x2 = (float) p[e[1]][0], y2 = (float) p[e[1]][1], z2 = (float) p[e[1]][2];
            consumer.vertex(pose, x1, y1, z1).color(0, 230, 255, 255).normal(1f, 0f, 0f)
                    .vertex(pose, x2, y2, z2).color(0, 230, 255, 255).normal(1f, 0f, 0f);
        }
        matrices.popPose();
    }
}
