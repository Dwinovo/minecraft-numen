package com.dwinovo.numen.core.pathing.moves;

/**
 * chunk 加载谓词:给定方块坐标的 XZ,回答其所在 chunk 是否已加载
 * (或已被快照捕获)。由调用方以 lambda 注入,后续接快照体系。
 */
@FunctionalInterface
public interface ChunkLoadedTest {

    /** 永远视为已加载(实时世界场景)。 */
    ChunkLoadedTest ALWAYS = (x, z) -> true;

    boolean isLoaded(int blockX, int blockZ);
}
