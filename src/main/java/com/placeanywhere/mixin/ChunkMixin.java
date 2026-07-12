package com.placeanywhere.mixin;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 子系统1（区块存储格式）的挂载点：给每个 LevelChunk 注入一个 {@link ChunkFreeData} 字段，
 * 作为 palette + 数组索引存储的容器；并维护脏标记驱动保存。
 */
@Mixin(LevelChunk.class)
public class ChunkMixin implements FreeBlockChunkAccess {
    @Unique
    private ChunkFreeData placeanywhere_data;

    @Unique
    private boolean placeanywhere_dirty;

    @Override
    public ChunkFreeData placeanywhere_freeData() {
        if (placeanywhere_data == null) {
            LevelChunk self = (LevelChunk) (Object) this;
            placeanywhere_data = new ChunkFreeData(self.getPos().x, self.getPos().z);
        }
        return placeanywhere_data;
    }

    @Override
    public void placeanywhere_markFreeDirty() {
        placeanywhere_dirty = true;
        // 同时标记原版区块需要保存，确保 ChunkSerializer.write 会被调用
        ((LevelChunk) (Object) this).setUnsaved(true);
    }

    @Override
    public boolean placeanywhere_isFreeDirty() {
        return placeanywhere_dirty;
    }
}
