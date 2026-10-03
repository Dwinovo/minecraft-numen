package com.dwinovo.numen.pathing.plan;

/**
 * 一步走不成的原因。前提不成立时连同是哪一格一起交出;规划据此跳过这一步,执行复核据此停下并报出哪一格、哪一条。
 */
public enum Reason {
    /** 规格没开这种走法。 */
    DISABLED,
    /** 起步那一格身体站不住、攀不住、也浮不住。 */
    NOT_STANDING,
    /** 这种走法要身体处在别的状态(比如泡在水里跳不起来、挂在梯子上垫不了柱)。 */
    WRONG_STANCE,
    /** 落点托不住身体。 */
    NO_FOOTING,
    /** 身体放不下:这一格的碰撞箱挡着,又打不开、不许挖或这种走法不挖。 */
    NO_CLEARANCE,
    /** 坎太高:迈不上,也跳不上。 */
    TOO_HIGH,
    /** 摔不起:落上去掉的血身体受不起,或落差超过规格的无水落差。 */
    TOO_FAR_TO_FALL,
    /** 落点不是这种走法该到的高度(下一级的落点更深,或下落的落点只低一格)。 */
    WRONG_DROP,
    /** 跑酷紧挨着的那一列走得过去,不是空隙。 */
    NO_GAP,
    /** 这种走法要疾跑,而规格或身体不许。 */
    NO_SPRINT,
    /** 规格排除这种格子(岩浆、危险、流水、机关、易碎)。 */
    EXCLUDED,
    /** 从高处落上去会把它踩坏(耕地、海龟蛋)。 */
    TRAMPLES,
    /** 规格按位置或按方块种类禁止这样用这一格。 */
    FORBIDDEN,
    /** 要挖一格,这一趟不挖(规格的 {@code dig} 关着)。 */
    NO_DIGGING,
    /** 要放一块(垫柱、搭桥、倒水接坠落),这一趟不放(规格的 {@code place} 关着)。 */
    NO_PLACING,
    /** 要垫一块,身上没有料。 */
    NO_MATERIALS,
    /** 许可拒绝;理由随失败交出。 */
    DENIED,
    /** 许可要问主人,而这一趟把要问的格当墙(规格的 {@code consent} 关着)。 */
    NEEDS_CONSENT,
    /** 身体此刻的游戏模式动不了方块(冒险、旁观)。 */
    EDIT_RESTRICTED,
    /** 物理上挖不动(基岩这类)。 */
    UNBREAKABLE,
    /** 挖了邻格的液体会漏进来。 */
    WOULD_FLOOD,
    /** 挖了上面或旁边悬着的落沙会塌下来。 */
    WOULD_COLLAPSE,
    /** 冰挖掉会变成水。 */
    MELTS,
    /** 虫蚀方块,挖了钻出蠹虫。 */
    INFESTED,
    /** 在世界边界或建筑高度之外。 */
    OUT_OF_BOUNDS,
    /** 这一格放不进方块(已经有东西,又不能被顶替)。 */
    NOT_REPLACEABLE,
    /** 往这一格放方块没有能点的面。 */
    NO_FACE,
    /** 身体正占着这一格,不能往里放方块。 */
    OCCUPIED,
    /** 手够不着这一格。 */
    OUT_OF_REACH,
    /** 憋不住气:照身体此刻的氧气,从这一步起的这一段水下游不到换气的地方({@link Breath#lasts})。 */
    OUT_OF_BREATH
}
