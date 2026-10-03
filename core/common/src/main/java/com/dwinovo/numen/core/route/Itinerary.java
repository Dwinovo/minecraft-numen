package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.cli.Names;
import com.dwinovo.numen.core.task.move.Destination;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * 一条路线,命令组 {@code route} 的那个名词:意图——名字、在哪个维度、一串路段(每段是去一个途经点的那一截,途经点是去处的写法
 * 加到达方式,最后一段的途经点是终点)、整条与每段的规格——加上最近一次计划({@link Plan})与走过的记录({@link Walk})。
 * 叫 Itinerary 而不叫 Route,是为了和寻路模块推导出来的一步步的路({@code search.Route})分开:那个不存,这里存的是意图与计划。
 * 不存起点:从哪儿出发都行,计划里记着那一次是从哪儿规划的。
 *
 * <p>规格存的是她写的那几个路线标志,按命令行的写法存成一截文字(如 {@code --alter natural --avoid water});给她看时写成选项表
 * ({@link RouteFlags#shown}),用时经 {@link RouteFlags} 翻成规格。
 * 改意图(加减途经点、改规格)的每一步都丢掉计划:计划是对着旧意图做的。
 *
 * @param flags 整条的规格标志;没写是空串
 * @param plan  最近一次计划;还没规划过、或改过意图之后为 null
 * @param walks 走过的记录,旧的在前,最多 {@link #WALKS_KEPT} 条
 */
public record Itinerary(String name, ResourceLocation dimension, List<Leg> legs, String flags, Plan plan,
                        List<Walk> walks) {

    /** 命令组的名字:路线的写法都是这一组的命令。 */
    public static final String GROUP = "route";
    /** 走过的记录留几条:{@code move.goto_} 每次都走她那条匿名路线,记录不能无限长;几条足够看出这条路最近走不走得通。 */
    static final int WALKS_KEPT = 8;

    /**
     * 一段:去 {@code to} 的那一截,连同只管这一段的规格标志。
     *
     * @param flags 这一段另加的规格标志,叠在整条的之上;没写是空串
     */
    public record Leg(Destination.Stop to, String flags) {

        static final Codec<Leg> CODEC = RecordCodecBuilder.create(i -> i.group(
                Destination.Stop.CODEC.fieldOf("to").forGetter(Leg::to),
                Codec.STRING.fieldOf("flags").forGetter(Leg::flags)
        ).apply(i, Leg::new));
    }

    /**
     * 走过一次。
     *
     * @param who     谁走的(同伴的名字)
     * @param at      何时出发(主世界游戏刻)
     * @param ticks   实际走了多少刻
     * @param arrived 走到了终点
     * @param end     怎么收场的,一截给人看的话
     */
    public record Walk(String who, long at, long ticks, boolean arrived, String end) {

        static final Codec<Walk> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("who").forGetter(Walk::who),
                Codec.LONG.fieldOf("at").forGetter(Walk::at),
                Codec.LONG.fieldOf("ticks").forGetter(Walk::ticks),
                Codec.BOOL.fieldOf("arrived").forGetter(Walk::arrived),
                Codec.STRING.fieldOf("end").forGetter(Walk::end)
        ).apply(i, Walk::new));
    }

    static final Codec<Itinerary> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Itinerary::name),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(Itinerary::dimension),
            Leg.CODEC.listOf().fieldOf("legs").forGetter(Itinerary::legs),
            Codec.STRING.fieldOf("flags").forGetter(Itinerary::flags),
            Plan.CODEC.optionalFieldOf("plan").forGetter(r -> Optional.ofNullable(r.plan())),
            Walk.CODEC.listOf().fieldOf("walks").forGetter(Itinerary::walks)
    ).apply(i, (name, dimension, legs, flags, plan, walks) -> new Itinerary(name, dimension, legs, flags,
            plan.orElse(null), walks)));

    public Itinerary {
        Names.checked("route", name);
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("a route goes somewhere: it needs at least its destination");
        }
        legs = List.copyOf(legs);
        walks = List.copyOf(walks);
    }

    /**
     * 她自己的那条匿名路线叫什么:{@code move.goto_} 每次都把这一趟写成它再走,每个同伴一条、名字固定({@code goto-aria}),
     * 失败回执里改它的下一步照抄这个名字。同伴的名字是玩家名的字符(字母、数字、下划线),小写后合名字的规矩({@link Names})。
     */
    public static String gotoOf(String companionName) {
        return "goto-" + companionName.toLowerCase(java.util.Locale.ROOT);
    }

    /** 新的一条:只有终点一段,还没规划过、没走过。 */
    public static Itinerary of(String name, ResourceLocation dimension, Destination.Stop to, String flags) {
        return new Itinerary(name, dimension, List.of(new Leg(to, "")), flags, null, List.of());
    }

    /** 终点。 */
    public Destination.Stop destination() {
        return legs.get(legs.size() - 1).to();
    }

    /**
     * 插一个途经点,成为第 {@code at} 个(从 1 数);比途经点数多一就是接在终点后面,成了新的终点。原来去它后面那个点的一段
     * 规格照旧——一段认的是它去哪儿。新的一段没有另加的规格。
     */
    public Itinerary via(Destination.Stop stop, int at) {
        checkStop(at, legs.size() + 1);
        List<Leg> next = new ArrayList<>(legs);
        next.add(at - 1, new Leg(stop, ""));
        return new Itinerary(name, dimension, next, flags, null, walks);
    }

    /** 删掉第 {@code n} 个途经点(从 1 数);终点删不掉,删整条是另一回事。 */
    public Itinerary dropVia(int n) {
        checkStop(n, legs.size());
        if (n == legs.size()) {
            throw new IllegalArgumentException("stop " + n + " is the destination of " + name + ", not a waypoint; "
                    + "route.delete(\"" + name + "\") removes the whole route");
        }
        List<Leg> next = new ArrayList<>(legs);
        next.remove(n - 1);
        return new Itinerary(name, dimension, next, flags, null, walks);
    }

    /** 换整条的规格标志。 */
    public Itinerary withFlags(String flags) {
        return new Itinerary(name, dimension, legs, flags, null, walks);
    }

    /** 换第 {@code n} 段(从 1 数)另加的规格标志。 */
    public Itinerary withLegFlags(int n, String flags) {
        checkLeg(n);
        List<Leg> next = new ArrayList<>(legs);
        next.set(n - 1, new Leg(legs.get(n - 1).to(), flags));
        return new Itinerary(name, dimension, next, this.flags, null, walks);
    }

    /** 记下这一份计划(替掉旧的)。 */
    public Itinerary planned(Plan plan) {
        return new Itinerary(name, dimension, legs, flags, plan, walks);
    }

    /** 记下走过一次;只留最近的 {@link #WALKS_KEPT} 条。 */
    public Itinerary walked(Walk walk) {
        List<Walk> next = new ArrayList<>(walks);
        next.add(walk);
        while (next.size() > WALKS_KEPT) {
            next.remove(0);
        }
        return new Itinerary(name, dimension, legs, flags, plan, next);
    }

    /**
     * 反着的一条,叫 {@code as}:途经点倒过来,终点是这一条上次规划时的起点(站在那一格就算到);每段另加的规格跟着它那一截
     * 换到反向的那一段上,整条的规格照抄。没有计划、记录。
     *
     * @throws IllegalArgumentException 还没规划过:不知道这条从哪儿起
     */
    public Itinerary reversed(String as) {
        if (plan == null) {
            throw new IllegalArgumentException("route " + name + " has no plan, so where it starts is not known: a route"
                    + " starts wherever it was last planned from. Run `route.plan(\"" + name + "\")` where it should "
                    + "start, then reverse it");
        }
        int n = legs.size();
        List<Leg> back = new ArrayList<>(n);
        for (int j = 1; j <= n; j++) {
            // 反向第 j 段是原来第 n+1-j 段倒着走:去原来那一段的起点(第 n-j 个途经点,或整条的起点)
            Destination.Stop to = j < n ? legs.get(n - j - 1).to()
                    : new Destination.Stop(plan.from().getX(), plan.from().getY(), plan.from().getZ(),
                            Destination.Arrive.AT, null);
            back.add(new Leg(to, legs.get(n - j).flags()));
        }
        return new Itinerary(as, dimension, back, flags, null, List.of());
    }

    private void checkLeg(int n) {
        if (n < 1 || n > legs.size()) {
            throw new IllegalArgumentException("route " + name + " has " + legs.size() + " leg(s); leg " + n
                    + " is not one of them");
        }
    }

    private void checkStop(int n, int most) {
        if (n < 1 || n > most) {
            throw new IllegalArgumentException("route " + name + " has " + legs.size() + " stop(s), so the place is 1 to "
                    + most + ", not " + n);
        }
    }
}
