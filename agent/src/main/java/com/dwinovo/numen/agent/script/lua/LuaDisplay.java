package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.lua.LuaSandbox;
import com.dwinovo.numen.agent.script.ScriptLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个值写成给模型读的样子:Lua 的字面量写法,但大的缩略。{@code print} 一张表、程序 {@code return} 的值、错误值里的表,进回执前都经这一处,
 * 缩略的判据只有这一份(数值在 {@link ScriptLimits} 的 {@code DISPLAY_*},理由写在那里)。纯函数,不认识 Numen 的任何概念。
 *
 * <p>缩略从不静默:列表或表超过阈值,只显示首尾几项,中间写明总数与怎么看更多;一段长文字截掉后面并写明总长;嵌套太深的写成
 * {@code {...}},末尾加一句说明。小的值原样,和 {@link LuaEngine#literal} 写的一样。
 */
final class LuaDisplay {

    private LuaDisplay() {}

    /** 嵌套太深而缩成了 {@code {...}} 的有没有:有,末尾补一句怎么看里面。 */
    private static final class Cuts {
        boolean deep;
    }

    /** 一个值(null、布尔、数、字符串、列表、名字到值的表)的显示。 */
    static String of(Object value) {
        Cuts cuts = new Cuts();
        String text = write(value, 0, cuts);
        return cuts.deep ? text + "\n[tables nested deeper than " + ScriptLimits.DISPLAY_DEPTH
                + " levels show as {...}; read a field and print that to see inside]" : text;
    }

    private static String write(Object value, int depth, Cuts cuts) {
        return switch (value) {
            case null -> "nil";
            case String s -> text(s);
            case Double d -> LuaSandbox.number(d);
            case Number n -> String.valueOf(n);
            case Boolean b -> String.valueOf(b);
            case List<?> list -> depth >= ScriptLimits.DISPLAY_DEPTH ? deep(cuts) : list(list, depth, cuts);
            case Map<?, ?> map -> depth >= ScriptLimits.DISPLAY_DEPTH ? deep(cuts) : map(map, depth, cuts);
            default -> throw new IllegalArgumentException("cannot write " + value + " in Lua");
        };
    }

    private static String deep(Cuts cuts) {
        cuts.deep = true;
        return "{...}";
    }

    /** 一段文字:太长的留开头,写明一共多少字。 */
    private static String text(String s) {
        if (s.length() <= ScriptLimits.DISPLAY_STRING_CHARS) {
            return LuaEngine.quote(s);
        }
        int cut = ScriptLimits.DISPLAY_STRING_CHARS;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;   // 不把一个代理对劈开
        }
        return LuaEngine.quote(s.substring(0, cut)) + "…(" + s.length() + " characters in all)";
    }

    private static String list(List<?> list, int depth, Cuts cuts) {
        int edge = ScriptLimits.DISPLAY_EDGE_ITEMS;
        List<String> parts = new ArrayList<>();
        if (list.size() <= ScriptLimits.DISPLAY_THRESHOLD) {
            list.forEach(item -> parts.add(write(item, depth + 1, cuts)));
        } else {
            list.subList(0, edge).forEach(item -> parts.add(write(item, depth + 1, cuts)));
            parts.add("…(" + list.size() + " items in all; index one with t[i], or filter in the program before you "
                    + "print)");
            list.subList(list.size() - edge, list.size()).forEach(item -> parts.add(write(item, depth + 1, cuts)));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    private static String map(Map<?, ?> map, int depth, Cuts cuts) {
        int edge = ScriptLimits.DISPLAY_EDGE_ITEMS;
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        List<String> parts = new ArrayList<>();
        if (entries.size() <= ScriptLimits.DISPLAY_THRESHOLD) {
            entries.forEach(e -> parts.add(field(e, depth, cuts)));
        } else {
            entries.subList(0, edge).forEach(e -> parts.add(field(e, depth, cuts)));
            parts.add("…(" + entries.size() + " fields in all; read one by its name, or loop over the table and filter "
                    + "before you print)");
            entries.subList(entries.size() - edge, entries.size()).forEach(e -> parts.add(field(e, depth, cuts)));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    private static String field(Map.Entry<?, ?> e, int depth, Cuts cuts) {
        return LuaEngine.key(String.valueOf(e.getKey())) + " = " + write(e.getValue(), depth + 1, cuts);
    }
}
