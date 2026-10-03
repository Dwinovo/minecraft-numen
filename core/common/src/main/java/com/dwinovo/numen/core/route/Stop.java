package com.dwinovo.numen.core.route;

import java.util.Locale;
import java.util.Set;

import com.dwinovo.numen.agent.script.ScriptType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 路线描述里的一站:去哪儿({@link Target})、怎样算到了({@link Arrive})、到了停不停(途经点可以路过)。最后一站是终点,总要停下。
 * 形状不成立(只给高度却要用一格方块、{@code range} 配错了到达方式)在建的时候就报,那是参数写错了;和世界有关的(那一格站不进去、
 * 没东西可用)在规划时说,那是这一趟走不通。
 *
 * @param range   {@code near} 是离它不超过几格,{@code away} 是离它至少几格;别的到达方式是 0
 * @param through 路过:走进去就算到了,不停稳,接着走下一段
 */
public record Stop(Target to, Arrive arrive, int range, boolean through) {

    /** 怎样算到了。 */
    public enum Arrive {
        /** 站在那一格里(一列、一个高度;一堆格子里任意一格)。 */
        AT,
        /** 离它不超过 {@code range} 格。 */
        NEAR,
        /** 站在看得见那一格方块某一面、点得到它的地方,去用它。 */
        USE,
        /** 站在手够得着那一格方块的地方(挡着的可以挖开),去挖它。 */
        DIG,
        /** 站在手够得着往那一格里放方块的地方,不站进去,去往里放。 */
        PLACE,
        /** 离它至少 {@code range} 格。 */
        AWAY;

        /** 脚本里的写法。 */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 一格方块的到达:要的是一格,不是一列、一个高度或一只实体。 */
        boolean namesABlock() {
            return this == USE || this == DIG || this == PLACE;
        }
    }

    /** 选项 {@code arrive} 能写的几个。 */
    public static final String[] ARRIVE_WORDS = {"at", "near", "use", "dig", "place", "away"};
    /** {@code arrive = "near"} 不写 {@code range} 时停在几格内:3 格大致是"就在旁边"。 */
    public static final int DEFAULT_NEAR = 3;
    /** {@code arrive = "away"} 不写 {@code range} 时至少离开几格:8 格出了近战与苦力怕的引爆范围,也够看清它在干什么。 */
    public static final int DEFAULT_AWAY = 8;
    /** {@code range} 最多几格。 */
    public static final int MAX_RANGE = 64;

    /** 一站在脚本里的样子({@code stops} 的一项)。 */
    public static final ScriptType.Class CLASS = new ScriptType.Class("Stop",
            "One stop on the way, before the destination.", null, java.util.List.of(
                    ScriptType.field("to", Target.SCRIPT, "Where: the same kinds of place the destination takes."),
                    ScriptType.optional("type", ScriptType.choice(java.util.List.of("through", "stop")),
                            "through: pass it without stopping (the default); stop: come to rest there first."),
                    ScriptType.optional("arrive", ScriptType.choice(java.util.List.of(ARRIVE_WORDS)),
                            "What counts as there, as for the destination; default at."),
                    ScriptType.optional("range", ScriptType.INTEGER, "With near or away, as for the destination.")));

    public Stop {
        if (to instanceof Target.Height && arrive != Arrive.AT) {
            throw new IllegalArgumentException("a height {y = …} is a level to climb or descend to; arrive = \""
                    + arrive.word() + "\" needs a place ({x = …, z = …}, or {x = …, y = …, z = …})");
        }
        if (arrive.namesABlock() && (to instanceof Target.Column || to instanceof Target.Mob)) {
            throw new IllegalArgumentException("arrive = \"" + arrive.word() + "\" names one block — give its cell "
                    + "({x = …, y = …, z = …}, or the Block itself), not " + (to instanceof Target.Mob ? "an entity"
                    : "a column"));
        }
    }

