package com.dwinovo.numen.api.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 一排宽度各异的小按钮在给定宽度里折行:按顺序往行里放,放不下就另起一行。
 * 只回答"哪几个在哪一行",居中、取宽、画都归调用方。
 */
public final class ChipFlow {

    private ChipFlow() {}

    /**
     * 每行装哪几个(按下标,保持原顺序)。同一行里相邻两个隔 {@code gap};一个就比 {@code maxWidth} 宽的也独占一行,
     * 不丢也不截。
     */
    public static List<List<Integer>> rows(int[] widths, int gap, int maxWidth) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            int need = row.isEmpty() ? widths[i] : used + gap + widths[i];
            if (!row.isEmpty() && need > maxWidth) {
                rows.add(row);
                row = new ArrayList<>();
                need = widths[i];
            }
            row.add(i);
            used = need;
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }
}
