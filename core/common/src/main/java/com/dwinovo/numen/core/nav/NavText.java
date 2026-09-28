package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 寻路交出来的事实怎么对模型说,全在这里,只此一处:结局({@link Outcome})说成一句英文、归到哪一种 {@link FailureType};
 * 路上真改了什么(实际账)与身体为走路做了什么({@link BodyAction});一条候选路线要动哪些格(预算账),以及候选清单。
 * goto、follow、{@code move route} 与各件活的回执说的都是这一份。方块按种类归堆,坐标点名的写法与征询清单同一种
 * ({@link Listing})。
 */
public final class NavText {

    private NavText() {}

    // ==================== 结局 ====================

    /** 这个结局归到哪一种失败。 */
    public static FailureType type(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Arrived arrived -> FailureType.UNKNOWN;
            case Outcome.NoRoute noRoute -> FailureType.NO_PATH;
            case Outcome.OutOfBudget budget -> FailureType.NO_PATH;
            case Outcome.Unloaded unloaded -> FailureType.NO_PATH;
            case Outcome.OverAlterBudget over -> FailureType.NO_PATH;
            case Outcome.Stranded stranded -> FailureType.BOXED_IN;
            case Outcome.Blocked blocked -> FailureType.BOXED_IN;
            case Outcome.NeedsAlter needs -> FailureType.TERRAIN_BLOCKED;
            case Outcome.NoMaterials none -> FailureType.NO_MATERIAL;
            case Outcome.Denied denied -> FailureType.REFUSED;
            case Outcome.NoLineOfSight sight -> FailureType.OCCLUDED;
        };
    }

    /**
     * 没走到时的那句话:从哪儿、朝哪儿、多远,为什么,下一步能试什么。
     *
     * @param from   她此刻脚下那一格
     * @param toward 要去的那一格(给人看的方向)
     */
    public static String failure(Outcome outcome, NumenPlayer player, BlockPos from, BlockPos toward, RouteSpec spec) {
        String where = where(from, toward);
        return switch (outcome) {
            case Outcome.Arrived arrived -> "arrived";
            case Outcome.NoRoute noRoute -> "found no path to target (" + where + "; every reachable cell was searched"
                    + (spec.alter().mayAlter() ? ", digging, bridging and pillaring included" : "") + ")";
            case Outcome.OutOfBudget budget -> "found no path to target (" + where + "; the search used up its budget"
                    + " before finding one, so this is not proof there is none; a nearer waypoint in that direction"
                    + " gets further)";
            case Outcome.Unloaded unloaded -> "found no path to target (" + where + "; the search reached chunks that"
                    + " are not loaded, so what lies past them is unknown; walk toward it and try again)";
            case Outcome.OverAlterBudget over -> "found no path to target (" + where + "; "
                    + overBudget(spec.alterBudget(), over.needed()) + ")";
            case Outcome.NeedsAlter needs -> needs.level() == RouteSpec.Alter.NATURAL
                    ? "found no path to target without altering terrain (" + where + "; a route that digs, bridges or"
                            + " pillars through natural terrain exists, changing " + needs.alterations() + " block(s):"
                            + " walk with alter:'natural' to take it)"
                    : "found no route without touching what needs the owner's consent (" + where + "; one exists"
                            + " that changes " + needs.alterations() + " block(s), some of them someone's: walk with"
                            + " alter:'any' to ask the owner first)";
            case Outcome.NoMaterials none -> "found no path to target (" + where + "; every way needs blocks to"
                    + " pillar or bridge with)." + ThrowawayBlocks.shortageAdvice(player);
            case Outcome.Denied denied -> "had to stop: changing " + Listing.coords(denied.cell()) + " is refused ("
                    + reason(denied.reason()) + ")";
            case Outcome.Stranded stranded -> "can't set off: I can't stand where I am (" + name(stranded.block())
                    + " at " + Listing.coords(stranded.cell()) + ")";
            case Outcome.Blocked blocked -> "gave up: " + blockage(blocked.blockage());
            case Outcome.NoLineOfSight sight -> "arrived, but " + Listing.coords(sight.target())
                    + " is not in sight from there";
        };
    }

    private static String where(BlockPos from, BlockPos toward) {
        return String.format("from %s toward %s, about %.0f blocks away", from.toShortString(), toward.toShortString(),
                Math.sqrt(from.distSqr(toward)));
    }

    /** 预算内无路:两处回执共用,数字口径一致。 */
    public static String overBudget(int budget, int cheapest) {
        return String.format("no route within an alter_budget of %d (the cheapest found would change %d blocks)",
                budget, cheapest);
    }

    /** 许可或动手时被拒的理由:权限层的裁决说它自己的话,别的(服务端退回)照实说。 */
    private static String reason(Object refusal) {
        return refusal instanceof Verdict verdict ? verdict.reason() : "the server would not let it happen";
    }

    /** 一步走不下去的那一句:哪一格、什么方块、哪种走法、为什么。 */
    public static String blockage(Blockage b) {
        String why = b.reason() != null ? reason(b.reason()) : hitch(b.hitch());
        return move(b.move()) + " at " + Listing.coords(b.cell()) + " (" + name(b.block()) + ") kept failing: " + why;
    }

    private static String move(MoveKind kind) {
        return switch (kind) {
            case WALK -> "walking";
            case DIAGONAL -> "stepping diagonally";
            case ASCEND -> "stepping up";
            case DESCEND -> "stepping down";
            case FALL -> "dropping down";
            case PARKOUR -> "a running jump";
            case PILLAR -> "pillaring up";
            case DOWNWARD -> "digging down";
            case CLIMB -> "climbing";
            case SWIM -> "swimming";
        };
    }

    private static String reason(Reason reason) {
        return switch (reason) {
            case DISABLED -> "that kind of move is switched off for this walk";
            case NOT_STANDING -> "there is nothing to stand on where the step starts";
            case WRONG_STANCE -> "I am not in a position to make that move";
            case NO_FOOTING -> "nothing to stand on where the step lands";
            case NO_CLEARANCE -> "no room for my body there";
            case TOO_HIGH -> "too high to step or jump up";
            case TOO_FAR_TO_FALL -> "the drop is too far, or the landing would hurt too much";
            case WRONG_DROP -> "the ground is not where the step expected it";
            case NO_GAP -> "there is no gap to jump";
            case NO_SPRINT -> "the jump needs a sprint and I can't sprint";
            case EXCLUDED -> "this walk keeps out of that kind of cell";
            case TRAMPLES -> "landing there would trample it";
            case FORBIDDEN -> "this walk may not touch that cell";
            case NEEDS_ALTER -> "it would change the terrain, which this walk may not";
            case NO_MATERIALS -> "it needs a block to place and I carry none of my throwaway blocks";
            case DENIED -> "changing it is refused";
            case NEEDS_CONSENT -> "changing it needs the owner's consent";
            case EDIT_RESTRICTED -> "my game mode can't change blocks";
            case UNBREAKABLE -> "it can't be broken";
            case WOULD_FLOOD -> "breaking it would let liquid in";
            case WOULD_COLLAPSE -> "breaking it would bring loose blocks down";
            case MELTS -> "breaking the ice would leave water";
            case INFESTED -> "it is infested";
            case OUT_OF_BOUNDS -> "it is outside the world border or build height";
            case NOT_REPLACEABLE -> "a block can't go in there";
            case NO_FACE -> "there is nothing to place against";
            case OCCUPIED -> "my own body is in that cell";
            case OUT_OF_REACH -> "it is out of my reach";
        };
    }

    private static String hitch(Blockage.Hitch hitch) {
        return switch (hitch) {
            case STUCK -> "the step took far longer than it should, I was stuck";
            case OCCLUDED -> "I could not see the block I had to work on";
            case NO_FACE -> "I could not point at a face to place against";
            case NO_MATERIALS -> "I could not get the block into my hand";
            case DIVERTED -> "the ground changed and the step would land somewhere else";
        };
    }

    // ==================== 实际账与身体动作 ====================

    /**
     * 回执末尾那一段:路上挖了什么、放了什么(与倒下又收回的水),身体为走路做了什么;什么都没有是空串。
     * 例如 {@code En route I had to break 2 oak_planks (120,64,-33; 120,65,-33) and place 1 cobblestone (121,64,-33).
     * I also stepped off the boat.}
     */
    public static String journey(List<EditLedger.Entry> entries, List<BodyAction> actions) {
        String edits = edits(entries);
        String done = actions(actions);
        if (edits.isEmpty() && done.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!edits.isEmpty()) {
            sb.append("En route I had to ").append(edits).append('.');
        }
        if (!done.isEmpty()) {
            sb.append(sb.length() > 0 ? " I also " : "En route I ").append(done).append('.');
        }
        return sb.toString();
    }

    /** 实际账的正文:挖掉的、放下的、倒下的液体与收回的,各按方块归堆。空账是空串。 */
    static String edits(List<EditLedger.Entry> entries) {
        Map<Block, List<BlockPos>> broke = new LinkedHashMap<>();
        Map<Block, List<BlockPos>> placed = new LinkedHashMap<>();
        Map<Block, List<BlockPos>> poured = new LinkedHashMap<>();
        Map<Block, List<BlockPos>> scooped = new LinkedHashMap<>();
        for (EditLedger.Entry entry : entries) {
            switch (entry) {
                case EditLedger.Dug dug -> heap(broke, dug.before().getBlock(), dug.pos());
                case EditLedger.Placed p when !p.after().getFluidState().isEmpty() ->
                        heap(poured, p.after().getBlock(), p.pos());
                case EditLedger.Placed p when p.after().isAir() -> heap(scooped, p.before().getBlock(), p.pos());
                case EditLedger.Placed p -> heap(placed, p.after().getBlock(), p.pos());
                case EditLedger.Toggled toggled -> {
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (!broke.isEmpty()) {
            parts.add("break " + heaps(broke));
        }
        if (!placed.isEmpty()) {
            parts.add("place " + heaps(placed));
        }
        if (!poured.isEmpty()) {
            parts.add("pour " + heaps(poured) + " to break a fall");
        }
        if (!scooped.isEmpty()) {
            parts.add("scoop " + heaps(scooped) + " back into the bucket");
        }
        return andJoined(parts);
    }

    /** 身体动作的正文,同样的动作归成一条,例如 {@code stepped off the boat and took cobblestone into my hand (3 times)}。 */
    static String actions(List<BodyAction> actions) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (BodyAction action : actions) {
            counted.merge(action(action), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counted.forEach((text, n) -> parts.add(n == 1 ? text : text + " (" + n + " times)"));
        return andJoined(parts);
    }

    private static String action(BodyAction action) {
        return switch (action) {
            case BodyAction.Dismounted d -> "stepped off the " + EntityType.getKey(d.vehicle()).getPath();
            case BodyAction.Held h when h.item() == Items.AIR -> "put away what was in my hand";
            case BodyAction.Held h when h.from() == Hotbar.OFFHAND -> "swapped " + item(h.item())
                    + " from my offhand into my hand";
            case BodyAction.Held h when h.from() == h.to() -> "switched to " + item(h.item()) + " in my hotbar";
            case BodyAction.Held h -> "took " + item(h.item()) + " from my inventory into my hand";
            case BodyAction.Conjured c -> "took a stack of " + item(c.item()) + " from the creative inventory";
        };
    }

    // ==================== 预算账与候选清单 ====================

    /**
     * 一条候选里的一行:id、步数、要挖的格(要问主人的缀上为什么问)、要放的格,例如
     * {@code   r3  12 steps  break 2 oak_planks (120,64,-33; 120,65,-33) needing consent (placed by a player)  place 1 cobblestone}。
     */
    static String line(String id, Route route) {
        int steps = route.legs().size();
        return "  " + id + "  " + steps + (steps == 1 ? " step  " : " steps  ") + planned(route);
    }

    /** 一条候选要动什么,紧凑的一句;一格都不动是 {@code no terrain change}。 */
    static String planned(Route route) {
        Map<String, List<BlockPos>> digs = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<Block, List<BlockPos>> places = new LinkedHashMap<>();
        for (Edit edit : route.edits()) {
            switch (edit) {
                case Edit.Dig dig -> {
                    String cause = dig.permit() instanceof Permit.Ask ask && ask.credential() instanceof ConsentItem item
                            ? item.cause() : "";
                    String key = name(dig.state()) + '|' + cause;
                    digs.computeIfAbsent(key, k -> new ArrayList<>()).add(dig.pos());
                    labels.putIfAbsent(key, cause.isEmpty() ? "" : " needing consent (" + cause + ")");
                }
                case Edit.Place place -> heap(places, place.block(), place.pos());
                case Edit.Catch caught -> heap(places, net.minecraft.world.level.block.Blocks.WATER, caught.pos());
                case Edit.Door door -> {
                }
            }
        }
        if (digs.isEmpty() && places.isEmpty()) {
            return "no terrain change";
        }
        StringBuilder sb = new StringBuilder();
        if (!digs.isEmpty()) {
            List<String> parts = new ArrayList<>();
            digs.forEach((key, cells) -> parts.add(Listing.part(key.substring(0, key.indexOf('|')), cells.size(), cells)
                    + labels.get(key)));
            sb.append("break ").append(String.join(", ", parts));
        }
        if (!places.isEmpty()) {
            sb.append(sb.length() > 0 ? "  " : "").append("place ").append(heaps(places));
        }
        return sb.toString();
    }

    /** 候选清单:每条一行,按给定的顺序(先出的先列)。 */
    static String listing(List<RouteBook.Entry> routes) {
        StringBuilder sb = new StringBuilder();
        for (RouteBook.Entry r : routes) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line(r.id(), r.route()));
        }
        return sb.toString();
    }

    /**
     * "按这次的规格没有路"的回执:哪儿到哪儿、多远,接着是候选清单,末尾告诉模型怎么选。goto 与 follow 的
     * {@link FailureType#TERRAIN_BLOCKED} 只此一种说法。
     *
     * @param alteringAllowed 这次的规格本来就许改自然地形(候选是连要主人同意的格也算进去查出来的)
     */
    static String noCleanRoute(BlockPos from, BlockPos toward, boolean alteringAllowed, List<RouteBook.Entry> routes) {
        return "found no route without " + (alteringAllowed ? "touching what needs the owner's consent"
                : "altering terrain") + " (" + where(from, toward) + "; every reachable cell was searched). candidates:\n"
                + listing(routes) + "\nchoose one with move_goto route:<id>, or pick another destination. A route with"
                + " cells needing consent asks the owner before I set off.";
    }

    /**
     * 只搜不走却一条候选都没有的回执({@code move route} 是命令,教的是命令的写法):按这次的规格要改地形才有路,就说放宽到
     * 哪一档再规划一次能看到那几条;其余与走路没走到同一句话。
     */
    public static String unplanned(Outcome outcome, NumenPlayer player, BlockPos from, BlockPos toward, RouteSpec spec) {
        if (outcome instanceof Outcome.NeedsAlter needs) {
            String level = needs.level() == RouteSpec.Alter.NATURAL ? "natural" : "any";
            return "found no route " + (needs.level() == RouteSpec.Alter.NATURAL ? "without altering terrain"
                    : "without touching what needs the owner's consent") + " (" + where(from, toward) + "; one exists"
                    + " that changes " + needs.alterations() + " block(s)): plan again with --alter " + level
                    + " to see what it would take, or pick another destination";
        }
        return failure(outcome, player, from, toward, spec);
    }

    /** 只搜不走的回执:找到几条、从哪儿到哪儿,接着是候选清单,末尾告诉模型怎么用。 */
    public static String plannedRoutes(BlockPos from, BlockPos toward, List<RouteBook.Entry> routes) {
        return routes.size() + (routes.size() == 1 ? " route " : " routes ") + where(from, toward) + ":\n"
                + listing(routes) + "\nwalk one with move_goto route:<id>; ids stay valid while I stay near here.";
    }

    // ==================== 小工具 ====================

    private static void heap(Map<Block, List<BlockPos>> heaps, Block block, BlockPos pos) {
        heaps.computeIfAbsent(block, k -> new ArrayList<>()).add(pos);
    }

    private static String heaps(Map<Block, List<BlockPos>> heaps) {
        List<String> parts = new ArrayList<>();
        heaps.forEach((block, cells) -> parts.add(Listing.part(name(block), cells.size(), cells)));
        return andJoined(parts);
    }

    private static String andJoined(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String name(BlockState state) {
        return name(state.getBlock());
    }

    static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static String item(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
