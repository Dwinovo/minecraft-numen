package com.dwinovo.numen.core.route;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.tools.RouteSpecFlags;
import com.dwinovo.numen.pathing.spec.RouteSpec;

/**
 * 路线上存的规格:她写的那一截路线标志原样存成文字(如 {@code --alter natural --avoid water}),{@code route show} 原样给她看;
 * 用时读回来经 {@link RouteSpecFlags} 翻成规格——标志到规格的翻译只有那一处。读与写都过 {@code route spec} 这一行命令:
 * 写是那一行的参数写回命令行的样子({@link CommandArgs#write}),读是同一棵树把它读回来({@link NumenCli#read}),文字的语法就是
 * 命令的语法,没有第二份。
 */
public final class RouteFlags {

    /** 读回存下的标志时借的那一行命令。 */
    static final String SPEC = Itinerary.GROUP + " spec";

    private RouteFlags() {}

    /** 这一行里写了的路线标志,写成命令行上的那一截;一个都没写是空串。 */
    public static String written(CommandArgs args) {
        return args.write("", RouteSpecFlags.PARAMS).strip();
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

    /** 一段走的规格:出厂值上叠整条的标志,再叠这一段另加的。 */
    public static RouteSpec spec(Itinerary route, int leg) {
        RouteSpec whole = RouteSpecFlags.parse(read(route.name(), route.flags()), RouteSpec.defaults());
        return RouteSpecFlags.parse(read(route.name(), route.legs().get(leg).flags()), whole);
    }

    private static CommandArgs read(String name, String flags) {
        return NumenCli.read(SPEC + " " + name + (flags.isEmpty() ? "" : " " + flags)).args();
    }

    private static <T> CommandArgs copied(Param<T> param, CommandArgs from, CommandArgs to) {
        T value = from.get(param);
        return value == null ? to : to.with(param, value);
    }
}
