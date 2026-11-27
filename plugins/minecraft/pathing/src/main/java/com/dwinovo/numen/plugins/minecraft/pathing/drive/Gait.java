package com.dwinovo.numen.plugins.minecraft.pathing.drive;

import java.util.List;

import com.dwinovo.numen.plugins.minecraft.pathing.plan.CostModel;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.Maneuver;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.MoveKind;
import com.dwinovo.numen.plugins.minecraft.pathing.world.BodyStats;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Kinematics;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Stepping;

import net.minecraft.world.level.BlockGetter;

/**
 * 步态:一步怎么走——疾不疾跑、走完这一步带不带着冲劲进下一步还是收脚。疾跑与收脚全模块只在这里定,控制器照做;
 * 价钱那一头只问"这一步物理上跑不跑得起来"({@link Maneuver#runnable}),也是这里读的同一个事实。
 * 判据来自路线上相邻两步的事实与脚下的世界。收脚停得准、拐弯不侧滑({@link Steering}),所以落点再窄、前面要拐弯,都不用为了
 * "停不停得住"提前慢下来:
 * <ul>
 *   <li><b>疾跑</b>:身体跑得动(饱食度够),这一步物理上跑得起来({@link Maneuver#runnable}),路径两侧站得稳
 *       ({@link Stepping#flanked}:一侧是空着的落坑,疾跑时歪一点就踩空),就跑——起跳不打断疾跑(原版疾跑跳),上一格、下一级也跑
 *       (疾跑走出边沿,只在冲劲带进下一步时:要在落点上收脚的下一级不在跑动里;下落不在跑动里);跑酷要助跑({@link Maneuver#runUp})就跑。
 *       身体此刻饿不饿、撞没撞墙,由按键照原版客户端的规矩再管一道({@link com.dwinovo.numen.api.entity.Controls});</li>
 *   <li><b>带着冲劲进下一步</b>({@link Stride#flows}),否则这一步在落点上收脚:这一步没有水平方向(垫柱、向下挖),没有冲劲可带;下一步要先原地改地形、下一步贴着落坑、起步或
 *       落点不在地上(水里、攀着没有地面可借力)、这一步是自由下落(飘得太远)、下一步不是平地上的走法,都收脚;下一级与跑酷离地之后冲劲
 *       带着身子往前飘,空中转不了身,只有下一步朝同一个方向才接得住(冲过了头也还在路上);下一级冲出去要落在这一步的落点那一列的支撑上
 *       ({@link #landsOnFooting}),擦着边沿落下去认不出落在哪一步,就收脚。</li>
 * </ul>
 */
final class Gait {

    /** 一步的步态。 */
    record Stride(boolean sprint, boolean flows) {}

    private Gait() {}

    /**
     * 这一步的步态。先试疾跑:身体跑得动、路径两侧站得稳、物理上跑得起来,冲劲能带进下一步就连着跑,带不进去就跑到落点收脚;
     * 跑不了再退到走,走着能带进下一步就带。
     *
     * @param level    脚下的世界
     * @param model    成本模型:身体(运动学读它的速度)、跑不跑得动
     * @param previous 路线上紧接在这一步前面的一步(路线的头一步没有);看身体是不是沿着一条直线走进来的
     * @param steps    这一步,和路线上紧接着它的几步(够不够都行,最后一步没有下一步)
     */
    static Stride stride(BlockGetter level, CostModel model, Maneuver previous, List<Maneuver> steps) {
        Maneuver m = steps.get(0);
        List<Maneuver> following = steps.subList(1, steps.size());
        BodyStats body = model.body().stats();
        if (model.maySprint() && canRun(level, body, previous, m, following.isEmpty() ? null : following.get(0))) {
            if (flows(level, model, true, m, following)) {
                return new Stride(true, true);
            }
            // 在落点上收脚而疾跑:走出边沿的下一级不带冲劲就是走出去,没有"跑到落点再停"的疾跑
            if (m.kind() != MoveKind.DESCEND) {
                return new Stride(true, false);
            }
        }
        return new Stride(false, flows(level, model, false, m, following));
    }

