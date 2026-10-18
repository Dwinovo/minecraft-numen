package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 库协议里的"权限"一种({@code Libraries.PERMISSION}):主人自己的规则与他名下每只同伴的模式。读的是 {@link RuleBook}
 * ——命令列规则读的同一份——写的是 {@link PermissionStore} 那几个公开方法,命令改规则调的也是它们;这里不判规则对不对,
 * 解析与教学式的错误归 {@link Groups#parse}。
 *
 * <p>谁能读写:调用者只摸得到自己那一份。{@link Ground} 是按发请求的玩家取的,别人的规则、别人的同伴不在它里面;
 * 管理员也一样——硬规则与未知算别人的开关是服主的服务器设置,这里只报开没开。
 *
 * <h2>整表</h2>
 * JSON 数组,每行一个对象,{@code row} 说是哪种:
 * <ul>
 *   <li>{@code "settings"}:{@code hardRules}(服主开着硬规则吗);</li>
 *   <li>{@code "mode"}:{@code companion}(UUID)、{@code name}、{@code mode}({@code ask}/{@code bypass}/{@code observe});</li>
 *   <li>{@code "rule"}:{@code layer}({@code hard}/{@code owner}/{@code factory})、{@code table}({@code deny}/{@code ask}/
 *       {@code allow})、{@code rule}(规范原文,带 {@code @同伴名} 的话在末尾)、{@code source}({@code owner}/{@code her},
 *       只有主人层)、{@code note}(她写的理由,可空)、{@code count}、{@code last}(最近命中的墙钟毫秒,没命中过为 0)。
 *       每层里按 deny、ask、allow 排开,主人层同一张表里按写的先后。</li>
 * </ul>
 *
 * <h2>操作</h2>
 * {@link #PUT} 加一行({@code table}、{@code rule}),{@link #DELETE} 删一行({@code table}、{@code rule} 原文,
 * 不按序号:她在两次读之间加了一行,序号就错位),{@link #RESET} 清空主人层,{@link #MODE} 设一只同伴的模式
 * ({@code companion}、{@code mode})。规则写错了,结局带着 {@link Rule.Mistake},界面按它的码翻成主人的语言。
 */
public final class PermissionLibrary {

    public static final String PUT = "put";
    public static final String DELETE = "delete";
    public static final String RESET = "reset";
    public static final String MODE = "mode";

    /** 每一层里表排开的次序:先拒绝,再询问,再允许——越上面越是"说了不行"。界面按这个次序分组。 */
    private static final List<Verdict.Kind> TABLE_ORDER = List.of(Verdict.Kind.DENY, Verdict.Kind.ASK, Verdict.Kind.ALLOW);

    /** 这位主人那一头:他的存档、服主的开关、他名下的同伴。 */
    public interface Ground {

        PermissionStore store();

        /** 服主开着硬规则吗({@link PermissionConfig#hardRules})。 */
        boolean hardRules();

        /** 主人名下的同伴,UUID 到名字,按名册顺序。 */
        Map<UUID, String> companions();
    }

    /**
     * 一次写的结局。
     *
     * @param problem 办不成的原因;办成为 null
     * @param mistake 办不成是因为规则写错了:错在哪;不是这个原因为 null
     */
    public record Outcome(String problem, Rule.Mistake mistake) {

        static final Outcome DONE = new Outcome(null, null);

        static Outcome problem(String problem) {
            return new Outcome(problem, null);
        }
    }

    private PermissionLibrary() {}

    /** 这位主人此刻的整表。 */
    public static JsonArray view(Ground ground) {
        JsonArray out = new JsonArray();
        JsonObject settings = new JsonObject();
        settings.addProperty("row", "settings");
        settings.addProperty("hardRules", ground.hardRules());
        out.add(settings);
        ground.companions().forEach((uuid, name) -> {
            JsonObject row = new JsonObject();
            row.addProperty("row", "mode");
            row.addProperty("companion", uuid.toString());
            row.addProperty("name", name);
            row.addProperty("mode", word(ground.store().modeOf(uuid)));
            out.add(row);
        });
        RuleBook book = RuleBook.of(ground.store(), ground.hardRules());
        book.hard().forEach(line -> out.add(ruleRow("hard", line)));
        for (Verdict.Kind table : TABLE_ORDER) {
            book.owner().get(table).forEach(line -> out.add(ruleRow("owner", line)));
        }
        for (Verdict.Kind table : TABLE_ORDER) {
            book.factory().get(table).forEach(line -> out.add(ruleRow("factory", line)));
        }
        return out;
    }

    private static JsonObject ruleRow(String layer, RuleBook.Line line) {
        JsonObject row = new JsonObject();
        row.addProperty("row", "rule");
        row.addProperty("layer", layer);
        row.addProperty("table", word(line.table()));
        row.addProperty("rule", line.rule().toString());
        if (line.source() != null) {
            row.addProperty("source", line.source().getSerializedName());
            row.addProperty("note", line.note());
        }
        row.addProperty("count", line.hit().count());
        row.addProperty("last", line.hit().lastMillis());
        return row;
    }

    /** 一次写。{@code list} 不经这里:整表只读。 */
    public static Outcome write(Ground ground, String op, JsonObject body) {
        PermissionStore store = ground.store();
        switch (op) {
            case PUT:
                return edit(body, (table, rule) -> {
                    store.add(table, rule);   // 已有一模一样的一行就是已经如愿,不算办不成
                    return Outcome.DONE;
                });
            case DELETE:
                return edit(body, (table, rule) -> {
                    int at = store.indexOf(table, rule);
                    if (at < 0) {
                        return Outcome.problem("no such row in " + word(table));
                    }
                    store.remove(table, at);
                    return Outcome.DONE;
                });
            case RESET:
                store.reset();
                return Outcome.DONE;
            case MODE: {
                UUID companion = uuid(text(body, "companion"));
                if (companion == null || !ground.companions().containsKey(companion)) {
                    return Outcome.problem("not one of your companions");
                }
                Mode mode = mode(text(body, "mode"));
                if (mode == null) {
                    return Outcome.problem("a mode is ask, bypass or observe");
                }
                store.setMode(companion, mode);
                return Outcome.DONE;
            }
            default:
                return Outcome.problem("unknown operation");
        }
    }

    /** 对一张表里的一行规则做一件事。 */
    private interface Edit {
        Outcome apply(Verdict.Kind table, Rule rule);
    }

    /** 请求里点名的表与规则取出来(规则经 {@link Groups#parse},写错了结局带着教学式的错误),交给 {@code edit}。 */
    private static Outcome edit(JsonObject body, Edit edit) {
        Verdict.Kind table = table(body);
        if (table == null) {
            return Outcome.problem("a rule goes in deny, ask or allow");
        }
        Rule rule;
        try {
            rule = Groups.parse(text(body, "rule"));
        } catch (Rule.Mistake mistake) {
            return new Outcome(mistake.getMessage(), mistake);
        }
        return edit.apply(table, rule);
    }

    private static String word(Verdict.Kind table) {
        return table.name().toLowerCase(Locale.ROOT);
    }

    private static String word(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static Verdict.Kind table(JsonObject body) {
        String word = text(body, "table");
        for (Verdict.Kind table : Verdict.Kind.values()) {
            if (word(table).equals(word)) {
                return table;
            }
        }
        return null;
    }

    /** {@link Mode#byName} 把不认识的当 ask,面板发来的名字写错了应当办不成,不是悄悄改成 ask。 */
    private static Mode mode(String word) {
        for (Mode mode : Mode.values()) {
            if (word(mode).equals(word)) {
                return mode;
            }
        }
        return null;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String text(JsonObject body, String key) {
        JsonElement e = body == null ? null : body.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
