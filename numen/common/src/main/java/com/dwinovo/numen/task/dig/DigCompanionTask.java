package com.dwinovo.numen.task.dig;

import com.dwinovo.numen.api.permission.GatedAct.Permit;
import com.dwinovo.numen.api.permission.GatedAct.PermitState;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.area.Cells;
import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.act.BlockDigger;
import com.dwinovo.numen.nav.DigQuote;
import com.dwinovo.numen.nav.NavText;
import com.dwinovo.numen.nav.Feet;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.base.Precondition;
import com.dwinovo.numen.task.move.Arrival;
import com.dwinovo.numen.task.move.Destination;
import com.dwinovo.numen.api.entity.Harvest;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.Listing;
import com.dwinovo.numen.api.permission.Verdict;
import com.dwinovo.numen.api.task.Preparation;
import com.dwinovo.numen.api.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code numen.work.dig}:挖点名的那些格。一条原子命令,对一个名词(点名的几处)做一种意图(挖):
 * <ul>
 *   <li>挖手够得着的——够不够得着与走过去挖一格的寻路目标是同一个判据({@link Goals#reach}:够得着、身体不占着它),中间隔着什么
 *       不问;手边一格都够不着就先走到够得着剩下最多格的地方({@link #walkTo},到达目标是 {@link Arrival#dig}),挖完够得着的再走,
 *       到挖完或真到不了;走到了仍够不着的,回执说还剩几格、最近一格在哪;</li>
 *   <li>挖出来的东西当场进她背包,放不下的留在原地(这是身体的事,{@link Harvest});这件活只开一本账,回执里说这一趟得到了什么、
 *       哪些放不下留在了哪。</li>
 * </ul>
 * 每一格都经她的手(原版挖掘循环外套权限层),用工具、有掉落、进实际账。点名的目标格本身要主人同意时,动手之前问({@link #permit}):
 * 要问就站着等主人点头,不许就带着理由收场。
 *
 * <p>受理 = 此刻真能开始:受理之前({@link #preparation})判有没有手够得着、挖得成、工具收得到、许挖的格,或者有没有能走去挖的;都没有
 * 就当场拒,说清楚为什么,没有任务编号。
 */
public final class DigCompanionTask extends AbstractCompanionTask<DigTaskRecord, DigCompanionTask.Dug> {

    /** 找手够得着的格只翻脚下这一圈:交互距离加眼高再多两格,够得着的格不会在它外面。 */
    private static final int REACH_SPAN = 8;

    private final BlockDigger digger;
    /** 给目标定价、判挖不挖得成的成本模型({@link DigTaskRecord#TARGET_SPEC}),每刻按此刻的身体与权限重组。 */
    private DigQuote pricing;

    /** 挖掉了的点名格,按挖掉的先后。 */
    private final Set<BlockPos> dug = new LinkedHashSet<>();
    /** 手里的工具收不到掉落的格。 */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** 物理上挖不成的格:挖不动、贴着流体、顶着落沙。 */
    private final Set<BlockPos> ruledOut = new HashSet<>();
    /** 许可不许挖目标时说的理由(最近一格的);没有为 null。 */
    private String deniedWhy;

    /** 正在走去挖的那些格(够不着的);没在走是 null。 */
    private List<BlockPos> walkingTo;
    /** 刚走到:这一趟走完还是挑不出手边的格,就不再走了(走到了够得着剩下最多格的地方,还是挖不了)。 */
    private boolean justWalked;

    /** 正在挖的目标;没有为 null。 */
    private BlockPos digTarget;

    /** 这件活里她的手挖出来的东西(连同走路途中挖的)记在这本账上:开工时开,收尾时收。 */
    private Harvest.Ledger harvest;

    public DigCompanionTask(NumenPlayer player, DigTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
    }

    @Override
    protected List<Precondition> preconditions() {
        // 一种都收不到掉落就当场失败:挖了只会把方块毁掉。个别收不到的格挑目标时剔掉,所以混着的(煤挖得了、钻石挖不了)照挖
        return List.of(() -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean any = r.targets.stream().anyMatch(
                    b -> DigTaskRecord.harvestable(player.getInventory(), b.defaultBlockState()));
            return any ? null : new Precondition.Failure(noTool(), FailureType.WRONG_TOOL);
        });
    }

    /**
     * 受理之前:按此刻的身体与权限定价,看手够得着的格里有没有挖得成、工具收得到、许挖的;没有就看够不着的格里有没有能走去挖的
     * (走路的到达目标编得成)。有就受理;都没有就当场拒,说的是开工后收工时同一句话。
     */
    @Override
    protected Preparation preparation() {
        quote();
        if (next() != null) {
            return Preparation.READY;
        }
        List<BlockPos> beyond = beyondReach();
        if (!beyond.isEmpty()) {
            try {
                Destination.of(player, Arrival.dig(beyond), Feet.cell(player));
                return Preparation.READY;
            } catch (IllegalArgumentException cannot) {
                return Preparation.refused(TaskResult.fail(ErrorKind.FAILED, nothingHere(true) + " " + cannot.getMessage(),
                        null, value()));
            }
        }
        return Preparation.refused(TaskResult.fail(nothingHereKind(), nothingHere(true), null, value()));
    }

    /** 手边没有可挖的那一刻是哪一类失败:目标本身不许挖是 denied,工具不对、挖不成是 failed,其余是够不着。 */
    private ErrorKind nothingHereKind() {
        if (deniedWhy != null) {
            return ErrorKind.DENIED;
        }
        if (!unharvestable.isEmpty() || !ruledOut.isEmpty() || Feet.of(player) == null) {
            return ErrorKind.FAILED;
        }
        return ErrorKind.OUT_OF_REACH;
    }

    /** 还要挖、站在这儿够不着、也没被判过挖不成(挖不动、工具收不到)的格,由近及远:走过去挖的就是它们。 */
    private List<BlockPos> beyondReach() {
        Feet here = Feet.of(player);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : wanted()) {
            if (!reaches(here, cell) && !ruledOut.contains(cell) && !unharvestable.contains(cell)) {
                out.add(cell);
                if (out.size() == MAX_WALK_TARGETS) {
                    break;
                }
            }
        }
        return out;
    }

    /** 一次走去挖最多点名几格(由近及远):寻路的目标也只取最近的这么多。 */
    private static final int MAX_WALK_TARGETS = 64;

    /** 够不着的格里挖了不用问主人的、挖得成的:手边这一格要问主人时,先走去挖它们。 */
    private List<BlockPos> unaskedBeyond() {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : beyondReach()) {
            BlockState state = player.level().getBlockState(cell);
            if (pricing.breakable(cell, state) && !pricing.asks(cell, state)
                    && Double.isFinite(pricing.price(cell, state).cost())) {
                out.add(cell);
            }
        }
        return out;
    }

    @Override
    protected void onStart() {
        com.dwinovo.numen.Constants.LOG.info("[numen-task] dig start targets={} count={} what={} feet={}",
                r.label, r.count, r.what, player.blockPosition().toShortString());
        harvest = player.harvest().open();
    }

    @Override
    protected TaskState onTick() {
        r.setDug(dug.size());
        if (r.count != DigTaskRecord.ALL && dug.size() >= r.count) {
            return TaskState.SUCCESS;
        }
        quote();
        if (walkingTo != null) {
            return walking();
        }
        Level level = player.level();
        // 接着挖正在挖的那一格,锁住它直到碎掉或她站的地方够不着了
        if (digTarget != null) {
            // 这一格送过一次 STOP、方块还在时,它随时可能没了:那一刻它不再"要挖",但那是挖掉了,由鼠标报,不能当"不用挖了"丢掉
            if (digger.stopSent()
                    || (r.wantsAt(digTarget, level.getBlockState(digTarget)) && reaches(Feet.of(player), digTarget))) {
                return digProgress(digTarget);
            }
            digger.cancel();
            digTarget = null;
        }
        if (!bodySettled()) {
            // 脚下那一格刚挖掉、正往下落:落定了再从新的站处看(浮在水面上不是在落)
            return TaskState.RUNNING;
        }
        BlockPos target = next();
        if (target != null && !justWalked) {
            // 手边这一格要问主人(同一套定价里贵十倍)而别处有不用问的:先走去挖不用问的,不剔除要问的
            List<BlockPos> unasked = pricing.asks(target, level.getBlockState(target)) ? unaskedBeyond() : List.of();
            if (!unasked.isEmpty()) {
                walkingTo = unasked;
                return walking();
            }
        }
        if (target == null) {
            List<BlockPos> beyond = justWalked ? List.of() : beyondReach();
            if (!beyond.isEmpty()) {
                walkingTo = beyond;
                return walking();
            }
            if (dug.isEmpty()) {
                fail(nothingHere(false) + harvestLine(), FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        justWalked = false;
        // 动手之前:这一格交给权限层。要问就站着等主人,不许就带着理由收场
        Permit permit = gate().permit(Action.breakBlock(target, level.getBlockState(target)));
        if (permit.state() == PermitState.WAITING) {
            player.controls().stop();
            return TaskState.RUNNING;
        }
        if (permit.state() == PermitState.REFUSED) {
            fail("did not dig " + Listing.coords(target) + ": " + permit.refusal() + "; " + tally() + "." + harvestLine(),
                    FailureType.REFUSED);
            return TaskState.FAILED;
        }
        return digProgress(target);
    }

    // ---- 走去挖 ----

    /**
     * 走到够得着剩下最多格的地方({@link Arrival#dig} 挑):走完了回来再挖手边够得着的。走不到(没路、被拒、超时)就带着那一趟的
     * 原因收场,挖到的照实交代。
     */
    private TaskState walking() {
        TaskResult walked = walkTo(Arrival.dig(walkingTo));
        if (walked == null) {
            return TaskState.RUNNING;
        }
        walkingTo = null;
        if (!walked.success()) {
            fail(walked.message() + "; " + tally() + "." + harvestLine(), lastFailure());
            return TaskState.FAILED;
        }
        justWalked = true;
        return TaskState.RUNNING;
    }

    // ---- 挑哪一格 ----

    /** 按此刻的身体与权限重组给目标定价的成本模型。 */
    private void quote() {
        pricing = DigQuote.of(player, DigTaskRecord.TARGET_SPEC);
    }

    /** 站在 {@code here} 手够不够得着 {@code cell}(够得着、身体不占着它):与走过去挖它的到达目标是同一个({@link Goals#reach})。 */
    private boolean reaches(Feet here, BlockPos cell) {
        return here != null && here.in(Goals.reach(cell, Snapshots.stats(player)));
    }

    /**
     * 下一格挖哪一格:此刻还要挖、手够得着、挖得成、工具收得到、许挖的格里价钱最低的,一样贵挑近的;一格都没有为 null。收不进的
     * 顺手记账(工具收不到、挖不成),回执交代。
     */
    private BlockPos next() {
        Feet here = Feet.of(player);
        if (here == null) {
            return null;
        }
        Level level = player.level();
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        BlockPos best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (BlockPos cell : wantedInReach(here)) {
            BlockState state = level.getBlockState(cell);
            if (!pricing.breakable(cell, state)) {
                ruledOut.add(cell);
                continue;
            }
            if (!WorkProfile.of(player).instaBreak() && !DigTaskRecord.harvestable(player.getInventory(), state)) {
                unharvestable.add(cell);
                continue;
            }
            DigQuote.Price price = pricing.price(cell, state);
            if (!Double.isFinite(price.cost())) {
                deniedWhy = price.refusal() instanceof Verdict verdict ? verdict.reason()
                        : "the permission layer does not allow it";
                continue;
            }
            if (price.cost() < bestCost || (price.cost() == bestCost && cell.distSqr(eye) < best.distSqr(eye))) {
                best = cell;
                bestCost = price.cost();
            }
        }
        return best;
    }

    /** 点名的格里此刻还要挖、站在 {@code here} 手够得着的那些,由近及远。只翻脚下 {@link #REACH_SPAN} 这一圈。 */
    private List<BlockPos> wantedInReach(Feet here) {
        if (here == null) {
            return List.of();
        }
        Level level = player.level();
        List<BlockPos> out = new ArrayList<>();
        Cells.sphere(here.node(), REACH_SPAN).forEach((x, y, z, unused) -> {
            BlockPos p = new BlockPos(x, y, z);
            if (r.cells.contains(p) && DigTaskRecord.wants(r.cells.seenAt(p), level.getBlockState(p))
                    && reaches(here, p)) {
                out.add(p);
            }
        });
        out.sort(Comparator.comparingDouble(here.node()::distSqr));
        return out;
    }

    /** 点名的格里此刻还要挖的那些({@link DigTaskRecord#wants}),由近及远:整块翻一遍,只在回执里用。 */
    private List<BlockPos> wanted() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        r.cells.forEach((x, y, z, seen) -> {
            BlockPos p = new BlockPos(x, y, z);
            if (DigTaskRecord.wants(seen, level.getBlockState(p))) {
                out.add(p);
            }
        });
        out.sort(Comparator.comparingDouble(feet::distSqr));
        return out;
    }

    // ---- 挖 ----

    /** 挖一刻(挖掘器自己把挖它最快的那件拿到手上);碎掉的那一刻记进挖掉的账。 */
    private TaskState digProgress(BlockPos pos) {
        digTarget = pos.immutable();
        switch (digger.digStep(pos, this::recordAction)) {
            case BROKE -> {
                digTarget = null;
                dug.add(pos.immutable());
            }
            case REFUSED -> {
                // 动手前放行之后世界变了:权限层或服务端的拒绝就是这件活的结果
                fail("did not dig " + Listing.coords(pos) + ": " + digger.refusal().reason() + "; " + tally() + "."
                        + harvestLine(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            // 身体被挪开了、这一下没按:放下它,下一刻按此刻站的地方重挑
            case OUT_OF_REACH -> digTarget = null;
            case PROGRESSING -> {
            }
        }
        return TaskState.RUNNING;
    }

    // ---- 回执 ----

    /** 够不着的那一截(以 {@code "; "} 起头):还剩几格够不着、最近一格在哪;都够得着是空串。 */
    private String outOfReach() {
        Feet here = Feet.of(player);
        List<BlockPos> beyond = new ArrayList<>();
        for (BlockPos cell : wanted()) {
            if (!reaches(here, cell)) {
                beyond.add(cell);
            }
        }
        if (beyond.isEmpty()) {
            return "";
        }
        BlockPos near = beyond.get(0);
        long blocks = Math.round(Math.sqrt(player.blockPosition().distSqr(near)));
        return "; " + beyond.size() + " more cell(s) of " + r.what + " are out of my reach from here, the nearest at "
                + Listing.coords(near) + " about " + blocks + " blocks away";
    }

    /**
     * 手边没有可挖的了(受理时就没有,或开工后一格没挖成)时说的那句话:按缘由——目标本身不许挖、工具收不到、挖不成,或者够得着的
     * 一格都没有;接着是够不着的在哪、下一步怎么写。
     *
     * @param before 受理之前说(还没开工)
     */
    private String nothingHere(boolean before) {
        String head = before ? "I did not start: " : "I dug nothing: ";
        Feet here = Feet.of(player);
        String why;
        if (deniedWhy != null) {
            why = "breaking the " + wantedInReach(here).size() + " cell(s) of " + r.what + " within my reach is refused: "
                    + deniedWhy;
        } else if (!unharvestable.isEmpty()) {
            why = noTool();
        } else if (!ruledOut.isEmpty()) {
            BlockPos first = ruledOut.iterator().next();
            String refused = pricing.whyNot(first);
            why = refused != null
                    ? com.dwinovo.numen.task.move.GotoReminders.cantDig(first, NavText.name(player.level().getBlockState(first)),
                            refused) + (ruledOut.size() > 1 ? " (and " + (ruledOut.size() - 1) + " more)" : "")
                    : ruledOut.size() + " cell(s) of " + r.what + " within my reach can't be broken here (unbreakable, or "
                            + "fluid or loose falling blocks beside them)";
        } else if (here == null) {
            why = "I am not standing anywhere (falling or stuck in a block), so nothing is within reach";
        } else {
            why = "none of the cells of " + r.what + " still to dig is within my reach where I stand";
        }
        return head + why + outOfReach() + leftovers() + ".";
    }

    /** 工具收不到掉落时说的那句:要什么、下一步。 */
    private String noTool() {
        return "my tools can't harvest " + r.label + " — digging it would destroy it without any drop. Equip a "
                + "suitable tool (numen.gear.hold, e.g. a pickaxe) first; to break a block regardless of drops, "
                + "numen.use.hit(pos) on it with whatever is in hand";
    }

    /** 要挖却没挖成的各因为什么(以 {@code "; "} 起头);都没有是空串。 */
    private String leftovers() {
        List<String> parts = new ArrayList<>(2);
        if (!unharvestable.isEmpty()) {
            parts.add(unharvestable.size() + " can't be harvested with my tools");
        }
        if (!ruledOut.isEmpty()) {
            parts.add(ruledOut.size() + " can't be broken here");
        }
        return parts.isEmpty() ? "" : "; not dug: " + String.join(", ", parts);
    }

    /** {@code dug 3 cells of iron_ore}。 */
    private String tally() {
        return "dug " + dug.size() + " cell(s) of " + r.label;
    }

    /**
     * 这一趟挖到了什么那一句(以空格起头):进了背包的各几个,放不下留在原地的各几个、在哪、下一步;挖掉了格却什么都没掉也说一句;
     * 一格没挖、什么都没记是空串。和交回的值({@link #value})读的是同一本账。
     */
    private String harvestLine() {
        if (harvest == null) {
            return "";
        }
        List<Got> got = got();
        List<Stayed> stayed = stayed();
        if (got.isEmpty() && stayed.isEmpty()) {
            return dug.isEmpty() ? "" : " Nothing dropped.";
        }
        StringBuilder out = new StringBuilder();
        if (!got.isEmpty()) {
            List<String> parts = new ArrayList<>(got.size());
            for (Got g : got) {
                parts.add(g.count() + " " + path(g.item()));
            }
            out.append(" Into my inventory: ").append(String.join(", ", parts)).append('.');
        }
        if (!stayed.isEmpty()) {
            List<String> parts = new ArrayList<>(3);
            for (Stayed s : stayed.subList(0, Math.min(3, stayed.size()))) {
                parts.add(s.count() + " " + path(s.item()) + " at " + LuaCodecs.literal(s.pos()));
            }
            int more = stayed.size() - parts.size();
            out.append(" Did not fit in my inventory and stayed on the ground: ").append(String.join(", ", parts))
                    .append(more > 0 ? " and " + more + " more spot(s)" : "")
                    .append(". With room made (numen.inv.drop, or numen.container.put into a chest), numen.work.collect() "
                            + "picks them up.");
        }
        return out.toString();
    }

    /** 物品 id 去掉命名空间的那一截,回执里用。 */
    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    /** 这一趟进了背包的,按第一次挖到的先后。 */
    private List<Got> got() {
        List<Got> out = new ArrayList<>();
        harvest.taken().forEach((item, count) -> out.add(new Got(BuiltInRegistries.ITEM.getKey(item).toString(), count)));
        return out;
    }

    /** 这一趟放不下、留在原地的,按先后。 */
    private List<Stayed> stayed() {
        List<Stayed> out = new ArrayList<>();
        for (Harvest.Left left : harvest.left()) {
            out.add(new Stayed(BuiltInRegistries.ITEM.getKey(left.item()).toString(), left.count(), left.pos()));
        }
        return out;
    }

    /** 这本账在回执拼之前收口:回执和交回的值都读这一份。 */
    @Override
    protected void closeBooks() {
        if (harvest != null) {
            harvest.close();
        }
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        digger.cancel();
    }

    /** 进了背包的一样东西。 */
    @com.dwinovo.numen.api.sdk.Doc("Something the dig put into your inventory.")
    public record Got(@com.dwinovo.numen.api.sdk.Doc("The item id, minecraft:raw_iron.") String item,
                      @com.dwinovo.numen.api.sdk.Doc("How many.") int count) {}

    /** 放不下、留在原地的一笔。 */
    @com.dwinovo.numen.api.sdk.Doc("Something the dig dug out that did not fit in your inventory.")
    public record Stayed(@com.dwinovo.numen.api.sdk.Doc("The item id, minecraft:cobblestone.") String item,
                         @com.dwinovo.numen.api.sdk.Doc("How many.") int count,
                         @com.dwinovo.numen.api.sdk.Doc("The cell it stayed in.") BlockPos pos) {}

    /** {@code numen.work.dig} 交回的值。 */
    @com.dwinovo.numen.api.sdk.Doc("What a dig did.")
    public record Dug(@com.dwinovo.numen.api.sdk.Doc("Cells it dug.") int dug,
                      @com.dwinovo.numen.api.sdk.Doc("Cells of what you gave still to dig.") int left,
                      @com.dwinovo.numen.api.sdk.Doc("Of those, how many your hand does not reach from where you stand.")
                      int outOfReach,
                      @com.dwinovo.numen.api.sdk.Doc("The nearest of those out of reach.") java.util.Optional<BlockPos> nearest,
                      @com.dwinovo.numen.api.sdk.Doc("What it dug out that went into your inventory, the walk's digging "
                              + "included.") List<Got> got,
                      @com.dwinovo.numen.api.sdk.Doc("What it dug out that did not fit in your inventory and stayed on the "
                              + "ground where it dropped.") List<Stayed> onGround) {}

    /**
     * 挖了几格、点名的里还剩几格要挖、其中几格站在这儿够不着、够不着里最近的那一格、这一趟挖到的东西进了背包的与留在原地的
     * (和回执那一句同一本账)。
     */
    @Override
    protected Dug value() {
        Feet here = Feet.of(player);
        List<BlockPos> left = wanted();
        BlockPos near = null;
        int beyond = 0;
        for (BlockPos cell : left) {
            if (!reaches(here, cell)) {
                beyond++;
                if (near == null) {
                    near = cell;
                }
            }
        }
        return new Dug(dug.size(), left.size(), beyond, java.util.Optional.ofNullable(near),
                harvest == null ? List.of() : got(), harvest == null ? List.of() : stayed());
    }

    /** 收工:挖了几格;手边还能挖却因为 {@code count} 停下的说一句;够不着的在哪、怎么去;这一趟挖到了什么。 */
    @Override
    protected String successMessage() {
        String stopped = r.count != DigTaskRecord.ALL && dug.size() >= r.count ? " (the count " + r.count
                + " I was given)" : "";
        return tally() + stopped + outOfReach() + leftovers() + "." + harvestLine();
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after I " + tally() + outOfReach() + "." + harvestLine();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after I " + tally() + "." + harvestLine();
    }
}
