package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 施工路上放下、又不是图纸格的方块:活怎么收场都撤掉,撤了哪些、哪些留在原处以及为什么,记下来交代出去。
 *
 * <p>路上放了什么只认一本账——寻路交出的实际账(任务的旅程账,{@code AbstractCompanionTask.placedOnTheWay}),
 * 这里不另记。账上的格此刻还是她放下的那种方块才撤:之后被别人换过、挖过的,已经不是她留下的东西。
 *
 * <p>撤走正常的破坏路径({@link BlockDigger#destroyNow}),权限层在那儿把门;她自己放的方块按出厂规则 {@code self_placed}
 * 放行,主人改过规则不让拆的,照实留下并说明。正托着她的那块不在这里拆——她会掉下去;调用方先让她挪开,挪不开就留下。
 */
final class EnRouteBlocks {

    private final NumenPlayer player;
    private final BlockDigger digger;
    /** 图纸里的格:它们是活本身,不是路上垫的。 */
    private final Predicate<BlockPos> designCell;

    /** 撤掉了的:格 → 方块。 */
    private final Map<BlockPos, Block> taken = new LinkedHashMap<>();
    /** 留在原处的:格 → (方块, 为什么)。 */
    private final Map<BlockPos, Left> left = new LinkedHashMap<>();

    private record Left(Block block, String why) {}

    /** 她正站在上面、挪不开时留下的说法。 */
    private static final String UNDERFOOT = "I was standing on it";

    EnRouteBlocks(NumenPlayer player, BlockDigger digger, Predicate<BlockPos> designCell) {
        this.player = player;
        this.digger = digger;
        this.designCell = designCell;
    }

    /** 账上还立着的垫块:放下的、不是图纸格、此刻还是那种方块、还没处理过。 */
    Set<BlockPos> standing(List<EditLedger.Placed> placed) {
        return Set.copyOf(standingBlocks(placed).keySet());
    }

    /**
     * 还立着的垫块里正托着她的那几格:拆掉就会让她掉下去。托着她的方块有一块不是垫块(站在自然地面与垫块的交界上)时,
     * 拆哪一块她都还站得住,一格也不算。
     */
    Set<BlockPos> holdingHer(List<EditLedger.Placed> placed) {
        Set<BlockPos> cells = standingBlocks(placed).keySet();
        Set<BlockPos> supports = supports();
        if (supports.isEmpty() || !cells.containsAll(supports)) {
            return Set.of();
        }
        return supports;
    }

    /**
     * 撤:账上还立着的垫块都走挖掘器拆掉;正托着她的那几格不拆,记成留在原处——调用方有机会让她先挪开的,在调这里之前挪。
     */
    void takeDown(List<EditLedger.Placed> placed) {
        Set<BlockPos> holding = holdingHer(placed);
        for (Map.Entry<BlockPos, Block> e : standingBlocks(placed).entrySet()) {
            BlockPos pos = e.getKey();
            if (holding.contains(pos)) {
                left.put(pos, new Left(e.getValue(), UNDERFOOT));
            } else if (digger.destroyNow(pos)) {
                taken.put(pos, e.getValue());
            } else {
                left.put(pos, new Left(e.getValue(), "breaking it was refused: " + digger.refusal().reason()));
            }
        }
    }

    /**
     * 回执里的那一句,例如 {@code Took down the 2 block(s) I had put down on the way: 2 cobblestone (1,64,2; 1,65,2).};
     * 什么都没放过是空串。
     */
    String describe() {
        if (taken.isEmpty() && left.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!taken.isEmpty()) {
            parts.add("Took down the " + taken.size() + " block(s) I had put down on the way: "
                    + String.join(", ", heaps(taken)));
        }
        Map<String, Map<BlockPos, Block>> byWhy = new LinkedHashMap<>();
        left.forEach((pos, l) -> byWhy.computeIfAbsent(l.why(), k -> new LinkedHashMap<>()).put(pos, l.block()));
        byWhy.forEach((why, cells) -> parts.add("Left " + cells.size() + " block(s) I had put down on the way where"
                + " they are (" + why + "): " + String.join(", ", heaps(cells))));
        return String.join(". ", parts) + ".";
    }

    /** 账上放下、不是图纸格、此刻还是那种方块、还没处理过的格,按放下的先后。 */
    private Map<BlockPos, Block> standingBlocks(List<EditLedger.Placed> placed) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        for (EditLedger.Placed place : placed) {
            BlockPos pos = place.pos();
            if (designCell.test(pos) || taken.containsKey(pos) || left.containsKey(pos)) {
                continue;
            }
            Block block = place.after().getBlock();
            if (player.level().getBlockState(pos).is(block)) {
                out.put(pos, block);
            }
        }
        return out;
    }

    /** 此刻托着她的方块(与寻路判"托着身体的是哪几格"同一处)。 */
    private Set<BlockPos> supports() {
        return Terrain.of(player).supports(player.getBoundingBox());
    }

    /** 按方块归堆,每堆一段:{@code 2 cobblestone (1,64,2; 1,65,2)}。 */
    private static List<String> heaps(Map<BlockPos, Block> cells) {
        Map<Block, List<BlockPos>> byBlock = new LinkedHashMap<>();
        cells.forEach((pos, block) -> byBlock.computeIfAbsent(block, k -> new ArrayList<>()).add(pos));
        List<String> out = new ArrayList<>();
        byBlock.forEach((block, list) -> out.add(
                Listing.part(BuiltInRegistries.BLOCK.getKey(block).getPath(), list.size(), list)));
        return out;
    }
}
