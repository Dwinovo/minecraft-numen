package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.permission.Listing;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 走完一遍之后没放好的格:每一格归到一个原因下,每个原因写成一句模型看得懂、能照着行动的话,带一行下一步。回执({@code Placed.unplaced})、
 * 成功与超时的那一句、留案的日志都从这一份出,不各说各的。
 *
 * <p>说明里的事实只来自服务端此刻知道的:挡着的是哪种方块、缺哪件物品各几件、谁站在格子里、周围六格是什么。猜不出的(是谁拒绝的)
 * 不写,只说"有东西拒绝了"。
 */
public final class BuildOutstanding {

    /** 一格为什么没放好;{@code kind} 是回执里的名字,脚本按它分支。 */
    enum Cause {
        /** 生存模式下立着别的方块,{@code numen.work.dig} 先挖开。 */
        DIG_FIRST("dig_first"),
        /** 身上没有要放的料。 */
        SHORT_OF_MATERIAL("short_of_material"),
        /** 走了一趟之后仍够不着、或走不到。 */
        OUT_OF_REACH("out_of_reach"),
        /** 托着它的那一格还没盖好,放下去立不住。 */
        NOTHING_HOLDS_IT("nothing_holds_it"),
        /** 有身体占着这一格(可能是她自己)。 */
        BLOCKED_BY_ENTITY("blocked_by_entity"),
        /** 点下去世界一格没变:保护、被取消的放置事件。 */
        REFUSED("refused"),
        /** 放下去了,原版弹掉了它或改了它的样子。 */
        DID_NOT_STAY("did_not_stay"),
        /** 没有哪件物品能直接放出这个方块(盆栽、海带的茎……)。 */
        NO_ITEM("no_item"),
        /** 涉及液体:要放水或岩浆、要把液体换成空气、或方块含的水没放。 */
        LIQUID("liquid"),
        /** 主动不去动:让路档位不许、权限不许、砸不动、出了边界。 */
        LEFT_ALONE("left_alone"),
        /** 所在区块没加载。 */
        NOT_LOADED("not_loaded"),
        /** 这一次调用在轮到它之前就收场了(被打断、超时)。 */
        UNFINISHED("unfinished");

        final String kind;

        Cause(String kind) {
            this.kind = kind;
        }
    }

    /**
     * 一格没放好:原因、图纸要的、世界里现在是什么,以及原因的细节(谁站着、为什么没留住、没走成的缘由;没有是 null)。
     */
    record Cell(Cause cause, BuildTaskRecord.Target target, BlockState actual, String detail) {}

    /** 回执里的一组:同一个原因的几格,一句说明,一行下一步。 */
    @Doc("Cells that were not placed, grouped by what stopped them.")
    public record Unplaced(@Doc("The reason: dig_first, short_of_material, out_of_reach, nothing_holds_it, "
            + "blocked_by_entity, refused, did_not_stay, no_item, liquid, left_alone, not_loaded or unfinished.") String kind,
                           @Doc("What stopped them, said in full.") String says,
                           @Doc("What to do next.") Optional<String> hint,
                           @Doc("How many cells.") int cells,
                           @Doc("The first few of them.") List<BlockPos> first) {}

    /** {@code liquid} 一类里一格的三种情形(细节句):要放液体、要把液体换成空气、方块含的水没放。 */
    static final String LIQUID_PLACE = "the design wants water or lava there, which is not placed";
    static final String LIQUID_DRAIN = "water or lava stands where the design wants air, which cannot be dug out by hand";
    static final String LIQUID_HELD = "the block was placed without the water it holds (waterlogged)";

    /** 一组里列出几格的位置。 */
    private static final int FIRST = 5;
    /** 一句里点名几格的细节(其余只计数)。 */
    private static final int DETAILED = 3;

    private BuildOutstanding() {}

    /**
     * 把没放好的格按原因分组,按 {@link Cause} 的先后。
     *
     * @param shortOf 还缺的料,{@code BuildLedger.summarizeShortfall} 的一句;不缺是空串
     */
    static List<Unplaced> report(List<Cell> cells, String shortOf) {
        Map<Cause, List<Cell>> byCause = new EnumMap<>(Cause.class);
        for (Cell cell : cells) {
            byCause.computeIfAbsent(cell.cause(), k -> new ArrayList<>()).add(cell);
        }
        List<Unplaced> out = new ArrayList<>();
        byCause.forEach((cause, group) -> {
            List<BlockPos> at = group.stream().map(c -> c.target().pos()).toList();
            out.add(new Unplaced(cause.kind, says(cause, group, shortOf), hint(cause, at),
                    group.size(), at.subList(0, Math.min(FIRST, at.size()))));
        });
        return out;
    }

    /** 一句话交代全部没放好的格,给成功与超时的收场语。 */
    static String sentence(List<Unplaced> report) {
        List<String> parts = new ArrayList<>();
        for (Unplaced group : report) {
            parts.add(group.says());
        }
        return String.join("; ", parts);
    }

