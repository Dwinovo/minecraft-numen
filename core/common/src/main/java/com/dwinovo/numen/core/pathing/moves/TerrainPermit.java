package com.dwinovo.numen.core.pathing.moves;

/**
 * 一次导航对地形的许可。走路本身从不改变世界:跳、游、爬、开门、驾船都不动一块方块;
 * 挖穿、垫路、搭柱、架桥只在调用方明确授予时开放。
 *
 * <p>这是<b>这一次移动的意图</b>,与 {@code NavSettings} 的服主总开关是两层:总开关是
 * 天花板(服主关了谁也挖不了),许可是这一次在天花板之下实际用到的部分。成本模型只读
 * {@link CalculationContext} 上折好的结果,不在别处再问一遍。
 *
 * <p>接近类动作(goto 默认、follow、追击、捡拾、摸实体)一律 {@link #PRESERVE}——它们的
 * 意图是"到那儿去",不是"改那儿"。挖矿、施工,以及模型显式 {@code may_alter_terrain} 的
 * goto 才是 {@link #TERRAFORM}。找不到不动地形的路时,导航层会探一条动地形的路,把要动的
 * 方块如实列给模型,由它决定要不要授权——判断归模型,引擎不替它猜"这是不是玩家的房子"。
 */
public enum TerrainPermit {
    /** 只走不改:任何挖掘与放置在成本模型里都是 INF。 */
    PRESERVE,
    /** 可改地形:挖穿、垫路、搭柱、架桥都可入路,受总开关与硬禁挖标签约束。 */
    TERRAFORM;

    public boolean mayAlter() {
        return this == TERRAFORM;
    }
}
