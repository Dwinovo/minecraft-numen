package com.dwinovo.numen.pathing.plan;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 前提成立的一步:从哪个节点到哪个节点、身体怎么过去、路上要改哪几格。代价只从这些事实与成本模型算出,执行的控制器
 * 也照这些事实去做。
 *
 * @param kind        走法
 * @param heading     前提按哪个方向判的:执行复核在活世界上按同一个方向再判一次
 * @param from        起步节点
 * @param start       身体起步时在 {@code from} 上怎么待着
 * @param to          落到的节点
 * @param landing     身体在 {@code to} 上怎么待着
 * @param jump        要起跳
 * @param runUp       要助跑:不跑起来跳不过去(跑酷落到第 4 列,或落点比起跳时高),只有跑酷有
 * @param sneak       要潜行(搭桥时站定点不中任何一个面,身子探出边沿才点得中)
 * @param wading      落到的节点泡在水里
 * @param submerged   这一步眼睛换不了气:起步或落定时眼睛泡在水里({@link Strides#submerged}),整步按憋着气算
 * @param speedFactor 脚下方块的步速系数(起步与落点两处的平均,灵魂沙、蜂蜜块慢)
 * @param drop        脚往下落了多高
 * @param fallDamage  落定时摔掉几点血({@link BodySnapshot#fallDamage}:按落差与脚踩的那一格);落进水里、不是站着落地为 0。
 *                    下一级、下落、斜走、向下挖才算;其余走法落差不到一格,原版落在哪种方块上都不疼,恒为 0
 * @param span        水平走了几列(跑酷是落点离起点的列数,其余是 1)
 * @param edits       要做的改动,按执行的先后
 * @param cells       身体这一步新进入的格(不含起步时已经占着的),{@link BlockPos#asLong} 编码
 * @param exposure    这些格与脚下那一格水平方向上紧挨着几格碰了会伤身的(岩浆、火、仙人掌……):挨着走没碰上,歪一点、
 *                    滑一下就碰上了
 * @param support     落到之后脚踩的那一格;不是站着为 null
 */
public record Maneuver(MoveKind kind, Heading heading, BlockPos from, Stance start, BlockPos to, Stance landing, boolean jump, boolean runUp,
                       boolean sneak, boolean wading, boolean submerged, double speedFactor, double drop, int fallDamage, int span, List<Edit> edits,
                       long[] cells, int exposure, BlockPos support) {

    public Maneuver {
        edits = List.copyOf(edits);
    }

    /**
     * 物理上跑得起来:站着起步、不潜行、不泡水、这一步不先在原地改地形(站着做完改动再起步,一步里加不到跑速)。能不能真跑还要看
     * 身体饿不饿,跑不跑、跑到哪一步收脚由步态定({@code Gait}),价钱按能跑算({@code Strides#pace})——两处读的是这一个事实。
     */
    public boolean runnable() {
        return start.grounded() && !sneak && !wading && edits.isEmpty();
    }

    /** 这一步改地形的格数(挖加放,开关门不算)。 */
    public int alterations() {
        int n = 0;
        for (Edit edit : edits) {
            if (edit.alters()) {
                n++;
            }
        }
        return n;
    }
}
