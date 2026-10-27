package com.dwinovo.numen.plugins.litematica;

/**
 * 一次交回的格数上限,以及超了的时候"取哪几层装得下"。不碰 Litematica 与 Minecraft,纯算术。
 */
final class Layers {

    /**
     * 一次交回的格数上限,与 {@code numen.build.place} 等收一串格的函数一次收的上限一致(Numen 的 {@code Canvas.MAX_CELLS}):
     * 交回的值要能原样传给它们,多一格就传不进去。那个常量在 Numen 里,联动够不着,两处各写一个数。
     */
    static final int MAX_CELLS = 16384;

    private Layers() {}

    /**
     * 从最低一层起连着取,总格数不超过 {@code max} 的最多层数。
     *
     * @param counts 每层的格数,下标 0 是最低那一层
     * @return 装得下的层数;0 表示最低一层自己就超了上限,取不出来
     */
    static int fitting(int[] counts, int max) {
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            total += counts[i];
            if (total > max) {
                return i;
            }
        }
        return counts.length;
    }
}
