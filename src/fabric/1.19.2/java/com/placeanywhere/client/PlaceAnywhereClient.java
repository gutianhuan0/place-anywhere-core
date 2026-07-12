package com.placeanywhere.client;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockNetworking;
import com.placeanywhere.core.FreeBlocks;
import com.placeanywhere.core.FreeBlocks.FreeBlockHit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 客户端入口：注册自由方块的渲染钩子、描边、网络接收器。
 *
 * 渲染：在 AFTER_TRANSLUCENT 阶段渲染真实贴图（见 FreeBlockDebugRenderer）。
 * 描边：BEFORE_BLOCK_OUTLINE 中对自由方块做 raycast，命中则画青色描边并取消原版。
 * 网络：接收服务端发来的自由方块快照，写入客户端 WorldChunk 的 ChunkFreeData。
 */
public class PlaceAnywhereClient implements ClientModInitializer {
    /** 玩家交互距离（与 ClientPlayerInteractionManagerMixin 一致）。 */
    private static final double REACH = 6.0;

    @Override
    public void onInitializeClient() {
        PlaceAnywhereMod.LOGGER.info("[Place Anywhere Core] 客户端初始化：渲染 + 描边 + 网络同步");

        // 渲染钩子（真实贴图）
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                FreeBlockDebugRenderer.render(context));

        // 描边：在原版描边前做自由方块 raycast，命中则自己画并取消原版
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, hitResult) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return true;
            Vec3d eye = client.player.getEyePos();
            Vec3d end = eye.add(client.player.getRotationVec(1.0F).multiply(REACH));
            var hit = FreeBlocks.raycast(client.world, eye, end);
            if (hit.isEmpty()) return true; // 未命中自由方块，放行原版描边
            FreeBlockHit fb = hit.get();
            drawFreeBlockOutline(ctx, fb);
            return false; // 已画自定义描边，取消原版
        });

        // 网络同步接收：服务端推送的 chunk 自由方块快照，覆盖写入客户端对应区块
        ClientPlayNetworking.registerGlobalReceiver(FreeBlockNetworking.SYNC_ID, (client, handler, buf, responseSender) -> {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            NbtCompound nbt = buf.readNbt();
            client.execute(() -> {
                if (client.world == null || nbt == null) return;
                com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                        "[PA-Net] 收到同步包 chunk=({},{}), nbt={}", chunkX, chunkZ, nbt);
                Chunk chunk = client.world.getChunk(chunkX, chunkZ);
                if (chunk instanceof WorldChunk wc) {
                    ChunkFreeData data = ((FreeBlockChunkAccess) wc).placeanywhere_freeData();
                    data.readNbt(nbt);
                }
            });
        });
    }

    /** 画自由方块的描边框（按方块真实 outline shape，旋转后画 OBB 线框）。 */
    private static void drawFreeBlockOutline(WorldRenderContext ctx, FreeBlockHit fb) {
        VertexConsumer consumer = ctx.consumers().getBuffer(RenderLayer.getLines());
        MatrixStack matrices = ctx.matrixStack();
        Vec3d cam = ctx.camera().getPos();
        // 取方块的 outline shape（相对最小角）
        net.minecraft.util.shape.VoxelShape shape = fb.state().getOutlineShape(ctx.world(), fb.pos().toBlockPos());
        Box localBox = shape.isEmpty() ? net.minecraft.util.shape.VoxelShapes.fullCube().getBoundingBox() : shape.getBoundingBox();
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
        matrices.push();
        var pose = matrices.peek().getPositionMatrix();
        for (int[] e : edges) {
            float x1 = (float) p[e[0]][0], y1 = (float) p[e[0]][1], z1 = (float) p[e[0]][2];
            float x2 = (float) p[e[1]][0], y2 = (float) p[e[1]][1], z2 = (float) p[e[1]][2];
            consumer.vertex(pose, x1, y1, z1).color(0.0f, 0.9f, 1.0f, 1.0f).normal(1f, 0f, 0f).vertex(pose, x2, y2, z2).color(0.0f, 0.9f, 1.0f, 1.0f).normal(1f, 0f, 0f);
        }
        matrices.pop();
    }
}
