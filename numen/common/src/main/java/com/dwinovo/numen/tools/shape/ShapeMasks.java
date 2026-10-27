package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.build.BuildStates;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 蒙版(mask)的文字读成数据,蒙版的判定只在这里:写法照 WorldEdit,一格测的是世界里此刻的方块({@link LoadedBlocks}),所以只出现在读世界的
 * 操作上。空格隔开的几段是"且"。
 *
 * <ul>
 *   <li>{@code stone,dirt}:逗号分开的方块之一;没写的属性什么值都行,写了的要一样({@code oak_stairs[facing=north]});</li>
 *   <li>{@code ##logs}:这个标签里的方块;{@code ^[facing=north]}:有这些属性、值是这些的方块({@code ^=[…]}:没有这些属性的方块不算);</li>
 *   <li>{@code #existing}:不是空气;{@code #air};{@code #solid}:挡得住移动(原版 {@code blocksMotion});{@code #fullcube}:整格实心的形状;
 *       {@code #surface}(即 {@code #exposed}):不是空气、六个邻格里有空气;</li>
 *   <li>{@code !m}:不是 m;{@code >m}:脚下那格是 m;{@code <m}:头上那格是 m;{@code ~m}:六个邻格里有 m(缺省的 m 是 {@code #existing});</li>
 *   <li>{@code %n}:每格 n% 的机会。</li>
 * </ul>
 */
final class ShapeMasks {

    private ShapeMasks() {}

    /** 一个蒙版:这一格过不过。 */
    @FunctionalInterface
    interface Mask {

        boolean test(LoadedBlocks world, int x, int y, int z);
    }

    /** 一个蒙版往四周最远读几格:读之前世界的这一圈要先已加载。 */
    static int reach(String text) {
        return text.contains(">") || text.contains("<") || text.contains("~") || text.contains("#surface")
                || text.contains("#exposed") ? 1 : 0;
    }

    static final Mask EXISTING = (w, x, y, z) -> !w.at(x, y, z).isAir();
    static final Mask AIR = (w, x, y, z) -> w.at(x, y, z).isAir();

    /** @throws IllegalArgumentException 读不通(说是哪一段) */
    static Mask parse(String text) {
        List<Mask> all = new ArrayList<>();
        for (String component : text.trim().split(" +")) {
            if (!component.isEmpty()) {
                all.add(component(component));
            }
        }
        if (all.isEmpty()) {
            throw new IllegalArgumentException("'" + text + "' has no mask in it");
        }
        return (w, x, y, z) -> {
            for (Mask m : all) {
                if (!m.test(w, x, y, z)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static Mask component(String c) {
        switch (c) {
            case "#existing":
                return EXISTING;
            case "#air":
                return AIR;
            case "#solid":
                return (w, x, y, z) -> w.at(x, y, z).blocksMotion();
            case "#fullcube":
                return (w, x, y, z) -> Block.isShapeFullBlock(w.at(x, y, z).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            case "#exposed", "#surface":
                return (w, x, y, z) -> !w.at(x, y, z).isAir() && anyAround(AIR, w, x, y, z);
            default:
                break;
        }
        char first = c.charAt(0);
        if (first == '!') {
            Mask inner = rest(c);
            return (w, x, y, z) -> !inner.test(w, x, y, z);
        }
        if (first == '>' || first == '<') {
            Mask inner = c.length() > 1 ? rest(c) : EXISTING;
            int dy = first == '>' ? -1 : 1;
            return (w, x, y, z) -> inner.test(w, x, y + dy, z);
        }
        if (first == '~') {
            Mask inner = c.length() > 1 ? rest(c) : EXISTING;
            return (w, x, y, z) -> anyAround(inner, w, x, y, z);
        }
        if (first == '%') {
            double density;
            try {
                density = Integer.parseInt(c.substring(1)) / 100.0;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + c + "' is no chance: write % and a whole number, such as %25");
            }
            if (density < 0 || density > 1) {
                throw new IllegalArgumentException("'" + c + "' is no chance: it is between %0 and %100");
            }
            return (w, x, y, z) -> ThreadLocalRandom.current().nextDouble() <= density;
        }
        if (first == '=') {
            throw new IllegalArgumentException("'" + c + "': an expression is not a mask here; pick the cells with "
                    + "numen.shape.generate and a Lua function instead");
        }
        if (c.startsWith("##")) {
            HolderSet.Named<Block> tag = BlockPatterns.tag(c.substring(2));
            return (w, x, y, z) -> {
                BlockState s = w.at(x, y, z);
                return tag.contains(s.getBlockHolder());
            };
        }
        if (c.startsWith("^[") || c.startsWith("^=[")) {
            return states(c);
        }
        return blocks(c);
    }

    private static Mask rest(String c) {
        if (c.length() < 2) {
            throw new IllegalArgumentException("'" + c + "' has nothing after it to apply to");
        }
        return component(c.substring(1));
    }

    private static boolean anyAround(Mask m, LoadedBlocks w, int x, int y, int z) {
        return m.test(w, x, y - 1, z) || m.test(w, x, y + 1, z) || m.test(w, x - 1, y, z) || m.test(w, x + 1, y, z)
                || m.test(w, x, y, z - 1) || m.test(w, x, y, z + 1);
    }

    /** {@code stone,oak_stairs[facing=north]}:方块的类型一样,写了的属性也一样。 */
    private static Mask blocks(String c) {
        record Wanted(Block block, Map<Property<?>, Comparable<?>> properties) {}
        List<Wanted> wanted = new ArrayList<>();
        for (String entry : BlockPatterns.split(c)) {
            BlockStateParser.BlockResult read;
            try {
                read = BuildStates.parse(entry, false);
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("'" + entry + "' is not a mask: " + bad.getMessage());
            }
            wanted.add(new Wanted(read.blockState().getBlock(), read.properties()));
        }
        return (w, x, y, z) -> {
            BlockState s = w.at(x, y, z);
            for (Wanted want : wanted) {
                if (s.is(want.block()) && want.properties().entrySet().stream()
                        .allMatch(e -> s.getValue(e.getKey()).equals(e.getValue()))) {
                    return true;
                }
            }
            return false;
        };
    }

    /** {@code ^[facing=north,half=top]}:有这些属性的方块,值要一样;缺这些属性的方块,不带 {@code =} 时算过,带 {@code =} 时不算。 */
    private static Mask states(String c) {
        boolean strict = c.startsWith("^=");
        String body = c.substring(strict ? 3 : 2);
        if (!body.endsWith("]")) {
            throw new IllegalArgumentException("'" + c + "' misses its closing ]");
        }
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        for (String pair : body.substring(0, body.length() - 1).split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("'" + c + "': '" + pair + "' is not property=value");
            }
            wanted.put(kv[0].trim(), kv[1].trim());
        }
        return (w, x, y, z) -> {
            BlockState s = w.at(x, y, z);
            boolean any = false;
            for (Property<?> p : s.getProperties()) {
                String want = wanted.get(p.getName());
                if (want != null) {
                    any = true;
                    if (!valueName(s, p).equals(want)) {
                        return false;
                    }
                }
            }
            return any || !strict;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(BlockState s, Property<?> p) {
        return ((Property) p).getName(s.getValue(p));
    }
}
