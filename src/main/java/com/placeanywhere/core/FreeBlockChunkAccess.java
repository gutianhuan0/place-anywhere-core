package com.placeanywhere.core;

public interface FreeBlockChunkAccess {

    ChunkFreeData placeanywhere_freeData();

    void placeanywhere_markFreeDirty();

    boolean placeanywhere_isFreeDirty();
}
