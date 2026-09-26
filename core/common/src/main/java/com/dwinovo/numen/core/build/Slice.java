package com.dwinovo.numen.core.build;

import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.permission.PlacedBlocks;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份施工图在某一层 y 的最终样子,俯视画成字符图:{@code build show <名> --layer <y>}。她写一层、看一层、改一层,不必在
 * 脑子里把整栋先算完。
 *
 * <h2>画的是什么</h2>
 * 每一格的最终方块:设计按步骤画完的那张图(后写覆盖先写,{@link Canvas}),蓝图文件读出来的那些格。门的上半、床头不在
 * 施工图里(放下主半时它们自己长出来),这里照它们建成后的样子画上——看的是建成之后这一层是什么样;那一格后来又被别的一步
 * 画过的,留后画的那一笔。
 *
 * <h2>长什么样</h2>
 * 和 {@code build layer} 的字符网格同一个约定:一行一个 z,第一行在北(最小的 z),每行从西往东(x 递增);{@code .} 是这一格
 * 什么都没画;图例写成 {@code --legend} 的样子({@code #=stone_bricks}),方块状态照 {@code /setblock} 的写法写全。行首标 z、
 * 头上一行标 x 的末位数,改哪一格一眼对得上坐标。所有层用同一个框(整份施工图的 x、z 范围),上下两层叠得上。行多了按输出
 * 预算分页({@link Listing})。
 */
public final class Slice {

    /** 这一格什么都没画。和 {@code build layer} 里"这一格不动"是同一个字。 */
    private static final char NOTHING = '.';
    /** 字母、数字、符号都分完了之后的那一种。 */
    private static final char OVERFLOW = '?';
    /**
     * 分给方块种类的字,先按名字的字母取,取不到再按这个顺序。不含 {@code .}、空格、{@code ?}(上面两个用处),不含
     * {@code -}(抄进 {@code build layer} 时以它开头的一行会被当成标志)、引号与反斜杠(命令行的引用)、{@code =}(图例的分隔)。
     */
    private static final String POOL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&@$+*~^!:;<>/|()[]{}"
            + "αβγδεζηθικλμνξοπρστυφχψωΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ";

    private Slice() {}

    /**
     * 画一层。
     *
     * @param title 抬头里怎么称呼这份施工图(如 {@code design house})
     * @param cells 施工图的每一格,坐标就是图上要标的坐标
     * @param again 翻页时写的那条命令(带着 {@code --layer})
     */
    public static Listing of(String title, List<BuildTaskRecord.Target> cells, int y, String again) {
        Map<BlockPos, BlockState> finished = finished(cells);
        if (finished.isEmpty()) {
            return new Listing(title + " has nothing drawn yet.", List.of(), "", again);
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos p : finished.keySet()) {
            int[] at = {p.getX(), p.getY(), p.getZ()};
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], at[i]);
                hi[i] = Math.max(hi[i], at[i]);
            }
        }
        if (y < lo[1] || y > hi[1]) {
            return new Listing(title + " has nothing at y=" + y + "; it spans y " + range(lo[1], hi[1]) + ".",
                    List.of(), "", again);
        }
        Map<BlockState, Character> legend = legend(finished, y, lo, hi);
        int zWidth = Math.max(String.valueOf(lo[2]).length(), String.valueOf(hi[2]).length());
        List<String> rows = new ArrayList<>();
        for (int z = lo[2]; z <= hi[2]; z++) {
            StringBuilder row = new StringBuilder(String.format("z %" + zWidth + "d  ", z));
            for (int x = lo[0]; x <= hi[0]; x++) {
                BlockState state = finished.get(new BlockPos(x, y, z));
                row.append(state == null ? NOTHING : legend.get(state));
            }
            rows.add(row.toString());
        }
        StringBuilder ruler = new StringBuilder("x ").append(" ".repeat(zWidth)).append("  ");
        for (int x = lo[0]; x <= hi[0]; x++) {
            ruler.append(Math.floorMod(x, 10));
        }
        String head = title + " at y=" + y + " (it spans y " + range(lo[1], hi[1]) + "), seen from above: x "
                + range(lo[0], hi[0]) + " left to right (east), z " + range(lo[2], hi[2])
                + " top to bottom (south); the x row gives each column's last digit; " + NOTHING
                + " = nothing here.\n" + ruler;
        return new Listing(head, rows, legendLine(legend), again);
    }

    /** 建成之后的每一格:画了的照画的,主半带出来的另一半补上(那一格没被别的一步画过时)。 */
    private static Map<BlockPos, BlockState> finished(List<BuildTaskRecord.Target> cells) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BuildTaskRecord.Target t : cells) {
            out.put(t.pos(), t.desiredState());
        }
        for (BuildTaskRecord.Target t : cells) {
            BlockPos other = PlacedBlocks.otherHalfOf(t.pos(), t.desiredState());
            if (other != null) {
                out.putIfAbsent(other, otherHalf(t.desiredState()));
            }
        }
        return out;
    }

    /** 主半带出来的另一半的状态;只在 {@link PlacedBlocks#otherHalfOf} 认它是主半之后调,判据在那一处。 */
    private static BlockState otherHalf(BlockState primary) {
        return primary.hasProperty(BlockStateProperties.BED_PART)
                ? primary.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                : primary.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
    }

    /**
     * 这一层的每种方块分一个字:格数多的先挑,一样多的按从北到南、从西到东先出现的先挑;先试方块名里的字母(小写、再大写),
     * 都被占了再按 {@link #POOL} 的顺序。分完了的归 {@link #OVERFLOW}。
     */
    private static Map<BlockState, Character> legend(Map<BlockPos, BlockState> finished, int y, int[] lo, int[] hi) {
        Map<BlockState, Integer> counts = new LinkedHashMap<>();
        for (int z = lo[2]; z <= hi[2]; z++) {
            for (int x = lo[0]; x <= hi[0]; x++) {
                BlockState state = finished.get(new BlockPos(x, y, z));
                if (state != null) {
                    counts.merge(state, 1, Integer::sum);
                }
            }
        }
        List<BlockState> order = new ArrayList<>(counts.keySet());
        order.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        Map<BlockState, Character> legend = new LinkedHashMap<>();
        StringBuilder taken = new StringBuilder();
        for (BlockState state : order) {
            char c = pick(name(state), taken);
            legend.put(state, c);
            if (c != OVERFLOW) {
                taken.append(c);
            }
        }
        return legend;
    }

    private static char pick(String name, CharSequence taken) {
        String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        StringBuilder candidates = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (Character.isLetter(c)) {
                candidates.append(Character.toLowerCase(c)).append(Character.toUpperCase(c));
            }
        }
        candidates.append(POOL);
        for (int i = 0; i < candidates.length(); i++) {
            char c = candidates.charAt(i);
            if (POOL.indexOf(c) >= 0 && taken.toString().indexOf(c) < 0) {
                return c;
            }
        }
        return OVERFLOW;
    }

    /** 图例,写成 {@code --legend} 的样子,一种一项、空格隔开;分不到字的几种合成一句。 */
    private static String legendLine(Map<BlockState, Character> legend) {
        StringBuilder sb = new StringBuilder("legend:");
        int overflow = 0;
        for (Map.Entry<BlockState, Character> e : legend.entrySet()) {
            if (e.getValue() == OVERFLOW) {
                overflow++;
            } else {
                sb.append(' ').append(e.getValue()).append('=').append(name(e.getKey()));
            }
        }
        if (overflow > 0) {
            sb.append(' ').append(OVERFLOW).append("=any of ").append(overflow)
                    .append(" more kinds this map has no letters left for");
        }
        return sb.toString();
    }

    /** 方块状态照 {@code /setblock} 的写法写全,原版的去掉命名空间。 */
    private static String name(BlockState state) {
        String full = BlockStateParser.serialize(state);
        return full.startsWith("minecraft:") ? full.substring("minecraft:".length()) : full;
    }

    private static String range(int lo, int hi) {
        return lo == hi ? Integer.toString(lo) : lo + ".." + hi;
    }
}
