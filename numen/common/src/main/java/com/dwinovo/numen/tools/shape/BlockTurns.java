package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.build.BuildStates;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 方块状态跟着一个 3×3 线性变换转(转向、翻转):全仓"方块状态怎么转"只有这一处。
 *
 * <p>分工:水平面里的 90° 倍数转动与水平镜像一律走原版的 {@code BlockState.rotate} 与 {@code mirror}——每种方块(模组的也是)自己知道
 * 铁轨、门、楼梯内外角、合成器朝向怎么转。原版不覆盖的(竖直翻转、绕 X 与 Z 转、不是 90° 倍数的转动)照 WorldEdit
 * {@code BlockTransformExtent} 的规则:方向属性取变换后最接近的方向,{@code axis}、{@code rotation}、东南西北四面、铁轨的 {@code shape}、
 * 合成器的 {@code orientation} 同理,{@code half}/{@code type} 随竖直翻转上下互换,楼梯 {@code shape} 与箱子 {@code type} 随水平翻转左右互换。
 * 变换后落不进这个方块的取值范围的(横向的 {@code axis} 转到竖直),这个属性保持原样。
 */
final class BlockTurns {

    private BlockTurns() {}

    /**
     * 每一种写法转一遍:没变的原样交回(模型写的是什么就是什么);变了的照它的写法改——方块名、写了的属性及其先后、NBT 原文都留着,
     * 只把属性的值换成转后的,没写的属性转后变了值的补在后面。
     */
    static List<String> turn(List<String> written, double[][] matrix) {
        Map<String, String> done = new HashMap<>();
        List<String> out = new ArrayList<>(written.size());
        for (String w : written) {
            out.add(done.computeIfAbsent(w, one -> turnOne(one, matrix)));
        }
        return out;
    }

    private static String turnOne(String written, double[][] m) {
        BlockState before = BuildStates.parse(written, true).blockState();
        BlockState after = turn(before, m);
        return after == before ? written : restated(written.trim(), before, after);
    }

