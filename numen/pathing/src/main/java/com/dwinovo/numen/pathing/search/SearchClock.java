package com.dwinovo.numen.pathing.search;

/**
 * 端口:搜索看的表。搜索只问一句"时间到没到"({@link Timer#up}),每展开一个节点之前问一次;放不放弃从不问它
 * (一趟的收场只认"证明到不了"与节点总数,见 {@code drive.Allowance}),所以换一块表只改每次想多深,不改到不到得了的结论。
 *
 * <p>宿主经 {@link com.dwinovo.numen.pathing.api.Ports} 交进来,这是唯一的接缝:正式运行给 {@link #WALL};测试给
 * {@link #counting},按展开的节点数折算,同一份搜索代码在测试里逐字节可复现。
 */
@FunctionalInterface
public interface SearchClock {

    /** 墙钟每展开这么多个节点才看一次:读纳秒钟在 Windows 上比展开一个节点还贵(Baritone 同样每 64 个节点看一次)。 */
    int WALL_EVERY = 64;

    /** 墙钟:从开搜那一刻起的真实纳秒。 */
    SearchClock WALL = nanos -> {
        long start = System.nanoTime();
        return expanded -> (expanded & (WALL_EVERY - 1)) == 0 && System.nanoTime() - start >= nanos;
    };

    /** 按展开的节点数折算的表:每展开一个节点记 {@code nanosPerNode} 纳秒。 */
    static SearchClock counting(long nanosPerNode) {
        if (nanosPerNode <= 0) {
            throw new IllegalArgumentException("每个节点记的纳秒要是正数:" + nanosPerNode);
        }
        return nanos -> expanded -> (long) expanded * nanosPerNode >= nanos;
    }

    /** 开搜:从此刻起最多想 {@code nanos} 纳秒。 */
    Timer start(long nanos);

    /** 一次搜索的表。 */
    @FunctionalInterface
    interface Timer {

        /** 已经展开了 {@code expanded} 个节点,时间到了。 */
        boolean up(int expanded);
    }
}
