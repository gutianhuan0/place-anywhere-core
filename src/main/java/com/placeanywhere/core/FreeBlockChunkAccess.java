package com.placeanywhere.core;

/**
 * Chunk 维度的自由方块数据访问接口，由 {@code com.placeanywhere.mixin.ChunkMixin}
 * 注入到原版 {@code LevelChunk} 上实现。
 *
 * 每个 Chunk 持有一个 {@link ChunkFreeData}，里面按 section 组织若干 {@link FreeBlockLayer}。
 */
public interface FreeBlockChunkAccess {
    /** 获取（必要时惰性创建）该 chunk 的自由方块数据容器。 */
    ChunkFreeData placeanywhere_freeData();

    /** 标记该 chunk 需要保存（脏标记，供序列化器判断）。 */
    void placeanywhere_markFreeDirty();

    /** 是否有自由方块数据需要写入存档。 */
    boolean placeanywhere_isFreeDirty();
}
