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










public class PlaceAnywhereClient implements ClientModInitializer {

    private static final double REACH = 6.0;



    private static final java.util.Map<Long, NbtCompound> pendingData = new java.util.concurrent.ConcurrentHashMap<>();


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


        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                FreeBlockDebugRenderer.render(context));


        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, hitResult) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return true;
            Vec3d eye = client.player.getEyePos();
            Vec3d end = eye.add(client.player.getRotationVec(1.0F).multiply(REACH));
            var hit = FreeBlocks.raycast(client.world, eye, end);
            if (hit.isEmpty()) return true;
            FreeBlockHit fb = hit.get();
            drawFreeBlockOutline(ctx, fb);
            return false;
        });


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

                    long key = (long) payload.chunkX() * 100000L + payload.chunkZ();
                    pendingData.put(key, payload.nbt().copy());
                    com.placeanywhere.PlaceAnywhereMod.LOGGER.info(
                            "[PA-Net] chunk=({},{}) 未就绪，缓存数据", payload.chunkX(), payload.chunkZ());
                }
            });
        });
    }


    private static void drawFreeBlockOutline(WorldRenderContext ctx, FreeBlockHit fb) {
        VertexConsumer consumer = ctx.consumers().getBuffer(RenderLayer.getLines());
        MatrixStack matrices = ctx.matrixStack();
        Vec3d cam = ctx.camera().getPos();

        net.minecraft.util.shape.VoxelShape shape = fb.state().getOutlineShape(ctx.world(), fb.pos().toBlockPos());
        Box localBox = shape.isEmpty() ? net.minecraft.util.shape.VoxelShapes.fullCube().getBoundingBox() : shape.getBoundingBox();
        if (localBox == null) return;

        double[][] c = FreeBlocks.rotatedCorners(localBox, fb.pos(), fb.qx(), fb.qy(), fb.qz(), fb.qw());
        double[][] p = new double[8][3];
        for (int i = 0; i < 8; i++) {
            p[i][0] = c[i][0] - cam.x;
            p[i][1] = c[i][1] - cam.y;
            p[i][2] = c[i][2] - cam.z;
        }

        int[][] edges = {
            {0,1},{2,3},{4,5},{6,7},
            {0,2},{1,3},{4,6},{5,7},
            {0,4},{1,5},{2,6},{3,7}
        };
        matrices.push();
        var pose = matrices.peek().getPositionMatrix();
        for (int[] e : edges) {
            float x1 = (float) p[e[0]][0], y1 = (float) p[e[0]][1], z1 = (float) p[e[0]][2];
            float x2 = (float) p[e[1]][0], y2 = (float) p[e[1]][1], z2 = (float) p[e[1]][2];

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
