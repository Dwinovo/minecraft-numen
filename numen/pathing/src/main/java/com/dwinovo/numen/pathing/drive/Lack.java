package com.dwinovo.numen.pathing.drive;

/**
 * 飞缺什么、没法(再)飞的原因,和相关的数。受理时当场拒绝与一趟中途收工用的是同一份:都是"此刻从这里飞不起来"的事实,
 * 怎么对模型说在宿主({@code NavText})。
 *
 * @param why  缺什么
 * @param need 要多少:烟花与耐久是下限({@link Glide#ROCKET_FLOOR}、{@link Glide#WEAR_FLOOR});找起飞点与降落点是找了多少格;太近是滑翔转入降落的距离;早落了地是再起飞的门槛
 * @param have 有多少:烟花、耐久、此刻离目标的距离;别的是 0
 */
public record Lack(Why why, int need, int have) {

    /** 缺什么。 */
    public enum Why {
        /** 创造飞行:身体不许飞({@code have} 是它穿着的鞘翅还剩的耐久,0 是没有)。 */
        NO_FLIGHT,
        /** 滑翔:没穿可用的鞘翅。 */
        NO_WINGS,
        /** 鞘翅耐久低于下限。 */
        WORN,
        /** 烟花少于下限,附近又没有能从边沿滑下去的地方。 */
        TOO_FEW_ROCKETS,
        /** 目标没有水平位置(只给高度):滑翔朝一个水平位置飞。 */
        NO_PLACE,
        /** 目标已经在转入降落的范围里({@link Glide#DESCENT_RANGE})。 */
        TOO_NEAR,
        /** 没进目标范围就落了地(迫降),离目标不超过再起飞的门槛:收工,{@code have} 是离目标的水平距离,{@code need} 是再起飞的门槛。 */
        SHORT,
        /** 附近没有头顶开阔、爬得上巡航高度的起飞点。 */
        NO_TAKEOFF,
        /** 起飞点有,可朝目标去的线都过不去前面的地形。 */
        NO_ROUTE,
        /** 目标附近没有安全的落点({@link Landings})。 */
        NO_LANDING
    }
}
