package com.placeanywhere;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockInteractHandler;
import com.placeanywhere.core.FreeBlockInteractPayload;
import com.placeanywhere.core.FreeBlockLoadQueue;
import com.placeanywhere.core.FreeBlockNetworking;
import com.placeanywhere.core.FreeBlockNetworking.FreeBlockSyncPayload;
import com.placeanywhere.core.FreeBlocks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;








public class PlaceAnywhereMod implements ModInitializer {
    public static final String MOD_ID = "placeanywherecore";
    public static final Logger LOGGER = LoggerFactory.getLogger("Place Anywhere");

    private static final int SYNC_RADIUS = 8;

    @Override
    public void onInitialize() {
        LOGGER.info("[Place Anywhere] 初始化：小数坐标方块核心（palette + 数组索引存储）");


        PayloadTypeRegistry.playS2C().register(FreeBlockSyncPayload.ID, FreeBlockSyncPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(FreeBlockInteractPayload.ID, FreeBlockInteractPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(FreeBlockInteractPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> FreeBlockInteractHandler.handle(payload, player));
                });


        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
                FreeBlocks.registerCommand(dispatcher, registryAccess);
                FreeBlocks.registerDebugCommand(dispatcher);
        });




        ServerChunkEvents.CHUNK_LOAD.register((ServerWorld world, WorldChunk chunk) -> {
            long pos = chunk.getPos().toLong();
            var nbt = FreeBlockLoadQueue.poll(world, pos);
            if (nbt != null) {
                ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
                data.readNbt(nbt);

                if (!data.isEmpty()) {
                    FreeBlockNetworking.sendToTrackers(world, chunk.getPos(), data);
                }
            }
        });


        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ServerWorld world = player.getServerWorld();
            ChunkPos center = new ChunkPos(player.getBlockPos());
            for (int dx = -SYNC_RADIUS; dx <= SYNC_RADIUS; dx++) {
                for (int dz = -SYNC_RADIUS; dz <= SYNC_RADIUS; dz++) {
                    int cx = center.x + dx, cz = center.z + dz;
                    Chunk chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk instanceof WorldChunk wc) {
                        ChunkFreeData data = ((FreeBlockChunkAccess) wc).placeanywhere_freeData();
                        if (!data.isEmpty()) {
                            FreeBlockNetworking.sendToPlayer(player, new ChunkPos(cx, cz), data);
                        }
                    }
                }
            }
        });
    }
}

