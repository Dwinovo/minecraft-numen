package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 脚本里写类型用的名字,和对应的值转换:{@code string}、{@code integer}、{@code number}、{@code boolean}、{@code block}、{@code item}、
 * {@code id}(资源 id)、{@code pos}(一格),后面加 {@code []} 是一串。值转换就是 {@link LuaCodecs} 里的那一个——脚本函数的参数与返回
 * 和 Java 函数的写成同样的 Lua 值。
 *
 * <p>JavaScript 的数都是 double,返回的值要先落成值转换认的 Java 类型({@link #coerce}),才交给它。
 */
record ValueType(String name, Codec<Object> codec, UnaryOperator<Object> coerce) {

    static final List<String> NAMES = List.of("string", "integer", "number", "boolean", "block", "item", "id", "pos");

    /** 不交回值:返回什么都丢掉。 */
    static ValueType nothing(String namespace) {
        return new ValueType("nothing", cast(LuaCodecs.of(Void.class, namespace)), value -> null);
    }

    /**
     * @param text      类型名(见类说明),后面可以跟 {@code []}
     * @param namespace 现造类型的类名用哪个名字空间
     * @throws IllegalArgumentException 没有这个类型名
     */
    static ValueType parse(String text, String namespace) {
        if (text.endsWith("[]")) {
            ValueType item = parse(text.substring(0, text.length() - 2), namespace);
            return new ValueType(text, cast(LuaCodecs.listOf(item.codec)), value -> {
                List<?> list = (List<?>) value;
                return list == null ? null : list.stream().map(item.coerce).toList();
            });
        }
        return switch (text) {
            case "string" -> of(text, String.class, namespace, value -> value == null ? null : String.valueOf(value));
            case "integer" -> of(text, Integer.class, namespace, value -> value == null ? null : ((Number) value).intValue());
            case "number" -> of(text, Double.class, namespace, value -> value == null ? null : ((Number) value).doubleValue());
            case "boolean" -> of(text, Boolean.class, namespace, value -> value);
            case "block" -> of(text, Block.class, namespace, value -> value);
            case "item" -> of(text, Item.class, namespace, value -> value);
            case "id" -> of(text, ResourceLocation.class, namespace, value -> value);
            case "pos" -> of(text, BlockPos.class, namespace, value -> value);
            default -> throw new IllegalArgumentException("'" + text + "' is not a type a script function can use; the "
                    + "types are " + String.join(", ", NAMES) + ", each also as a list (string[])");
        };
    }

    private static ValueType of(String name, Class<?> type, String namespace, UnaryOperator<Object> coerce) {
        return new ValueType(name, cast(LuaCodecs.of(type, namespace)), coerce);
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> cast(Codec<?> codec) {
        return (Codec<Object>) codec;
    }
}
