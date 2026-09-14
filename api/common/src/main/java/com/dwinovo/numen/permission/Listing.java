package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 点名一堆格子的说法:{@code 2 oak_planks (120,64,-33; 120,65,-33)}。路线账单与征询清单
 * 共用这一份——模型在回执里、主人在卡片上看到的同一种东西长一个样。
 */
public final class Listing {

    /** 每一堆最多点名多少个坐标,其余计数——清单是给人判断的,不是给人数的。 */
    public static final int COORDS_NAMED = 6;

    private Listing() {}

    /**
     * 一堆:数量、名字、括号里的坐标(最多 {@link #COORDS_NAMED} 个,其余 {@code +N more})。
     * 没有坐标的(丢弃)只报数量与名字。
     */
    public static String part(String name, int count, List<BlockPos> cells) {
        StringBuilder sb = new StringBuilder().append(count).append(' ').append(name);
        if (cells.isEmpty()) {
            return sb.toString();
        }
        sb.append(" (");
        for (int i = 0; i < Math.min(COORDS_NAMED, cells.size()); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            BlockPos p = cells.get(i);
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        if (cells.size() > COORDS_NAMED) {
            sb.append("; +").append(cells.size() - COORDS_NAMED).append(" more");
        }
        return sb.append(')').toString();
    }
}
