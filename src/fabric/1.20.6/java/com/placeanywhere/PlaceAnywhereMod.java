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

/**
 * Place Anywhere 主入口。
 *
 * 设计思路参考 Create 的 Contraption / Valkyrien Skies 的 Ship：把"任意小数坐标方块"
 * 组织成独立于原版整数网格的额外数据层（FreeBlockLayer），叠加在每个 Chunk 之上，
 * 由六大子系统（存储/渲染/碰撞/射线/邻居更新/红石/NBT）通过 Mixin 接入。
 */
public class PlaceAnywhereMod implements ModInitializer {
    public static final String MOD_ID = "placeanywhere";
    public static final Logger LOGGER = LoggerFactory.getLogger("Place Anywhere");
    /** 玩家加入时同步自由方块的区块半径。 */
    private static final int SYNC_RADIUS = 8;

    @Override
    public void onInitialize() {
        LOGGER.info("[Place Anywhere] 初始化：小数坐标方块核心（palette + 数组索引存储）");

        // 注册自由方块同步 payload 的编解码器（S2C）
        PayloadTypeRegistry.playS2C().register(FreeBlockSyncPayload.ID, FreeBlockSyncPayload.CODEC);
        // 注册自由方块交互 payload 的编解码器（C2S）
        PayloadTypeRegistry.playC2S().register(FreeBlockInteractPayload.ID, FreeBlockInteractPayload.CODEC);
        // 服务端接收客户端的交互请求，交由 FreeBlockInteractHandler 处理
        ServerPlayNetworking.registerGlobalReceiver(FreeBlockInteractPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> FreeBlockInteractHandler.handle(payload, player));
                });

        // 命令注册：作为放置自由方块的测试入口
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
                FreeBlocks.registerCommand(dispatcher, registryAccess);
                FreeBlocks.registerDebugCommand(dispatcher);
        });

        // 区块加载完成时，把反序列化阶段排队的自由方块 NBT 应用到 WorldChunk 上。
        // 这样做的原因：ChunkSerializer.deserialize 返回的是 ProtoChunk（还不是 WorldChunk），
        // 而自由方块数据容器挂在 WorldChunk 上（由 ChunkMixin 注入），所以需要中转队列。
        ServerChunkEvents.CHUNK_LOAD.register((ServerWorld world, WorldChunk chunk) -> {
            long pos = chunk.getPos().toLong();
            var nbt = FreeBlockLoadQueue.poll(world, pos);
            if (nbt != null) {
                ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
                data.readNbt(nbt);
                // 区块加载后若含有自由方块，同步给正在跟踪它的玩家
                if (!data.isEmpty()) {
                    FreeBlockNetworking.sendToTrackers(world, chunk.getPos(), data);
                }
            }
        });

        // 玩家加入世界时，补发其周围已加载 chunk 的自由方块数据（初始同步）
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