    private static String restated(String text, BlockState before, BlockState after) {
        int open = text.indexOf('[');
        int brace = text.indexOf('{');
        boolean states = open >= 0 && (brace < 0 || open < brace);
        int close = states ? text.indexOf(']', open) : -1;
        String head = states ? text.substring(0, open) : brace >= 0 ? text.substring(0, brace) : text;
        String tail = states ? text.substring(close + 1) : brace >= 0 ? text.substring(brace) : "";
        List<String> parts = new ArrayList<>();
        List<String> written = new ArrayList<>();
        if (states) {
            for (String pair : text.substring(open + 1, close).split(",")) {
                String key = pair.split("=", 2)[0].trim();
                Property<?> p = after.getBlock().getStateDefinition().getProperty(key);
                written.add(key);
                parts.add(key + "=" + name(p, after.getValue(p)));
            }
        }
        List<Property<?>> others = new ArrayList<>(after.getProperties());
        others.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Property<?> p : others) {
            if (!written.contains(p.getName()) && !name(p, before.getValue(p)).equals(name(p, after.getValue(p)))) {
                parts.add(p.getName() + "=" + name(p, after.getValue(p)));
            }
        }
        return head.trim() + (parts.isEmpty() ? "" : "[" + String.join(",", parts) + "]") + tail;
    }

    static BlockState turn(BlockState state, double[][] m) {
        Optional<BlockState> vanilla = horizontal(state, m);
        return vanilla.orElseGet(() -> weTurn(state, m));
    }

    // ---- 原版能做的:水平面里的四分之一转与镜像 ----

    /** 变换只动水平面、且是 90° 的倍数(可带水平镜像):交给原版;不是就空。 */
    private static Optional<BlockState> horizontal(BlockState state, double[][] m) {
        if (m[1][1] != 1 || m[0][1] != 0 || m[1][0] != 0 || m[1][2] != 0 || m[2][1] != 0) {
            return Optional.empty();
        }
        // 先镜像(不镜像、前后、左右)再转:变换 = 转 · 镜像,取不用转的那一种(不转最省)
        Mirror[] mirrors = {Mirror.NONE, Mirror.LEFT_RIGHT, Mirror.FRONT_BACK};
        Rotation best = null;
        Mirror bestMirror = null;
        for (Mirror mirror : mirrors) {
            Rotation rotation = rotationOf(m[0][0] * (mirror == Mirror.FRONT_BACK ? -1 : 1), m[0][2] * (mirror == Mirror.LEFT_RIGHT ? -1 : 1),
                    m[2][0] * (mirror == Mirror.FRONT_BACK ? -1 : 1), m[2][2] * (mirror == Mirror.LEFT_RIGHT ? -1 : 1));
            if (rotation != null && (best == null || (best != Rotation.NONE && rotation == Rotation.NONE))) {
                best = rotation;
                bestMirror = mirror;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(state.mirror(bestMirror).rotate(best));
    }

    /** 2×2 阵 (a b; c d) 作用在 (x, z) 上:是哪个顺时针转动(从上看)。东 (1,0) 转到南 (0,1) 是顺时针 90°。不是四分之一转是 null。 */
    private static Rotation rotationOf(double a, double b, double c, double d) {
        if (a == 1 && b == 0 && c == 0 && d == 1) {
            return Rotation.NONE;
        }
        if (a == 0 && b == -1 && c == 1 && d == 0) {
            return Rotation.CLOCKWISE_90;
        }
        if (a == -1 && b == 0 && c == 0 && d == -1) {
            return Rotation.CLOCKWISE_180;
        }
        if (a == 0 && b == 1 && c == -1 && d == 0) {
            return Rotation.COUNTERCLOCKWISE_90;
        }
        return null;
    }

    // ---- 原版不覆盖的:WorldEdit 的规则 ----

    private static final List<Direction> CARDINAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final List<Direction> UPRIGHT_AND_CARDINAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST, Direction.UP, Direction.DOWN);
    private static final List<String> STRAIGHT_RAILS = List.of("north_south", "east_west", "ascending_east", "ascending_west",
            "ascending_north", "ascending_south");

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState weTurn(BlockState original, double[][] m) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 东南西北四面(围栏、墙、玻璃板、红石线、藤蔓…):每一面有的,搬到变换后最接近的那一面。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState sides(BlockState original, BlockState turned, double[][] m) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static boolean isRailShape(Property<?> p) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static String railShape(Property<?> p, String shape, double[][] m, boolean verticalFlip) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 合成器的 orientation:{@code 朝向_上方}(朝向可以是上下,上方只在水平方向里选,朝下换成朝上)。 */
    private static String crafterOrientation(String orientation, double[][] m) {
        throw new UnsupportedOperationException("净室重写中");
    }

    /** 告示牌、旗帜、头颅的 0..15 档:十六个水平方向里取变换后最接近的;0 是南,每档 22.5°,4 是西。 */
    private static int turnedRotation(int index, double[][] m) {
        double angle = Math.toRadians(index * 22.5);
        double[] v = apply(m, -Math.sin(angle), 0, Math.cos(angle));
        if (Math.hypot(v[0], v[2]) < 1e-9) {
            return index;
        }
        long steps = Math.round(Math.toDegrees(Math.atan2(-v[0], v[2])) / 22.5);
        return (int) Math.floorMod(steps, 16L);
    }

    /**
     * 一个方向变换后,候选里最接近的那个(点积最大,并列取后面的,同 WorldEdit);{@code withY} 为否时竖直分量不算。变换把它转成了零向量是 null。
     */
    private static Direction closest(double[][] m, Direction d, List<Direction> candidates, boolean withY) {
        throw new UnsupportedOperationException("净室重写中");
    }

    private static double[] apply(double[][] m, double x, double y, double z) {
        return new double[]{m[0][0] * x + m[0][1] * y + m[0][2] * z, m[1][0] * x + m[1][1] * y + m[1][2] * z,
                m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }

    private static String swapped(String value, String a, String b) {
        return value.equals(a) ? b : value.equals(b) ? a : value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String name(Property<?> p, Comparable<?> value) {
        return ((Property) p).getName(value);
    }

    /** 把这个属性设成名字为 value 的取值;这个方块没有这个取值就保持原样。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, Property<?> p, String value) {
        Optional<?> v = p.getValue(value.toLowerCase(Locale.ROOT));
        return v.isPresent() ? state.setValue((Property) p, (Comparable) v.get()) : state;
    }
}
