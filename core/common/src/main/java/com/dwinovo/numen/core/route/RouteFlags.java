package com.dwinovo.numen.core.route;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.tools.RouteSpecFlags;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 路线上存的规格:她写的路线标志按命令行的写法存成一截文字(如 {@code --alter natural --avoid water}),{@code numen.route.show} 写成
 * 选项表给她看({@link #shown});
 * 用时读回来经 {@link RouteSpecFlags} 翻成规格——标志到规格的翻译只有那一处。读与写都过 {@code route spec} 这一行命令:
 * 写是那一行的参数写回命令行的样子({@link CommandArgs#write}),读是同一棵树把它读回来({@link NumenCli#read}),文字的语法就是
 * 命令的语法,没有第二份。
 */
public final class RouteFlags {

    /** 读回存下的标志时借的那一行命令。 */
    static final String SPEC = com.dwinovo.numen.api.NumenPlugins.NUMEN + " " + Itinerary.GROUP + " spec";

    private RouteFlags() {}

    /** 这一行里写了的路线标志,写成命令行上的那一截(存盘的写法);一个都没写是空串。 */
    public static String written(CommandArgs args) {
        return args.write("", RouteSpecFlags.PARAMS).strip();
    }

    /** 存下的那一截标志写成脚本里的选项表,给她看:{@code {alter = "natural", avoid = {"water"}}}。 */
    public static String shown(String flags) {
        return read("x", flags).options(RouteSpecFlags.PARAMS);
    }

    /**
     * 在存下的 {@code flags} 上改:{@code given} 里写了的每个标志换掉原来的值(一串值的标志整串换),没写的照旧。
     *
     * @param name 路线名(读回存下的标志借 {@code route spec <name>} 那一行)
     * @throws IllegalArgumentException 改完的规格意思不成立(方块不存在、标签是空的……),说法同 {@link RouteSpecFlags#parse}
     */
    public static String merged(String name, String flags, CommandArgs given) {
        CommandArgs now = read(name, flags);
        for (Param<?> p : RouteSpecFlags.PARAMS) {
            now = copied(p, given, now);
        }
        RouteSpecFlags.parse(now, RouteSpec.defaults());
        return written(now);
    }

    /**
     * 一段走的规格:底子上叠整条的标志,再叠这一段另加的。
     *
     * @param base 她的底子({@link #base});标志放不开它的禁令
     */
    public static RouteSpec spec(RouteSpec base, Itinerary route, int leg) {
        RouteSpec whole = RouteSpecFlags.parse(read(route.name(), route.flags()), base);
        return RouteSpecFlags.parse(read(route.name(), route.legs().get(leg).flags()), whole);
    }

    /**
     * 她每一段路的底子:出厂值,加上盖好的房子不挖——{@link Built} 记着、此刻还立着的格(她照设计放下的)哪个标志都放不开,路线
     * 只许绕开、从上面走或站在旁边够。房子是要留下来的东西,不是挡路的地形:{@code alter = "natural"} 许她挖开自然地形与她自己
     * 垫的料,路上挖掉一格墙,下一轮又得补上,建造就在原地来回打转。在主线程上调。
     */
    public static RouteSpec base(NumenPlayer her) {
        LongSet built = Built.of(her.getServer()).standingIn(her.serverLevel());
        if (built.isEmpty()) {
            return RouteSpec.defaults();
        }
        return RouteSpec.defaults().edit()
                .positions(PositionCosts.builder().forbid(PositionCosts.Use.DIG, built::contains).build()).build();
    }

    private static CommandArgs read(String name, String flags) {
        return NumenCli.read(SPEC + " " + name + (flags.isEmpty() ? "" : " " + flags)).args();
    }

    private static <T> CommandArgs copied(Param<T> param, CommandArgs from, CommandArgs to) {
        T value = from.get(param);
        return value == null ? to : to.with(param, value);
    }
}
