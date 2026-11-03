package com.dwinovo.numen.pathing.drive;

/**
 * 滑翔缺什么、没法(再)飞的原因,和相关的数。受理时当场拒绝与一趟中途收工用的是同一份:都是"此刻从这里飞不起来"的事实,
 * 怎么对模型说在宿主({@code NavText})。
 *
 * @param why  缺什么
 * @param need 要多少:烟花与耐久是下限({@link Glide#ROCKET_FLOOR}、{@link Glide#WEAR_FLOOR});找起飞点与降落点是找了多少格;太近是滑翔落得下的最近距离
 * @param have 有多少:烟花、耐久、此刻离目标的距离;别的是 0
 */
public record Lack(Why why, int need, int have) {

    /** 缺什么。 */
    public enum Why {
        /** 不许飞,也没穿可用的鞘翅。 */
        NO_WINGS,
        /** 鞘翅耐久低于下限。 */
        WORN,
        /** 烟花少于下限,附近又没有能从边沿滑下去的地方。 */
        TOO_FEW_ROCKETS,
        /** 目标没有水平位置(只给高度):滑翔朝一个水平位置飞。 */
        NO_PLACE,
        /** 目标近得滑翔来不及升起再落下。 */
        TOO_NEAR,
        /** 附近没有头顶开阔、爬得上巡航高度的起飞点。 */
        NO_TAKEOFF,
        /** 起飞点有,可朝目标去的线都过不去前面的地形。 */
        NO_ROUTE,
        /** 目标附近没有站得住的干地降落。 */
        NO_LANDING
    }
}
