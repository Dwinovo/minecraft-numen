package com.dwinovo.numen.task.move;

import com.dwinovo.numen.api.task.Activity;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 一趟路怎样算到了:去哪儿({@link Where})加到了的样子({@link How})。给模型的只有两种说法——{@link #to}(站进去,或离它不超过几格)和
 * {@link #away}(离它至少几格);{@link How#USE}、{@link How#DIG}、{@link How#PLACE} 是动手的活够不着时替自己走过去用的
 * ({@link #use}、{@link #dig}、{@link #place}),判据就是动作自己那一处的到达目标。形状不成立(只给高度却要离开它)在建的时候就报,
 * 那是参数写错了;和世界有关的(那一格站不进去、没东西可用)在编成目标时说({@link Destination}),那是这一趟走不通。
 *
 * @param range {@link How#NEAR}、{@link How#BESIDE} 是离它不超过几格,{@link How#AWAY} 是离它至少几格;别的是 0
 */
public record Arrival(Where to, How how, int range) {

    /** 怎样算到了。 */
    public enum How {
        /** 站在那一格里(一列、一个高度;一堆格子里任意一格)。 */
        AT,
        /** 离它不超过 {@code range} 格。 */
        NEAR,
        /**
         * 没说距离,方块与实体站不进去,走到旁边:离它不超过 {@link #BESIDE} 格;掉在地上的东西是身体捡得到它的地方({@link ItemReach})。
         */
        BESIDE,
        /** 站在看得见那一格方块某一面、点得到它的地方,去用它。 */
        USE,
        /** 站在手够得着那一格方块的地方(挡着的可以挖开),去挖它。 */
        DIG,
        /** 站在手够得着往那一格里放方块的地方,不站进去,去往里放。 */
        PLACE,
        /** 离它至少 {@code range} 格。 */
        AWAY;

        /** 要的是一格方块,不是一列、一个高度或一只实体。 */
        boolean namesABlock() {
            return this == USE || this == DIG || this == PLACE;
        }
    }

    /** 没说距离时,方块与实体走到离它几格内:就在旁边。 */
    public static final int BESIDE = 2;
    /** 距离最多几格。 */
    public static final int MAX_RANGE = 64;

    public Arrival {
        if (to instanceof Where.Height && how != How.AT) {
            throw new IllegalArgumentException("a height {y = …} is a level to climb or descend to, not a place to stay "
                    + "close to or get away from: give it alone, or give a place ({x = …, z = …}, or a Pos)");
        }
        if (how.namesABlock() && (to instanceof Where.Column || to instanceof Where.Mob)) {
            throw new IllegalArgumentException("that arrival names one block — give its cell ({x = …, y = …, z = …}, "
                    + "or the Block itself), not " + (to instanceof Where.Mob ? "an entity" : "a column"));
        }
    }

    /**
     * {@code numen.move.walk}:没说距离,能站进去的格就站进去,方块与实体站不进去,走到旁边({@link #BESIDE});说了距离,走到离它不超过
     * 这么远。
     *
     * @param distance 没说为 null
     * @throws IllegalArgumentException 距离不在 1 到 {@value #MAX_RANGE},或给了高度还说了距离
     */
    public static Arrival to(Where to, Integer distance) {
        if (distance != null) {
            return new Arrival(to, How.NEAR, checked(distance));
        }
        return to.beside() ? new Arrival(to, How.BESIDE, BESIDE) : new Arrival(to, How.AT, 0);
    }

    /** {@code numen.move.away}:离 {@code from} 至少 {@code distance} 格。 */
    public static Arrival away(Where from, int distance) {
        return new Arrival(from, How.AWAY, checked(distance));
    }

    /** 站到看得见、点得到 {@code cell} 这一格方块的地方。 */
    public static Arrival use(BlockPos cell) {
        return new Arrival(new Where.Cell(cell, null), How.USE, 0);
    }

    /** 站到手够得着 {@code cells} 里最多格方块的地方(挡着的可以挖开)。 */
    public static Arrival dig(List<BlockPos> cells) {
        return new Arrival(cells.size() == 1 ? new Where.Cell(cells.get(0), null) : Where.Cells.of(cells), How.DIG, 0);
    }

    /** 站到手够得着往 {@code cells} 里放方块的地方,不站进格子里。 */
    public static Arrival place(List<BlockPos> cells) {
        return new Arrival(cells.size() == 1 ? new Where.Cell(cells.get(0), null) : Where.Cells.of(cells), How.PLACE, 0);
    }

    private static int checked(int distance) {
        if (distance < 1 || distance > MAX_RANGE) {
            throw new IllegalArgumentException("distance must be 1 to " + MAX_RANGE + ", got " + distance);
        }
        return distance;
    }

    /** 给模型看的一截:那一处,不是站进去时接上怎样算到了。 */
    public String words() {
        String where = to.words();
        return switch (how) {
            case AT -> where;
            case NEAR, BESIDE -> where + " (within " + range + ")";
            case USE -> where + " (to use it)";
            case DIG -> where + " (to dig it)";
            case PLACE -> where + " (to build into it)";
            case AWAY -> where + " (at least " + range + " away)";
        };
    }

    /** 给模型看的一句({@code MoveToTaskRecord.describe} 印的是它);主人看的是 {@link #activity()}。 */
    public String describe() {
        String where = to.words();
        return switch (how) {
            case AT -> "走向 " + where;
            case NEAR, BESIDE -> "走到 " + where + " " + range + " 格内";
            case USE -> "去用 " + where;
            case DIG -> "走到够得着 " + where + " 的地方";
            case PLACE -> "走到够得着 " + where + "、往里放方块的地方";
            case AWAY -> "离开 " + where + " " + range + " 格以外";
        };
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    public Activity activity() {
        String where = to.words();
        return switch (how) {
            case AT -> Activity.of("numen.activity.move_at", where);
            case NEAR, BESIDE -> Activity.of("numen.activity.move_near", where, range);
            case USE -> Activity.of("numen.activity.move_use", where);
            case DIG -> Activity.of("numen.activity.move_dig", where);
            case PLACE -> Activity.of("numen.activity.move_place", where);
            case AWAY -> Activity.of("numen.activity.move_away", where, range);
        };
    }
}
