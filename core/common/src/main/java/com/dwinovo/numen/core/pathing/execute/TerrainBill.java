package com.dwinovo.numen.core.pathing.execute;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.moves.Movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张路线的账单:多长(格数、估计刻数)、哪些格要挖(坐标、方块、以及为什么需要同意)、
 * 哪些格要放。两处用同一种说法——规划出来的路<b>会</b>动什么(预算账,喂给模型决定要不要
 * 授权),和执行器<b>真</b>动了什么(实际账,任务回执事后如实相告)。语言面向工具回执
 * (英文),坐标点名,方块按种类归堆,模型一眼能分出"两块木板一块玻璃"和"十一块石头"。
 *
 * <p>路线回执的文案全在这里:一条候选一行({@link #line}),候选清单
 * ({@link #listing}),以及"没有干净的路"的回执({@link #noCleanRoute})——goto、follow
 * 与规划查询工具都用这一份措辞,模型在哪儿看到的路线都长一个样。
 */
public final class TerrainBill {

    /** 每种方块最多点名多少个坐标,其余计数——清单是给人判断的,不是给人数的。 */
    private static final int COORDS_PER_KIND = 6;

    /**
     * 一条挖掘条目。
     *
     * @param consent 为什么需要主人同意(玩家放置 / 带方块实体);不需要同意为空串。
     *                由权限层填写,规划器自己不判
     */
    public record Break(BlockPos pos, Block block, String consent) {}

    /** @param block 放上去的方块;规划阶段还不知道会选哪种耗材,为 null */
    public record Place(BlockPos pos, Block block) {}

    private final List<Break> breaks = new ArrayList<>();
    private final List<Place> places = new ArrayList<>();
    private int blocks;
    private double ticks;

    /** 规划路径的预算:长度,加上沿途每个移动原语此刻仍需挖/放的格。 */
    public static TerrainBill planned(NavPath path, BlockGetter level) {
        TerrainBill bill = new TerrainBill();
        bill.blocks = path.length();
        bill.ticks = path.ticksRemainingFrom(0);
        for (Movement m : path.movements()) {
            for (BlockPos p : m.toBreak(level)) {
                bill.addBreak(p, level.getBlockState(p));
            }
            for (BlockPos p : m.toPlace(level)) {
                bill.addPlace(p, null);
            }
        }
        return bill;
    }

    public void addBreak(BlockPos pos, BlockState was) {
        breaks.add(new Break(pos.immutable(), was.getBlock(), ""));
    }

    /** @param placed 放上去的方块;规划阶段还不知道会选哪种耗材,传 null */
    public void addPlace(BlockPos pos, Block placed) {
        places.add(new Place(pos.immutable(), placed));
    }

    /** 并入另一张账单(任务把历次导航的账汇总成一次旅程的账)。 */
    public void addAll(TerrainBill other) {
        breaks.addAll(other.breaks);
        places.addAll(other.places);
        blocks += other.blocks;
        ticks += other.ticks;
    }

    public boolean isEmpty() {
        return breaks.isEmpty() && places.isEmpty();
    }

    public List<Break> breaks() {
        return List.copyOf(breaks);
    }

    public List<Place> places() {
        return List.copyOf(places);
    }

    public int breakCount() {
        return breaks.size();
    }

    public int placeCount() {
        return places.size();
    }

    /** 路线长度(格数);执行账尚未记长度时为 0。 */
    public int blocks() {
        return blocks;
    }

    /** 路线的估计刻数;执行账尚未记长度时为 0。 */
    public double ticks() {
        return ticks;
    }

    /**
     * 清单正文,例如
     * {@code break 2 oak_planks (120,64,-33; 120,65,-33) and 1 glass (122,65,-33), and place 2 blocks}。
     * 空清单返回空串。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!breaks.isEmpty()) {
            Map<Block, List<BlockPos>> byKind = new LinkedHashMap<>();
            for (Break b : breaks) {
                byKind.computeIfAbsent(b.block(), k -> new ArrayList<>()).add(b.pos());
            }
            sb.append("break ").append(kinds(byKind));
        }
        if (!places.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", and ");
            }
            sb.append("place ");
            Map<Block, List<BlockPos>> byKind = new LinkedHashMap<>();
            for (Place p : places) {
                byKind.computeIfAbsent(p.block(), k -> new ArrayList<>()).add(p.pos());
            }
            if (byKind.size() == 1 && byKind.containsKey(null)) {
                sb.append(placeCount()).append(placeCount() == 1 ? " block" : " blocks");
            } else {
                sb.append(kinds(byKind));
            }
        }
        return sb.toString();
    }

    /**
     * 紧凑正文(候选行用),例如 {@code break 2 oak_planks (120,64,-33; 120,65,-33), 1 glass (122,65,-33)  place 2 blocks};
     * 空清单是 {@code no terrain change}。与 {@link #describe} 同一份归堆,只是不成句。
     */
    public String summary() {
        if (isEmpty()) {
            return "no terrain change";
        }
        StringBuilder sb = new StringBuilder();
        if (!breaks.isEmpty()) {
            Map<Block, List<BlockPos>> byKind = new LinkedHashMap<>();
            for (Break b : breaks) {
                byKind.computeIfAbsent(b.block(), k -> new ArrayList<>()).add(b.pos());
            }
            sb.append("break ").append(String.join(", ", parts(byKind)));
        }
        if (!places.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append("place ").append(placeCount()).append(placeCount() == 1 ? " block" : " blocks");
        }
        return sb.toString();
    }

    /** 候选清单里的一行:id、长度、账。 */
    public String line(String id) {
        return "  " + id + "  " + blocks + (blocks == 1 ? " block  " : " blocks  ") + summary();
    }

    /** 候选清单正文:每条一行,按给定顺序(先出的先列)。 */
    public static String listing(Map<String, TerrainBill> byId) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, TerrainBill> e : byId.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getValue().line(e.getKey()));
        }
        return sb.toString();
    }

    /**
     * "只走不改没有路"的回执:哪儿到哪儿、多远,接着是候选清单,末尾告诉模型怎么选。
     * goto 与 follow 的 TERRAIN_BLOCKED 文案只此一处。
     */
    public static String noCleanRoute(BlockPos from, BlockPos toward, Map<String, TerrainBill> byId) {
        return String.format(
                "no route without altering terrain (from %s toward %s, about %.0f blocks away). candidates:\n%s\n"
                        + "choose one with goto route:<id>, or pick another destination.",
                from.toShortString(), toward.toShortString(), Math.sqrt(from.distSqr(toward)),
                listing(byId));
    }

    /** 预算内无路的说法:两处失败回执共用,数字口径一致。 */
    public static String overBudget(int budget, int cheapestChange) {
        return String.format("no route within an alter_budget of %d (the cheapest found would change %d blocks)",
                budget, cheapestChange);
    }

    /** 规划查询的回执:找到几条、从哪儿到哪儿,接着是候选清单,末尾告诉模型怎么用。 */
    public static String planned(BlockPos from, BlockPos toward, Map<String, TerrainBill> byId) {
        return String.format(
                "%d route%s from %s toward %s (about %.0f blocks away):\n%s\n"
                        + "walk one with goto route:<id>; ids stay valid while I stay near here.",
                byId.size(), byId.size() == 1 ? "" : "s",
                from.toShortString(), toward.toShortString(), Math.sqrt(from.distSqr(toward)),
                listing(byId));
    }

    private static String kinds(Map<Block, List<BlockPos>> byKind) {
        List<String> parts = parts(byKind);
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** 每种方块一段:{@code 2 oak_planks (120,64,-33; 120,65,-33)}。 */
    private static List<String> parts(Map<Block, List<BlockPos>> byKind) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Block, List<BlockPos>> e : byKind.entrySet()) {
            List<BlockPos> cells = e.getValue();
            StringBuilder part = new StringBuilder();
            part.append(cells.size()).append(' ')
                    .append(e.getKey() == null ? "block" : BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath())
                    .append(" (");
            for (int i = 0; i < Math.min(COORDS_PER_KIND, cells.size()); i++) {
                if (i > 0) {
                    part.append("; ");
                }
                BlockPos p = cells.get(i);
                part.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
            }
            if (cells.size() > COORDS_PER_KIND) {
                part.append("; +").append(cells.size() - COORDS_PER_KIND).append(" more");
            }
            part.append(')');
            parts.add(part.toString());
        }
        return parts;
    }
}
