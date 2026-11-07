package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.WorldView;

/**
 * 滑翔与降落读的世界:在 {@link WorldView} 之上多答两件事——一列所在的区块加载了没有,和这一列最高的碰撞面。活世界是 {@link LiveWorld},
 * 单测里是固定地形夹具。
 */
public interface Terrain extends WorldView {

    /** {@code (x, z)} 这一列所在的区块已经加载。 */
    boolean isLoaded(int x, int z);

    /**
     * 这一列最高的碰撞面(站在上面脚的高度):第一个挡动作或有流体的方块之上(原版 {@code MOTION_BLOCKING} 高度图);没加载为建筑高度的底。
     */
    int top(int x, int z);
}
