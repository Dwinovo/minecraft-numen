package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.resources.ResourceLocation;

/** 语言自带的几种值的转换:字符串、整数、小数、布尔、不返回值、资源 id。登记在 {@link LuaCodecs}。 */
final class Builtin {

    private Builtin() {}

    /** 一个字符串。 */
    static final Codec<String> STRING = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.STRING;
        }

        @Override
        public String decode(Object value) {
            if (value instanceof String s) {
                return s;
            }
            throw new BadValue("expected a string; got " + BadValue.given(value));
        }

        @Override
        public Object encode(String value) {
            return value;
        }
    };

    /** 一个整数(int)。 */
    static final Codec<Integer> INTEGER = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.INTEGER;
        }

        @Override
        public Integer decode(Object value) {
            long whole = whole(value);
            if (whole != (int) whole) {
                throw new BadValue("expected an integer within " + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE
                        + "; got " + BadValue.given(value));
            }
            return (int) whole;
        }

        @Override
        public Object encode(Integer value) {
            return (long) value;
        }
    };

    /** 一个整数(long)。 */
    static final Codec<Long> LONG = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.INTEGER;
        }

        @Override
        public Long decode(Object value) {
            return whole(value);
        }

        @Override
        public Object encode(Long value) {
            return value;
        }
    };

    /** 一个数。 */
    static final Codec<Double> DOUBLE = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.NUMBER;
        }

        @Override
        public Double decode(Object value) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            throw new BadValue("expected a number; got " + BadValue.given(value));
        }

        @Override
        public Object encode(Double value) {
            return value;
        }
    };

    /** 开关:{@code true} 或 {@code false}。 */
    static final Codec<Boolean> BOOLEAN = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.BOOLEAN;
        }

        @Override
        public Boolean decode(Object value) {
            if (value instanceof Boolean b) {
                return b;
            }
            throw new BadValue("expected true or false; got " + BadValue.given(value));
        }

        @Override
        public Object encode(Boolean value) {
            return value;
        }
    };

    /** 不返回值:脚本拿到 nil。 */
    static final Codec<Void> NOTHING = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.NOTHING;
        }

        @Override
        public Void decode(Object value) {
            return null;
        }

        @Override
        public Object encode(Void value) {
            return null;
        }
    };

    /**
     * 资源 id:配方、物品、方块、模组模型这类 {@code 命名空间:路径}。合法性用原版 {@link ResourceLocation} 自己的规则,不写命名空间就是
     * {@code minecraft:},和原版指令一样。
     */
    static final Codec<ResourceLocation> ID = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.STRING;
        }

        @Override
        public ResourceLocation decode(Object value) {
            if (!(value instanceof String s)) {
                throw new BadValue("expected an id like \"minecraft:oak_log\" (minecraft: may be left out); got "
                        + BadValue.given(value));
            }
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id == null) {
                throw new BadValue("\"" + s + "\" is not a valid id: lowercase letters, digits, _ - . / and one :");
            }
            return id;
        }

        @Override
        public Object encode(ResourceLocation value) {
            return value.toString();
        }
    };

    /** 一种物品:它的 id(不写命名空间就是 {@code minecraft:});认不出的 id 读不成。 */
    static final Codec<net.minecraft.world.item.Item> ITEM = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.STRING;
        }

        @Override
        public net.minecraft.world.item.Item decode(Object value) {
            ResourceLocation id = ID.decode(value);
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                throw new BadValue("there is no item " + id);
            }
            return item;
        }

        @Override
        public Object encode(net.minecraft.world.item.Item value) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(value).toString();
        }
    };

    /** 一种方块:它的 id(不写命名空间就是 {@code minecraft:});认不出的 id 读不成。 */
    static final Codec<net.minecraft.world.level.block.Block> BLOCK = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.STRING;
        }

        @Override
        public net.minecraft.world.level.block.Block decode(Object value) {
            ResourceLocation id = ID.decode(value);
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new BadValue("there is no block " + id);
            }
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        }

        @Override
        public Object encode(net.minecraft.world.level.block.Block value) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(value).toString();
        }
    };

    /** 一个整数值:整数,或小数部分为零的数。 */
    private static long whole(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) < 9.0e15) {
            return (long) n.doubleValue();
        }
        throw new BadValue("expected a whole number; got " + BadValue.given(value));
    }
}
