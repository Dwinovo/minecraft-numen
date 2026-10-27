package com.dwinovo.numen.plugins.litematica;

/**
 * 一次交回的格数超了上限({@code LuaCodecs.MAX_CELLS})时的"取哪几层装得下"。不碰 Litematica 与 Minecraft,纯算术。
 */
final class Layers {

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
