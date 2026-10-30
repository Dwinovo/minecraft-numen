package com.dwinovo.numen.pathing.search;

/**
 * 端口:搜索看的表。搜索只问一句"时间到没到"({@link Timer#up}),每展开一个节点之前问一次;放不放弃从不问它
 * (一趟的收场只认"证明到不了"与节点总数,见 {@code drive.Allowance}),所以换一块表只改每次想多深,不改到不到得了的结论。
 *
 * <p>宿主经 {@link com.dwinovo.numen.pathing.api.Ports} 交进来,这是模块唯一的接缝:正式运行给 {@link #WALL};测试给
 * {@link #STEADY},按做了多少活折算,同一份搜索代码在测试里逐字节可复现,定出来的时限又与真机对得上。
 */
@FunctionalInterface
public interface SearchClock {

    /** 估价的预计算读一列(扫一遍整列的标志字、判种类)折多少纳秒:靶场实测,见 ADR 0029。 */
    long PER_COLUMN = 2_000;

    /** 按活量折算的表:展开一个节点(快判断、松弛)记多少纳秒。 */
    long PER_EXPANSION = 4_400;

    /** 按活量折算的表:取出一条候选做一次精确判断记多少纳秒。 */
    long PER_CHECK = 2_700;

    /** 墙钟每展开这么多个节点才看一次:读纳秒钟在 Windows 上比展开一个节点还贵(Baritone 同样每 64 个节点看一次)。 */
    int WALL_EVERY = 64;

    /** 墙钟:从开搜那一刻起的真实纳秒。 */
    SearchClock WALL = nanos -> {
        long start = System.nanoTime();
        return (expanded, checked) -> (expanded & (WALL_EVERY - 1)) == 0 && System.nanoTime() - start >= nanos;
    };

    /**
     * 按做了多少活折算的表,系数取靶场上实测的平均耗时:展开一个节点(快判断、松弛)4.4 微秒,取出一条候选做一次精确判断 2.7 微秒。
     * 依据:10-29 靶场 42 次整路线跑(最终常数),每条路线的搜索墙钟对"展开数、精确判断数"做无截距最小二乘;平均每展开一个节点
     * 伴随 2.4 次精确判断,合起来约 11 微秒。精确判断里多数是一读就不成立的(水里往岸上够、脚下整块落不下),所以平均比一次展开便宜。
     */
    SearchClock STEADY = counting(PER_EXPANSION, PER_CHECK, PER_COLUMN);

    /** 按做了多少活折算的表:每展开一个节点记 {@code perExpansion} 纳秒,每做一次精确判断记 {@code perCheck} 纳秒,不记估价的预计算。 */
    static SearchClock counting(long perExpansion, long perCheck) {
        return counting(perExpansion, perCheck, 0);
    }

    /** 同上,估价的预计算每读一列({@link Timer#precomputed})再记 {@code perColumn} 纳秒。 */
    static SearchClock counting(long perExpansion, long perCheck, long perColumn) {
        if (perExpansion <= 0 || perCheck < 0 || perColumn < 0) {
            throw new IllegalArgumentException("折算的纳秒数不对:" + perExpansion + "、" + perCheck + "、" + perColumn);
        }
        return nanos -> new Timer() {
            private long precomputed;

            @Override
            public void precomputed(int columns) {
                precomputed = (long) columns * perColumn;
            }

            @Override
            public boolean up(int expanded, int checked) {
                return precomputed + (long) expanded * perExpansion + (long) checked * perCheck >= nanos;
            }
        };
    }

    /** 开搜:从此刻起最多想 {@code nanos} 纳秒。 */
    Timer start(long nanos);

    /** 一次搜索的表。 */
    interface Timer {

        /** 估价的预计算读了 {@code columns} 列({@link Columns}):算在这次搜索想的时间里。墙钟自己在走,不用记;按活量折算的表要记。 */
        default void precomputed(int columns) {}

        /** 已经展开了 {@code expanded} 个节点、做了 {@code checked} 次精确判断,时间到了。 */
        boolean up(int expanded, int checked);
    }
}