    private static String says(Cause cause, List<Cell> group, String shortOf) {
        int n = group.size();
        return switch (cause) {
            case DIG_FIRST -> n + " cell(s) hold another block that must be dug out first: "
                    + heaps(group, true);
            case SHORT_OF_MATERIAL -> n + " cell(s) could not be placed for lack of material"
                    + (shortOf.isEmpty() ? "" : "; you still need " + shortOf) + ": " + heaps(group, false);
            case OUT_OF_REACH -> n + " cell(s) stayed out of reach" + detail(group, "; ") + ": "
                    + heaps(group, false);
            case NOTHING_HOLDS_IT -> n + " cell(s) would not stay put yet because what holds them (the block below "
                    + "or behind) is not built: " + heaps(group, false);
            case BLOCKED_BY_ENTITY -> n + " cell(s) have someone in them" + detail(group, " — ") + ": "
                    + heaps(group, false);
            case REFUSED -> n + " cell(s) were not placed: the click changed nothing, so something refused it "
                    + "(a protection plugin, a cancelled placement event, or your owner's rules): "
                    + heaps(group, false);
            case DID_NOT_STAY -> n + " cell(s) were placed and the world did not let them stay" + detail(group, " — ");
            case NO_ITEM -> n + " cell(s) have no item that places them" + detail(group, " — ") + ": "
                    + heaps(group, false);
            case LIQUID -> liquidSays(group);
            case LEFT_ALONE -> n + " cell(s) were left alone: what is there may not be moved, or the spot cannot be "
                    + "built on: " + heaps(group, false);
            case NOT_LOADED -> n + " cell(s) are in chunks that are not loaded: " + heaps(group, false);
            case UNFINISHED -> n + " cell(s) had not had their turn when the call ended: " + heaps(group, false);
        };
    }

    /** liquid 一类的一句:三种情形各几格、各是什么,不合并成一个总数。 */
    private static String liquidSays(List<Cell> group) {
        List<String> parts = new ArrayList<>();
        for (String detail : List.of(LIQUID_PLACE, LIQUID_DRAIN, LIQUID_HELD)) {
            List<Cell> some = group.stream().filter(c -> detail.equals(c.detail())).toList();
            if (!some.isEmpty()) {
                parts.add(some.size() + " cell(s): " + detail + " (" + heaps(some, detail.equals(LIQUID_DRAIN)) + ")");
            }
        }
        return String.join("; ", parts);
    }

    /** 下一步:能照抄的一行 Lua 最好,没有就一句话。 */
    private static Optional<String> hint(Cause cause, List<BlockPos> at) {
        return switch (cause) {
            case DIG_FIRST -> Optional.of(Call.of("numen.work.dig",
                    at.subList(0, Math.min(8, at.size())).toArray()));
            case OUT_OF_REACH, NOT_LOADED -> Optional.of(Call.of("numen.move.to", at.get(0)));
            case SHORT_OF_MATERIAL -> Optional.of("get those items, then call numen.build.place again with the same "
                    + "design: what already stands is skipped");
            case NOTHING_HOLDS_IT, DID_NOT_STAY -> Optional.of("build what should hold them first (the block below "
                    + "or behind each), then call numen.build.place again with the same design");
            case BLOCKED_BY_ENTITY -> Optional.of("have them step aside, then call numen.build.place again");
            case REFUSED -> Optional.of("check with your owner or the protection, then call numen.build.place again");
            case UNFINISHED -> Optional.of("call numen.build.place again with the same design");
            case NO_ITEM -> Optional.of("place what it comes from instead (the empty flower_pot for a potted plant), "
                    + "and leave it out of the design here");
            case LIQUID -> Optional.of("to have water or lava there, pour it from a bucket with numen.use.block; to get rid of it, "
                    + "fill the cell with a block, or scoop a water source up with a bucket");
            case LEFT_ALONE -> Optional.empty();
        };
    }

    /** 按方块归堆,每堆点名坐标;{@code actual} 取世界里现在立着的(挡路的),否则取图纸要的。 */
    private static String heaps(List<Cell> group, boolean actual) {
        Map<Block, List<BlockPos>> byBlock = new LinkedHashMap<>();
        for (Cell cell : group) {
            Block block = actual ? cell.actual().getBlock() : cell.target().desiredState().getBlock();
            byBlock.computeIfAbsent(block, k -> new ArrayList<>()).add(cell.target().pos());
        }
        List<String> heaps = new ArrayList<>();
        byBlock.forEach((block, positions) -> heaps.add(Listing.part(name(block), positions.size(), positions)));
        return String.join(", ", heaps);
    }

    /** 前几格各自的细节,用 {@code lead} 接在前面一句后面;没有细节为空。 */
    private static String detail(List<Cell> group, String lead) {
        List<String> parts = new ArrayList<>();
        for (Cell cell : group) {
            if (cell.detail() != null && !parts.contains(cell.detail()) && parts.size() < DETAILED) {
                parts.add(cell.detail());
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return lead + String.join("; ", parts) + (group.size() > parts.size() ? "; and others alike" : "");
    }

    static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }
}