    /**
     * 这一步能不能在跑动里走:跑酷要助跑就跑;平走、斜走、上一级、下一级要物理上跑得起来、路径两侧站得稳;下落、攀、游、垫、挖不在跑动里。
     * 下一级只有带着冲劲走出去({@link #stride} 里 {@code flows})才真的跑。
     */
    private static boolean canRun(BlockGetter level, BodyStats body, Maneuver before, Maneuver m, Maneuver after) {
        return switch (m.kind()) {
            case PARKOUR -> true;
            case WALK, DIAGONAL, ASCEND, DESCEND -> m.runnable() && flanked(level, body, before, m, after);
            case FALL, PILLAR, DOWNWARD, CLIMB, SWIM, TAKEOFF, FLY, LAND -> false;
        };
    }

    /** 疾跑({@code sprinting})或平走的冲劲走完 {@code m} 进下一步,接不接得住。 */
    private static boolean flows(BlockGetter level, CostModel model, boolean sprinting, Maneuver m, List<Maneuver> following) {
        Maneuver next = following.isEmpty() ? null : following.get(0);
        if (next == null || !m.heading().horizontal() || !next.edits().isEmpty() || !m.landing().grounded() || m.wading()
                || !next.start().grounded() || m.kind() == MoveKind.FALL) {
            return false;
        }
        boolean flat = switch (next.kind()) {
            // 下一步贴着落坑就不带
            case WALK, DIAGONAL, ASCEND -> flanked(level, model.body().stats(), m, next, following.size() > 1 ? following.get(1) : null);
            case DESCEND, FALL, PARKOUR -> true;
            case PILLAR, DOWNWARD, CLIMB, SWIM, TAKEOFF, FLY, LAND -> false;
        };
        if (!flat) {
            return false;
        }
        boolean sameWay = next.heading().dx() == m.heading().dx() && next.heading().dz() == m.heading().dz();
        return switch (m.kind()) {
            case DESCEND -> sameWay && landsOnFooting(model, sprinting, m, next);
            case PARKOUR -> sameWay;
            default -> true;
        };
    }

    /**
     * 带着冲劲走出边沿的下一级,落在这一步的落点上({@link Kinematics#dropLanding},连落地那一刻自己的水平挪动):身子还压着落点那一列的支撑,
     * 才认得出落在了这一步、才收得住。落过了这一列的外沿(再加半个身宽),身子擦着支撑的边沿落下一刻就滑出去——认步({@link Tracker})
     * 认不出它落在了哪一步,冲劲又没人收,一路滑下去。飘得再远也不能算作落在后面连着的下一级上:身子下落到这一步的落点那一层时,
     * 压着这一步的支撑就落在它上面,不会飘过去。下一步是平的(平走、上一级),落点那一列后面还连着下一步的脚下,落进下一列也行。
     */
    private static boolean landsOnFooting(CostModel model, boolean sprinting, Maneuver m, Maneuver next) {
        BodyStats body = model.body().stats();
        double half = body.width() / 2;
        double speed = sprinting ? Kinematics.sprintSpeed(body) : Kinematics.walkSpeed(body);
        double landing = Kinematics.dropLanding(body.gravity(), 0.5 + half, speed, Kinematics.pushAcceleration(body, sprinting),
                Kinematics.airAcceleration(sprinting), m.drop());
        boolean flatNext = next.kind() == MoveKind.WALK || next.kind() == MoveKind.ASCEND;
        return Double.isNaN(landing) || landing <= 1.5 + (flatNext ? 1 : 0) + half;
    }

    private static boolean flanked(BlockGetter level, BodyStats body, Maneuver before, Maneuver m, Maneuver after) {
        return Stepping.flanked(level, body, m.from(), m.start().feetY(), m.to(), m.landing().feetY(), m.heading().dx(), m.heading().dz(),
                inLine(before, m, after));
    }

    /**
     * 身体沿着一条直线走过这一步:前后两步和它朝同一个方向,都是平走、上一级、下一级(落脚的步子、跑酷、斜走、转弯处都不算)。
     * 直线上身体不会歪出中心线({@link Stepping#flanked})。
     */
    private static boolean inLine(Maneuver before, Maneuver m, Maneuver after) {
        return before != null && after != null && alongLine(before, m) && alongLine(m, after);
    }

    private static boolean alongLine(Maneuver a, Maneuver b) {
        return a.heading().dx() == b.heading().dx() && a.heading().dz() == b.heading().dz() && walkingKind(a) && walkingKind(b);
    }

    private static boolean walkingKind(Maneuver m) {
        return m.kind() == MoveKind.WALK || m.kind() == MoveKind.ASCEND || m.kind() == MoveKind.DESCEND;
    }
}
