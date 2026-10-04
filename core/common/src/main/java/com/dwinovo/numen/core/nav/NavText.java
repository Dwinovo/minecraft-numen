package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.sdk.Place;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.drive.DiveLog;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
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
 * 路上真改了什么(实际账)与身体为走路做了什么({@link BodyAction});一份计划要动哪些格(预算账)。走路线、跟随与各件活的
 * 回执说的都是这一份。方块按种类归堆,坐标点名的写法与征询清单同一种({@link Listing})。
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
            case Outcome.NeedsChanges needs -> FailureType.TERRAIN_BLOCKED;
            case Outcome.NoMaterials none -> FailureType.NO_MATERIAL;
            case Outcome.Denied denied -> FailureType.REFUSED;
            case Outcome.NoLineOfSight sight -> FailureType.OCCLUDED;
            case Outcome.Breathless breathless -> FailureType.HAZARD;
        };
    }

    /**
     * 没走到时的那句话:从哪儿、朝哪儿、多远,为什么,下一步能试什么。每一种结局各说各的原因与下一步,走路与各件活
     * (挖矿、建造……)没走到时都原样说这一句,不另写、不并成一句"到不了"。要改的是这一趟的描述时,下一步写成描述里的那几项
     * (如 {@code costs = {dig = true, place = true}}),再规划一次。
     *
     * @param from      她此刻脚下那一格
     * @param toward    要去的那一格(给人看的方向)
     * @param materials 这一趟愿意垫的料(说"没料"时列出来)
     */
    public static String failure(Outcome outcome, NumenPlayer player, BlockPos from, BlockPos toward, RouteSpec spec,
                                 List<Item> materials) {
        String where = where(from, toward);
        return switch (outcome) {
            case Outcome.Arrived arrived -> "arrived";
            // 诊断在许挖许放、设想有料时也没搜出路才给这个结局:这条规格下从这里就是过不去
            case Outcome.NoRoute noRoute -> "found no path to target (" + where + "; every reachable cell was searched"
                    + (spec.changes() ? ", digging, bridging and pillaring included" : "")
                    + "): there is no way there from here, pick another destination";
            case Outcome.OutOfBudget budget -> "found no path to target (" + where + "; the search used up its budget"
                    + " before finding one, so this is not proof there is none; a nearer stop in that direction"
                    + " gets further)";
            case Outcome.Unloaded unloaded -> "found no path to target (" + where + "; the search reached chunks that"
                    + " are not loaded, so what lies past them is unknown; walk toward it and try again)";
            case Outcome.OverAlterBudget over -> "found no path to target (" + where + "; "
                    + overBudget(spec.alterBudget(), over.needed()) + "): plan it again with costs = {max_changes = "
                    + over.needed() + "} to allow it";
            case Outcome.NeedsChanges needs -> needsChanges(needs, spec, where);
            case Outcome.NoMaterials none -> "found no path to target (" + where + "; every way needs blocks to"
                    + " pillar or bridge with)." + ThrowawayBlocks.shortageAdvice(player, materials);
            case Outcome.Denied denied -> "had to stop: changing " + Listing.coords(denied.cell()) + " is refused ("
                    + reason(denied.reason()) + "); that is not mine to get around, so plan a way that keeps out of it"
                    + " (avoid = {" + com.dwinovo.numen.sdk.LuaCodecs.literal(denied.cell()) + "}) or ask your owner";
            case Outcome.Stranded stranded -> "can't set off: I can't stand where I am (" + name(stranded.block())
                    + " at " + Listing.coords(stranded.cell()) + "); free me first (break that block: `numen.work.dig("
                    + com.dwinovo.numen.sdk.LuaCodecs.literal(stranded.cell()) + ")`) or wait until I land";
            case Outcome.Blocked blocked -> "gave up: " + blockage(blocked.blockage())
                    + "; try again, and pick another destination if it keeps failing";
            case Outcome.NoLineOfSight sight -> "arrived, but " + Listing.coords(sight.target())
                    + " went out of sight after the walk was planned (something now stands in between); "
                    + gotoCall(Place.cell(sight.target()), "arrive = \"use\"") + " again picks a spot that sees it";
            case Outcome.Breathless b -> "found no path to target (" + where + "; the way there swims under water"
                    + " from " + Listing.coords(b.from()) + " to " + Listing.coords(b.to()) + " with no air on the way,"
                    + " about " + seconds(b.held()) + " without a breath, longer than I can safely hold mine now (about "
                    + seconds(b.spare()) + "): with water breathing (a potion of water breathing, or a turtle shell on"
                    + " my head) I could take it, otherwise pick another destination";
        };
    }

    /**
     * 这一趟的描述不许的改动,有了才有路:那条路要改什么,描述里该改哪几项——诊断为找出这条路打开了哪几样就说哪几样(挖与放
     * 一起打开,要问主人的格算能走)。只点那条路用到的一样不行:诊断设想身上有料,只许放的计划在没料时仍然走不通。
     */
    private static String needsChanges(Outcome.NeedsChanges needs, RouteSpec spec, String where) {
        List<String> knobs = new ArrayList<>();
        if (!spec.dig()) {
            knobs.add("dig = true");
        }
        if (!spec.place()) {
            knobs.add("place = true");
        }
        boolean asking = needs.asks() && !spec.consent();
        if (asking) {
            knobs.add("consent = " + (int) RouteSpec.CONSENT_MULTIPLIER);
        }
        String what = knobs.size() == 1 && asking ? "touching what needs the owner's consent"
                : "changing terrain";
        return "found no path to target without " + what + " (" + where + "; a way exists that changes "
                + needs.alterations() + " block(s) — " + planned(needs.changes()) + "): plan it again with costs = {"
                + String.join(", ", knobs) + "}"
                + (needs.asks() ? "; walking it asks your owner when I get to each of those cells" : "");
    }

    /** 刻数说成秒:{@code 14 s},不足一秒说 {@code under 1 s}。憋气的几处(回执、结局、计划)都这么说。 */
    public static String seconds(int ticks) {
        return ticks < 20 ? "under 1 s" : Math.round(ticks / 20.0) + " s";
    }

    private static String where(BlockPos from, BlockPos toward) {
        return String.format("from %s toward %s, about %.0f blocks away", from.toShortString(), toward.toShortString(),
                Math.sqrt(from.distSqr(toward)));
    }

    /** 预算内无路:两处回执共用,数字口径一致。 */
    public static String overBudget(int budget, int cheapest) {
        return String.format("no route within max_changes = %d (the cheapest found would change %d blocks)",
                budget, cheapest);
    }

    /** 许可或动手时被拒的理由:权限层的裁决说它自己的话,别的(服务端退回)照实说。 */
    private static String reason(Object refusal) {
        Verdict verdict = CompanionHands.verdict(refusal);
        return verdict != null ? verdict.reason() : "the server would not let it happen";
    }

    /** 一格改不得的原因:许可拒绝的({@code detail} 是它的裁决)说权限层自己的话,别的按 {@link Reason} 说。 */
    public static String refused(Reason reason, Object detail) {
        return reason == Reason.DENIED ? reason(detail) : reason(reason);
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
            case NO_DIGGING -> "it would dig, which this walk may not";
            case NO_PLACING -> "it would place a block, which this walk may not";
            case NO_MATERIALS -> "it needs a block to place and I carry none of the blocks this walk may spend";
            case DENIED -> "changing it is refused";
            case NEEDS_CONSENT -> "changing it needs the owner's consent, and this walk keeps out of such cells";
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
            case OUT_OF_BREATH -> "with the air I have left I could not reach air before drowning";
        };
    }

    private static String hitch(Blockage.Hitch hitch) {
        return switch (hitch) {
            case STUCK -> "the step took far longer than it should, I was stuck";
            case OCCLUDED -> "I could not see the block I had to work on";
            case NO_FACE -> "I could not point at a face to place against";
            case NO_MATERIALS -> "I could not get the block into my hand";
            case DIVERTED -> "the ground changed and the step would land somewhere else";
            case FELL_BACK -> "I kept ending up back where the step before it started";
        };
    }

    // ==================== 实际账与身体动作 ====================

    /**
     * 回执末尾那一段:路上挖了什么、放了什么(与倒下又收回的水),身体为走路做了什么,潜过几段水;什么都没有是空串。
     * 例如 {@code En route I had to break 2 oak_planks (120,64,-33; 120,65,-33) and place 1 cobblestone (121,64,-33).
     * I also stepped off the boat. I went under water once: 12 s without a breath from 10,40,5 to 20,40,5, air down to
     * 60/300.}
     */
    public static String journey(List<EditLedger.Entry> entries, List<BodyAction> actions, List<DiveLog.Dive> dives) {
        String edits = edits(entries);
        String done = actions(actions);
        String under = dives(dives);
        StringBuilder sb = new StringBuilder();
        if (!edits.isEmpty()) {
            sb.append("En route I had to ").append(edits).append('.');
        }
        if (!done.isEmpty()) {
            sb.append(sb.length() > 0 ? " I also " : "En route I ").append(done).append('.');
        }
        if (!under.isEmpty()) {
            sb.append(sb.length() > 0 ? " " : "").append(under);
        }
        return sb.toString();
    }

    /**
     * 路上潜过的水:几段,憋得最久的那一段从哪儿到哪儿、憋了多久、氧气最低到多少。没潜过是空串。例如
     * {@code I went under water 2 times; the longest: 12 s without a breath from 10,40,5 to 20,40,5, air down to 60/300.}
     */
    static String dives(List<DiveLog.Dive> dives) {
        if (dives.isEmpty()) {
            return "";
        }
        DiveLog.Dive longest = dives.get(0);
        for (DiveLog.Dive dive : dives) {
            if (dive.ticks() > longest.ticks()) {
                longest = dive;
            }
        }
        return "I went under water " + (dives.size() == 1 ? "once: " : dives.size() + " times; the longest: ")
                + seconds(longest.ticks()) + " without a breath from " + Listing.coords(longest.from()) + " to "
                + Listing.coords(longest.to()) + ", air down to " + Math.max(0, longest.lowestAir()) + "/"
                + longest.maxAir() + ".";
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

    // ==================== 预算账 ====================


    /**
     * 计划要动什么(预算账),与实际账同一种写法:要挖的格按方块归堆,要问主人的缀上为什么问;要放的格按方块归堆。例如
     * {@code break 2 oak_planks (120,64,-33; 120,65,-33) needing consent (placed by a player); place 1 cobblestone (121,64,-33)}。
     * 一格都不动是 {@code no terrain change}。
     *
     * @param digs   要挖的格 → 那里规划时的方块,按先后
     * @param places 要放的格 → 打算放下的方块,按先后
     * @param asks   其中要问主人的格 → 为什么要问
     */
    public static String planned(Map<BlockPos, Block> digs, Map<BlockPos, Block> places, Map<BlockPos, String> asks) {
        if (digs.isEmpty() && places.isEmpty()) {
            return "no terrain change";
        }
        List<String> parts = new ArrayList<>();
        if (!digs.isEmpty()) {
            parts.add("break " + consented(digs, asks));
        }
        if (!places.isEmpty()) {
            parts.add("place " + consented(places, asks));
        }
        return String.join("; ", parts);
    }

    /**
     * 一条路要改的格,读成三张表:要挖的格 → 那里原来的方块,要放的格 → 放的方块(倒水接坠落记水),其中要问主人的格 → 许可给的
     * 为什么问。规划写计划、回执说要改什么,读的都是这一份。
     */
    public record Changes(Map<BlockPos, Block> digs, Map<BlockPos, Block> places, Map<BlockPos, String> asks) {

        public static Changes of(List<Edit> edits) {
            Map<BlockPos, Block> digs = new LinkedHashMap<>();
            Map<BlockPos, Block> places = new LinkedHashMap<>();
            Map<BlockPos, String> asks = new LinkedHashMap<>();
            for (Edit edit : edits) {
                Permit permit = switch (edit) {
                    case Edit.Dig dig -> {
                        digs.put(dig.pos(), dig.state().getBlock());
                        yield dig.permit();
                    }
                    case Edit.Place place -> {
                        places.put(place.pos(), place.block());
                        yield place.permit();
                    }
                    case Edit.Catch caught -> {
                        places.put(caught.pos(), net.minecraft.world.level.block.Blocks.WATER);
                        yield caught.permit();
                    }
                    case Edit.Door door -> null;
                };
                if (permit instanceof Permit.Ask ask && ask.credential() instanceof ConsentItem item) {
                    asks.put(edit.pos(), item.cause());
                }
            }
            return new Changes(digs, places, asks);
        }
    }

    /** 一条路要改的格,与 {@link #planned(Map, Map, Map)} 同一种写法。 */
    static String planned(List<Edit> edits) {
        Changes changes = Changes.of(edits);
        return planned(changes.digs(), changes.places(), changes.asks());
    }

    /** 按方块归堆,同一种方块里要问主人的按为什么问再分一堆,缀上 {@code needing consent (…)}。 */
    private static String consented(Map<BlockPos, Block> cells, Map<BlockPos, String> asks) {
        Map<String, List<BlockPos>> heaps = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        cells.forEach((pos, block) -> {
            String cause = asks.getOrDefault(pos, "");
            String key = name(block) + '|' + cause;
            heaps.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
            labels.putIfAbsent(key, cause.isEmpty() ? "" : " needing consent (" + cause + ")");
        });
        List<String> parts = new ArrayList<>();
        heaps.forEach((key, at) -> parts.add(Listing.part(key.substring(0, key.indexOf('|')), at.size(), at)
                + labels.get(key)));
        return andJoined(parts);
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

    /**
     * 一句能照抄的去一处,带反引号:{@code `numen.move.to({x = 1, y = 2, z = 3}, {arrive = "use"})`};{@code options} 是路线描述里的那几项,
     * 可以为空。
     */
    public static String gotoCall(Place to, String options) {
        return "`numen.move.to(" + com.dwinovo.numen.sdk.LuaCodecs.literal(to) + (options.isEmpty() ? "" : ", {" + options + "}") + ")`";
    }

    public static String name(BlockState state) {
        return name(state.getBlock());
    }

    public static String name(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static String item(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
