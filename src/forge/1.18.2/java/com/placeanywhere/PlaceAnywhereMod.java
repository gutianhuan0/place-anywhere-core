package com.placeanywhere;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import com.placeanywhere.core.FreeBlockInteractHandler;
import com.placeanywhere.core.FreeBlockLoadQueue;
import com.placeanywhere.core.FreeBlockNetworking;
import com.placeanywhere.core.FreeBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Place Anywhere 主入口（Forge 版）。
 *
 * 设计思路参考 Create 的 Contraption / Valkyrien Skies 的 Ship：把"任意小数坐标方块"
 * 组织成独立于原版整数网格的额外数据层（FreeBlockLayer），叠加在每个 Chunk 之上，
 * 由六大子系统（存储/渲染/碰撞/射线/邻居更新/红石/NBT）通过 Mixin 接入。
 */
@Mod(PlaceAnywhereMod.MOD_ID)
public class PlaceAnywhereMod {
    public static final String MOD_ID = "placeanywhere";
    public static final Logger LOGGER = LoggerFactory.getLogger("Place Anywhere");
    /** 玩家加入时同步自由方块的区块半径。 */
    private static final int SYNC_RADIUS = 8;

    public PlaceAnywhereMod() {
        FreeBlockNetworking.register();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[Place Anywhere] 初始化：小数坐标方块核心（palette + 数组索引存储）");
    }

    /** 命令注册：作为放置自由方块的测试入口。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FreeBlocks.registerCommand(event.getDispatcher());
        FreeBlocks.registerDebugCommand(event.getDispatcher());
    }

    /** 区块加载完成时，把反序列化阶段排队的自由方块 NBT 应用到 LevelChunk 上。
     *  这样做的原因：ChunkSerializer.read 返回的是 ProtoChunk（还不是 LevelChunk），
     *  而自由方块数据容器挂在 LevelChunk 上（由 ChunkMixin 注入），所以需要中转队列。 */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!(event.getWorld() instanceof ServerLevel level)) return;
        long pos = chunk.getPos().toLong();
        var nbt = FreeBlockLoadQueue.poll(level, pos);
        if (nbt != null) {
            ChunkFreeData data = ((FreeBlockChunkAccess) chunk).placeanywhere_freeData();
            data.readNbt(nbt);
            // 区块加载后若含有自由方块，同步给正在跟踪它的玩家
            if (!data.isEmpty()) {
                FreeBlockNetworking.sendToTrackers(level, chunk.getPos(), data);
            }
        }
    }

    /** 玩家加入世界时，补发其周围已加载 chunk 的自由方块数据（初始同步）。 */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = (ServerLevel) player.getLevel();
        ChunkPos center = new ChunkPos(player.blockPosition());
        for (int dx = -SYNC_RADIUS; dx <= SYNC_RADIUS; dx++) {
            for (int dz = -SYNC_RADIUS; dz <= SYNC_RADIUS; dz++) {
                int cx = center.x + dx, cz = center.z + dz;
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk lc) {
                    ChunkFreeData data = ((FreeBlockChunkAccess) lc).placeanywhere_freeData();
                    if (!data.isEmpty()) {
                        FreeBlockNetworking.sendToPlayer(player, new ChunkPos(cx, cz), data);
                    }
                }
            }
        }
    }

    /** 服务端 tick：处理待恢复的整数网格位置（GUI 关闭后恢复）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FreeBlockInteractHandler.serverTick();
        }
    }
}
