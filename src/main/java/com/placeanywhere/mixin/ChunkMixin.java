package com.placeanywhere.mixin;

import com.placeanywhere.core.ChunkFreeData;
import com.placeanywhere.core.FreeBlockChunkAccess;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;





@Mixin(WorldChunk.class)
public class ChunkMixin implements FreeBlockChunkAccess {
    @Unique
    private ChunkFreeData placeanywhere_data;

    @Unique
    private boolean placeanywhere_dirty;

    @Override
    public ChunkFreeData placeanywhere_freeData() {
        if (placeanywhere_data == null) {
            WorldChunk self = (WorldChunk) (Object) this;
            placeanywhere_data = new ChunkFreeData(self.getPos().x, self.getPos().z);
            
            if (self.getWorld().isClient) {
                com.placeanywhere.client.PlaceAnywhereClient.applyPendingData(
                        self.getPos().x, self.getPos().z);
            }
        }
        return placeanywhere_data;
    }

    @Override
    public void placeanywhere_markFreeDirty() {
        placeanywhere_dirty = true;
        
        ((WorldChunk) (Object) this).setNeedsSaving(true);
    }

    @Override
    public boolean placeanywhere_isFreeDirty() {
        return placeanywhere_dirty;
    }
}
