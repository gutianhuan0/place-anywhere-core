package com.placeanywhere.client;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockNetworking.FreeBlockSyncPayload;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.nbt.NbtCompound;

/**
 * 客户端入口：注册自由方块的渲染钩子、描边、网络接收器。
 *
 * 渲染：在 AFTER_TRANSLUCENT 阶段渲染真实贴图（见 FreeBlockDebugRenderer）。
 * 描边：BEFORE_BLOCK_OUTLINE 中对自由方块做 raycast，命中则画青色描边并取消原版。
 * 网络：接收服务端发来的自由方块快照，写入客户端 WorldChunk 的 ChunkFreeData。
 *       如果收到数据包时 chunk 尚未加载为 WorldChunk，缓存到 pendingData，
 *       在 chunk 加载完成后应用。
 */
public class PlaceAnywhereClient implements ClientModInitializer {
    /** 玩家交互距离（与 ClientPlayerInteractionManagerMixin 一致）。 */
    private static final double REACH = 6.0;

    /** 缓存尚未应用的 chunk 数据（chunk 未就绪时暂存）。
     *  key: chunkX * 100000 + chunkZ（简化为 long key） */
    private static final java.util.Map<Long, NbtCompound> pendingData = new java.util.concurrent.ConcurrentHashMap<>();

    /** 尝试应用缓存的待处理数据到指定 chunk。 */
    public static void applyPendingData(int chunkX, int chunkZ) {
        long key = (long) chunkX * 100000L + chunkZ;
        NbtCompound pending = pendingData.remove(key);
        if (pending != null && MinecraftClient.getInstance().world != null) {
            Chunk chunk = MinecraftClient.getInstance().world.getChunk(chunkX, chunkZ);
            if (chunk instanceof WorldChunk wc) {
                ChunkFreeData data = ((FreeBlockChunkAccess) wc).placeanywhere_freeData();
                data.readNbt(pending);
                com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                        "[PA-Net] 应用缓存数据 chunk=({},{})", chunkX, chunkZ);
            }
        }
    }

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
        ClientPlayNetworking.registerGlobalReceiver(FreeBlockSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().world == null || payload.nbt() == null) return;
                com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                        "[PA-Net] 收到同步包 chunk=({},{}), nbt={}", payload.chunkX(), payload.chunkZ(), payload.nbt());
                Chunk chunk = context.client().world.getChunk(payload.chunkX(), payload.chunkZ());
                if (chunk instanceof WorldChunk wc) {
                    ChunkFreeData data = ((FreeBlockChunkAccess) wc).placeanywhere_freeData();
                    data.readNbt(payload.nbt());
                } else {
                    // chunk 尚未加载为 WorldChunk，缓存待后续应用
                    long key = (long) payload.chunkX() * 100000L + payload.chunkZ();
                    pendingData.put(key, payload.nbt().copy());
                    com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                            "[PA-Net] chunk=({},{}) 未就绪，缓存数据", payload.chunkX(), payload.chunkZ());
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
            // 用边的方向向量作为法线，避免固定法线导致某些视角下线条消失
            float ndx = x2 - x1, ndy = y2 - y1, ndz = z2 - z1;
            float nlen = (float) Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);
            if (nlen > 1e-6f) { ndx /= nlen; ndy /= nlen; ndz /= nlen; }
            else { ndx = 1f; ndy = 0f; ndz = 0f; }
            consumer.vertex(pose, x1, y1, z1).color(0.0f, 0.9f, 1.0f, 1.0f).normal(ndx, ndy, ndz)
                    .vertex(pose, x2, y2, z2).color(0.0f, 0.9f, 1.0f, 1.0f).normal(ndx, ndy, ndz);
        }
        matrices.pop();
    }
}
