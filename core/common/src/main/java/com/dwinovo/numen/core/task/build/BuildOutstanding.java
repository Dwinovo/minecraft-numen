package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.permission.Listing;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 收不了尾时还缺的格:走一遍图纸,每一格归到一个病因下,得到一份清单。留案的日志、交给模型的回执、失败的类型都从这一份出,
 * 不各数各的。
 *
 * <p>剩下的格放不下去只有三种病因,修法各不相同:让占着的人挪开、认下图纸里原版不允许的那几格、找出是什么一再把它弄没。
 * 所以按病因分开报,每一堆点名到方块和坐标——笼统一句 "could not be placed" 等于裁决做了却不交代理由。
 */
final class BuildOutstanding {

    /** 一格为什么还缺着。 */
    enum Cause {
        /** 有身体占着这一格(可能是她自己)。 */
        OCCUPIED(FailureType.ENTITY_BLOCKED, "blocked by someone standing there — ask them to step aside"),
        /** 原版在这个位置立不住它(花下面不是土、火把旁边没墙)。 */
        UNSUPPORTED(FailureType.NO_SUPPORT, "that vanilla physics will not hold at that spot"
                + " (the blueprint asks for something impossible there)"),
        /** 放了却没留住,或者世界不收。 */
        NOT_KEPT(FailureType.NOT_KEPT, "that would not stay put");

        final FailureType failure;
        final String says;

        Cause(FailureType failure, String says) {
            this.failure = failure;
            this.says = says;
        }
    }

    /** 一格缺着:病因、图纸要的、世界里现在是什么。 */
    record Cell(Cause cause, BuildTaskRecord.Target target, BlockState actual) {}

    private final List<Cell> cells;
    /** 施工期间已砌好又被外力弄没的格数:"没留住"那一堆的说法要带上它。 */
    private final int damagedCells;

    BuildOutstanding(List<Cell> cells, int damagedCells) {
        this.cells = List.copyOf(cells);
        this.damagedCells = damagedCells;
    }

    /**
     * 走一遍图纸:和世界对不上、又不是主动不去动的格,按病因归类。主动不去动的格不算"缺格"——它们不在分母里,
     * 更不该在清单里冒充病灶(玩家箱子压着的格 canSurvive 为真,会一路落进"没留住")。
     */
    static BuildOutstanding survey(List<BuildTaskRecord.Target> targets, BuildCellRules rules, LongSet skipped,
                                   Level level, int damagedCells) {
        List<Cell> cells = new ArrayList<>();
        for (BuildTaskRecord.Target target : targets) {
            BlockPos pos = target.pos();
            BlockState actual = rules.peek(pos);
            if (target.matches(actual) || skipped.contains(pos.asLong())) {
                continue;
            }
            BlockState desired = target.desiredState();
            Cause cause;
            if (rules.blockedByEntity(pos, desired)) {
                cause = Cause.OCCUPIED;
            } else if (!desired.canSurvive(level, pos)) {
                cause = Cause.UNSUPPORTED;
            } else {
                cause = Cause.NOT_KEPT;
            }
            cells.add(new Cell(cause, target, actual));
        }
        return new BuildOutstanding(cells, damagedCells);
    }

    List<Cell> cells() {
        return cells;
    }

    /** 失败类型跟主导病因走:缺格最多的那一类;一样多时按 {@link Cause} 的先后。 */
    FailureType failure() {
        Map<Cause, Integer> counts = counts();
        Cause dominant = null;
        for (Map.Entry<Cause, Integer> e : counts.entrySet()) {
            if (dominant == null || e.getValue() > counts.get(dominant)) {
                dominant = e.getKey();
            }
        }
        return dominant == null ? FailureType.NOT_KEPT : dominant.failure;
    }

    /**
     * 交给模型的那一句:一共几格,再按病因 → 方块 → 坐标归堆。坐标是世界坐标,这件活照一份施工图盖时每格后面跟着它在
     * 施工图里的坐标;每堆点名多少格、其余怎么计数照 {@link Listing}。
     *
     * @param frame 施工图摆在哪儿、朝哪儿;当场执行的原语没有施工图,是 null
     */
    String describe(Placement frame) {
        Function<BlockPos, String> naming = frame == null
                ? Listing::coords
                : pos -> Listing.coords(pos) + " = design " + Listing.coords(frame.relative(pos));
        List<String> parts = new ArrayList<>();
        grouped().forEach((cause, byBlock) -> {
            int n = byBlock.values().stream().mapToInt(List::size).sum();
            List<String> heaps = new ArrayList<>();
            byBlock.forEach((block, positions) -> heaps.add(Listing.part(name(block), positions.size(), positions,
                    naming)));
            String says = cause == Cause.NOT_KEPT && damagedCells > 0
                    ? cause.says + " — something kept breaking the finished work"
                    : cause.says;
            parts.add(n + " " + says + ": " + String.join(", ", heaps));
        });
        return cells.size() + " cell(s) unbuilt — " + String.join("; ", parts);
    }

    /** 留案逐格写明细的格数:病因与分布已在清单里写全,明细是给排查起个头的样本。 */
    private static final int LOGGED_EXAMPLES = 12;

    /**
     * 留案:缺格按层分布,加上与回执同一份的归堆清单;再逐格写开头几格的明细(期望的与世界里现在的),排查从这里开始。
     */
    void log(int completed, int total, BlockPos feet, Placement frame) {
        Map<Integer, Integer> byLayer = new TreeMap<>();
        for (Cell cell : cells) {
            byLayer.merge(cell.target().pos().getY(), 1, Integer::sum);
        }
        com.dwinovo.numen.core.Constants.LOG.info("[numen-build] 收不了尾 {}/{} feet={} 缺格分层={} {}",
                completed, total, feet.toShortString(), byLayer, describe(frame));
        for (Cell cell : cells.subList(0, Math.min(LOGGED_EXAMPLES, cells.size()))) {
            com.dwinovo.numen.core.Constants.LOG.info("[numen-build] 缺 {} {} 期望={} 实际={}",
                    cell.cause(), cell.target().pos().toShortString(), cell.target().desiredState(), cell.actual());
        }
    }

    private Map<Cause, Integer> counts() {
        Map<Cause, Integer> counts = new EnumMap<>(Cause.class);
        for (Cell cell : cells) {
            counts.merge(cell.cause(), 1, Integer::sum);
        }
        return counts;
    }

    /** 病因 → 方块 → 坐标,病因按 {@link Cause} 的先后,方块与坐标按图纸里的先后。 */
    private Map<Cause, Map<Block, List<BlockPos>>> grouped() {
        Map<Cause, Map<Block, List<BlockPos>>> out = new EnumMap<>(Cause.class);
        for (Cell cell : cells) {
            out.computeIfAbsent(cell.cause(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(cell.target().desiredState().getBlock(), k -> new ArrayList<>())
                    .add(cell.target().pos());
        }
        return out;
    }

    private static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
