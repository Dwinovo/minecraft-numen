package com.dwinovo.numen.api.describe;

import com.dwinovo.numen.script.ScriptEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问一圈得到的回答({@link Descriptions#of})写成一行短话:{@code id[名字={键=值, 键=值}, 名字={…}]},没人说话就只有 id。清单、回执里
 * 一行要写下很多样东西的地方用它;完整的字段在交回的值里。它不认识任何一个字段,回答者说什么就照着写什么。
 *
 * <p>每个回答按登记先后,都写出来。值的写法:字符串带双引号,数与布尔原样,列表与表在花括号里、键不加引号;一张列表或表超过
 * {@link #SHOWN} 项只写前 {@link #SHOWN} 项,后面写还有几项({@code …+2}),套在里面的每一层都这样。给模型读的,不保证能当脚本读回来。
 */
public final class AnswerText {

    /** 一张列表或表最多写出几项。 */
    private static final int SHOWN = 3;

    private AnswerText() {}

    /**
     * @param id      这样东西的 id
     * @param answers 回答者的名字到它说的字段
     */
    public static String of(String id, Map<String, Map<String, Object>> answers) {
        if (answers.isEmpty()) {
            return id;
        }
        List<String> said = new ArrayList<>();
        answers.forEach((name, fields) -> said.add(name + "=" + value(fields)));
        return id + "[" + String.join(", ", said) + "]";
    }

    private static String value(Object value) {
        return switch (value) {
            case Map<?, ?> table -> {
                List<String> shown = new ArrayList<>();
                for (Map.Entry<?, ?> field : table.entrySet()) {
                    if (shown.size() == SHOWN) {
                        break;
                    }
                    shown.add(field.getKey() + "=" + value(field.getValue()));
                }
                yield braces(shown, table.size());
            }
            case List<?> list -> {
                List<String> shown = new ArrayList<>();
                for (Object item : list.subList(0, Math.min(SHOWN, list.size()))) {
                    shown.add(value(item));
                }
                yield braces(shown, list.size());
            }
            // 字符串、数、布尔照脚本的字面量写:字符串带双引号、里面的引号与换行转义,一个值不会断成两行
            case null, default -> ScriptEngine.IN_USE.value(value);
        };
    }

    /** 写出来的几项放进花括号;一共 {@code total} 项,没写出来的记个数。 */
    private static String braces(List<String> shown, int total) {
        if (total > shown.size()) {
            shown.add("…+" + (total - shown.size()));
        }
        return "{" + String.join(", ", shown) + "}";
    }
}
