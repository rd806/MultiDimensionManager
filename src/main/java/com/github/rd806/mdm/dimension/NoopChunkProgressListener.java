package com.github.rd806.mdm.dimension;

import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

/**
 * 运行时创建维度时用的空进度监听器。
 * <p>
 * 不能给 ServerLevel 的构造器传 null：这个监听器会一路传到 ChunkMap，
 * 并在 ChunkMap.java:518/556/648 被无保护地解引用，null 会直接 NPE。
 * 它只用于世界加载时的进度条显示，空实现是安全的。
 */
public final class NoopChunkProgressListener implements ChunkProgressListener {

    public static final NoopChunkProgressListener INSTANCE = new NoopChunkProgressListener();

    private NoopChunkProgressListener() {
    }

    @Override
    public void updateSpawnPos(@NotNull ChunkPos center) {
    }

    @Override
    public void onStatusChange(@NotNull ChunkPos chunkPosition, @Nullable ChunkStatus newStatus) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