    /**
     * 写下的一站:{@code arrive} 没写是 {@code at};{@code range} 只配 {@code near} 与 {@code away},没写是它们的默认。
     *
     * @throws IllegalArgumentException 形状不成立
     */
    public static Stop of(Target to, String arriveWord, Integer range, boolean through) {
        Arrive arrive = arriveWord == null ? Arrive.AT : Arrive.valueOf(arriveWord.toUpperCase(Locale.ROOT));
        if (range != null && arrive != Arrive.NEAR && arrive != Arrive.AWAY) {
            throw new IllegalArgumentException("range = " + range + " only goes with arrive = \"near\" (stop within it) "
                    + "or arrive = \"away\" (get at least that far); without them arrival is exact");
        }
        if (range != null && (range < 1 || range > MAX_RANGE)) {
            throw new IllegalArgumentException("range must be 1 to " + MAX_RANGE + ", got " + range);
        }
        int r = range != null ? range : arrive == Arrive.NEAR ? DEFAULT_NEAR : arrive == Arrive.AWAY ? DEFAULT_AWAY : 0;
        return new Stop(to, arrive, r, through);
    }

    /**
     * {@code stops} 的一项:{@code {to = …, type = "through"|"stop", arrive = …, range = …}}。
     *
     * @throws IllegalArgumentException 不是这个样子:缺 {@code to}、多了别的键、值写错
     */
    static Stop read(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("each stop is a table {to = …, type = \"through\"|\"stop\", arrive = …, "
                    + "range = …}");
        }
        JsonObject o = value.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!Set.of("to", "type", "arrive", "range").contains(key)) {
                throw new IllegalArgumentException("a stop takes to, type, arrive and range; '" + key + "' is not one "
                        + "of them");
            }
        }
        if (!o.has("to")) {
            throw new IllegalArgumentException("a stop needs to = the place it goes through");
        }
        String type = o.has("type") ? o.get("type").getAsString() : "through";
        if (!type.equals("through") && !type.equals("stop")) {
            throw new IllegalArgumentException("a stop's type is \"through\" or \"stop\", got \"" + type + "\"");
        }
        String arrive = o.has("arrive") ? o.get("arrive").getAsString() : null;
        if (arrive != null && !Set.of(ARRIVE_WORDS).contains(arrive)) {
            throw new IllegalArgumentException("a stop's arrive is one of " + String.join(", ", ARRIVE_WORDS)
                    + ", got \"" + arrive + "\"");
        }
        Integer range = null;
        if (o.has("range")) {
            JsonElement r = o.get("range");
            if (!r.isJsonPrimitive() || !r.getAsJsonPrimitive().isNumber() || r.getAsDouble() != Math.rint(r.getAsDouble())) {
                throw new IllegalArgumentException("a stop's range is a whole number of blocks, got " + r);
            }
            range = r.getAsInt();
        }
        return of(Target.read(o.get("to")), arrive, range, type.equals("through"));
    }

    /** 写回脚本给的样子({@code stops} 的一项)。 */
    JsonObject json() {
        JsonObject o = new JsonObject();
        o.add("to", to.json());
        o.addProperty("type", through ? "through" : "stop");
        o.addProperty("arrive", arrive.word());
        if (arrive == Arrive.NEAR || arrive == Arrive.AWAY) {
            o.addProperty("range", range);
        }
        return o;
    }

    /** 给模型看的一截:那一处,不是 at 时接上怎样算到了。 */
    public String words() {
        String where = to.words();
        return switch (arrive) {
            case AT -> where;
            case NEAR -> where + " (within " + range + ")";
            case USE -> where + " (to use it)";
            case DIG -> where + " (to dig it)";
            case PLACE -> where + " (to build into it)";
            case AWAY -> where + " (at least " + range + " away)";
        };
    }

    /** 给主人看的一句(头顶气泡、面板)。 */
    public String describe() {
        String where = to.words();
        return switch (arrive) {
            case AT -> "走向 " + where;
            case NEAR -> "走到 " + where + " " + range + " 格内";
            case USE -> "去用 " + where;
            case DIG -> "走到够得着 " + where + " 的地方";
            case PLACE -> "走到够得着 " + where + "、往里放方块的地方";
            case AWAY -> "离开 " + where + " " + range + " 格以外";
        };
    }
}
