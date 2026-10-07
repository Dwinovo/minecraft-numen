package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.WorldView;

/**
 * 搜索读的世界:派发那一刻的只读快照({@link WorldSnapshot}),外加哪些列在快照里。快照之外的列读出来是空气,那不是
 * 测量,所以搜索碰到它们就停在边上,不往里走。
 */
public interface SearchView extends WorldView {

    /** {@code (x, z)} 这一列所在的区块在快照里。 */
    boolean isLoaded(int x, int z);
}
