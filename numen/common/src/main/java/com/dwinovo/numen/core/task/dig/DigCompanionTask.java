package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.act.Drops;
import com.dwinovo.numen.core.nav.DigQuote;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code numen.work.dig}:挖她<b>站在原地手够得着</b>的那些格。一条原子命令,对一个名词(点名的几处)做一种意图(挖):
 * <ul>
 *   <li>只挖手够得着的——站位就是此刻脚下,够不够得着与挖一格的寻路目标是同一个判据({@link Goals#dig}:够得着、身体不占着它);
 *       够不着的不走过去,回执说还剩几格、最近一格在哪、能照抄的 {@code numen.move.to(…, {arrive = "dig"})};</li>
 *   <li>挡在前面的格一并挖开:挖掘器朝隔着的格都清得掉、挡得最少的那一点看过去,准星落在的那一格先挖({@link BlockDigger})。清不清得掉
 *       按 {@link DigTaskRecord#SPEC} 问({@link DigQuote#clearing}):天然地形挖开,要主人同意的、规则不许的不挖,如实说是哪一格、
 *       为什么({@link DigQuote#walledIn});</li>
 *   <li>不走动、不捡:收工之前等这一挖的掉落物落定({@link Drops}:落地、被捡起、被毁、掉进虚空,最多
 *       {@link Drops#SETTLE_TICKS} 刻),结果里写明各去向的件数与位置;落在地上的由 {@code numen.work.collect} 去捡。</li>
 * </ul>
 * 每一格都经她的手(原版挖掘循环外套权限层),用工具、有掉落、进实际账。点名的目标格本身要主人同意时,动手之前问({@link #permit}):
 * 要问就站着等主人点头,不许就带着理由收场。
 *
 * <p>受理 = 此刻真能开始:受理之前({@link #preparation})判有没有手够得着、挖得成、工具收得到、许挖的格;一格都没有就当场拒,说清楚
 * 够不着的在哪、下一步怎么写,没有任务编号。
 */
public final class DigCompanionTask extends AbstractCompanionTask<DigTaskRecord> {

    /**
     * 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable}:站位说够得着,可射线始终成不了(挡在中间的挖不得、瞄准量化)。
     * 没有这条,挖掘会永远等一个不会来的射线。
     */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /** 找手够得着的格只翻脚下这一圈:交互距离加眼高再多两格,够得着的格不会在它外面。 */
    private static final int REACH_SPAN = 8;

    private final BlockDigger digger;
    /** 给目标定价、判挖不挖得成的成本模型({@link DigTaskRecord#TARGET_SPEC}),每刻按此刻的身体与权限重组。 */
    private DigQuote pricing;
    /** 挡着视线的格清不清得掉:按 {@link DigTaskRecord#SPEC} 问,与每刻的 {@link #pricing} 一起重组。 */
    private DigQuote clearing;

    /** 挖掉了的点名格,按挖掉的先后(为拉出射线挖开的遮挡也是点名格的算在里面,别的进实际账)。 */
    private final Set<BlockPos> dug = new LinkedHashSet<>();
    /** 手里的工具收不到掉落的格。 */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** 物理上挖不成的格:挖不动、贴着流体、顶着落沙。 */
    private final Set<BlockPos> ruledOut = new HashSet<>();
    /** 拉不出射线的格({@link #MAX_NO_SHOT_TICKS});挖掉任何一格地形就变了,整份作废重来。 */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** 许可不许挖目标时说的理由(最近一格的);没有为 null。 */
    private String deniedWhy;

    /** 正在挖的目标;挖掘器这一刻可能在挖挡在它前面的那一格,锁的是目标。没有为 null。 */
    private BlockPos digTarget;
    private BlockPos noShotPos;
    private int noShotTicks;

    /** 这一挖的掉落物记在这本账上:开工时开,收尾时收。 */
    private Drops drops;
    /** 活干完了、在等掉落物落定:等完收在这个终态上;还在干是 null。 */
    private TaskState ending;
    /** 等完之后收场要做的(失败时记下那句话);没有是 null。 */
    private Runnable endWith;
    /** 已经等了几刻。 */
    private int settleTicks;

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
     * 受理之前:按此刻的身体与权限定价,看手够得着的格里有没有挖得成、工具收得到、许挖、挡着的都清得掉的。有就受理,回执说够得着几格、
     * 够不着几格;一格都没有就当场拒,说的是开工后收工时同一句话。
     */
    @Override
    protected Preparation preparation() {
        quote();
        if (next() != null) {
            return Preparation.READY;
        }
        BlockPos near = nearestBeyond();
        return Preparation.refused(TaskResult.fail(nothingHereKind(), nothingHere(true), near == null ? null
                : DigTaskRecord.reachLine(r.named, near), value()));
    }

    /** 手边没有可挖的那一刻是哪一类失败:被不许挖的挡着、目标本身不许挖是 denied,工具不对、挖不成是 failed,其余是够不着。 */
    private ErrorKind nothingHereKind() {
        Feet here = Feet.of(player);
        for (BlockPos cell : wantedInReach(here)) {
            if (clearing.walledIn(cell) != null) {
                return ErrorKind.DENIED;
            }
        }
        if (deniedWhy != null) {
            return ErrorKind.DENIED;
        }
        if (!unharvestable.isEmpty() || !ruledOut.isEmpty() || here == null) {
            return ErrorKind.FAILED;
        }
        return ErrorKind.OUT_OF_REACH;
    }

    /** 还要挖、站在这儿够不着的格里离她最近的那一格;都够得着(或一格不剩)是 null。 */
    private BlockPos nearestBeyond() {
        Feet here = Feet.of(player);
        for (BlockPos cell : wanted()) {
            if (!reaches(here, cell)) {
                return cell;
            }
        }
        return null;
    }

    @Override
    protected void onStart() {
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] dig start targets={} count={} what={} feet={}",
                r.label, r.count, r.what, player.blockPosition().toShortString());
        drops = Drops.open(player);
    }

    @Override
    protected TaskState onTick() {
        drops.tick();
        if (ending != null) {
            return settle();
        }
        r.setDug(dug.size());
        if (r.count != DigTaskRecord.ALL && dug.size() >= r.count) {
            return end(TaskState.SUCCESS, null);
        }
        quote();
        Level level = player.level();
        // 接着挖正在挖的那一格,锁住它直到碎掉或她站的地方够不着了
        if (digTarget != null) {
            if (r.wantsAt(digTarget, level.getBlockState(digTarget)) && workable(Feet.of(player), digTarget)) {
                return digProgress(digTarget);
            }
            digger.cancel();
            digTarget = null;
        }
        if (!player.onGround()) {
            // 脚下那一格刚挖掉、正往下落:落定了再从新的站处看
            return TaskState.RUNNING;
        }
        BlockPos target = next();
        if (target == null) {
            if (dug.isEmpty()) {
                return end(TaskState.FAILED, () -> {
                    BlockPos near = nearestBeyond();
                    fail(nothingHere(false) + dropsLine(), FailureType.OUT_OF_REACH, near == null ? null
                            : DigTaskRecord.reachLine(r.named, near));
                });
            }
            return end(TaskState.SUCCESS, null);
        }
        // 动手之前:这一格交给权限层。要问就站着等主人,不许就带着理由收场
        Permit permit = permit(Action.breakBlock(target, level.getBlockState(target)));
        if (permit.state() == PermitState.WAITING) {
            player.controls().stop();
            return TaskState.RUNNING;
        }
        if (permit.state() == PermitState.REFUSED) {
            String why = "did not dig " + Listing.coords(target) + ": " + permit.refusal() + "; ";
            return end(TaskState.FAILED, () -> fail(why + tally() + "." + dropsLine(), FailureType.REFUSED));
        }
        return digProgress(target);
    }

    // ---- 收工:等这一挖的掉落物落定 ----

    /**
     * 活干完了:松手站住,等这一挖的掉落物落定再以 {@code state} 收场;{@code then} 在收场那一刻做(失败时记下那句话,掉落物的去向
     * 那时才齐)。
     */
    private TaskState end(TaskState state, Runnable then) {
        digger.cancel();
        ending = state;
        endWith = then;
        return settle();
    }

    /**
     * 等的这一刻:都落定了、或等满 {@link Drops#SETTLE_TICKS} 刻,就收账收场——先收账,还在动的记成还在动,失败那句话与收尾的
     * 数据读的是同一份记录。
     */
    private TaskState settle() {
        player.controls().stop();
        if (!drops.settled() && settleTicks++ < Drops.SETTLE_TICKS) {
            return TaskState.RUNNING;
        }
        drops.close();
        if (endWith != null) {
            endWith.run();
        }
        return ending;
    }

    // ---- 挑哪一格 ----

    /** 按此刻的身体与权限重组给目标定价与判遮挡的两份成本模型。 */
    private void quote() {
        pricing = DigQuote.of(player, DigTaskRecord.TARGET_SPEC);
        clearing = DigQuote.of(player, DigTaskRecord.SPEC);
    }

    /** 挖 {@code cell} 的目标:挡着视线的格按 {@link #clearing} 清——判够不够得着、站在这儿办不办得成、挖掘器清遮挡都是它。 */
    private Goal digGoal(BlockPos cell) {
        return Goals.dig(cell, Snapshots.stats(player), clearing.clearing());
    }

    /** 站在 {@code here} 手够不够得着 {@code cell}(够得着、身体不占着它)。 */
    private boolean reaches(Feet here, BlockPos cell) {
        return here != null && here.in(digGoal(cell));
    }

    /** 站在 {@code here} 挖不挖得了 {@code cell}:够得着,而且看得见它的面里至少有一面隔着的格都清得掉。 */
    private boolean workable(Feet here, BlockPos cell) {
        if (!reaches(here, cell)) {
            return false;
        }
        BlockPos node = here.node();
        return Double.isFinite(digGoal(cell).arrival(new LiveWorld(player.serverLevel()), node.getX(), node.getY(),
                node.getZ(), here.stance()));
    }

    /**
     * 下一格挖哪一格:此刻还要挖、手够得着、挖得成、工具收得到、许挖、站在这儿办得成的格里价钱最低的,一样贵挑近的;一格都没有为
     * null。收不进的顺手记账(别人动过、工具收不到、挖不成),回执交代。
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
            if (unworkable.contains(cell)) {
                continue;
            }
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
            if (!workable(here, cell)) {
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

    /**
     * 挖一刻(挖掘器自己把挖它最快的那件拿到手上);目标碎掉的那一刻记进挖掉的账。{@code BROKE_OCCLUDER}(为拉出射线挖开的那一格)
     * 本身是点名要挖的就记进挖掉的账,否则进实际账,目标留着。连续的 {@code NO_SHOT} 满 {@link #MAX_NO_SHOT_TICKS} 就把那一格记进 {@link #unworkable}。
     */
    private TaskState digProgress(BlockPos pos) {
        digTarget = pos.immutable();
        switch (digger.digStep(pos, clearing::clears, this::recordAction)) {
            case BROKE_TARGET -> {
                digTarget = null;
                dug.add(pos.immutable());
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"拉不出射线"结论全部作废
                unworkable.clear();
                clearNoShot();
            }
            case REFUSED -> {
                // 动手前放行之后世界变了,或挡在前面的遮挡物不许挖:权限层的拒绝就是这件活的结果
                String why = "did not dig " + Listing.coords(pos) + ": " + digger.refusal().reason() + "; ";
                return end(TaskState.FAILED, () -> fail(why + tally() + "." + dropsLine(), FailureType.REFUSED));
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(pos.immutable());
                        digger.cancel();
                        digTarget = null;
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            case BROKE_OCCLUDER -> {
                // 挡在前面的那一格本身也是点名要挖的,就记进挖掉的账;否则进实际账
                BlockDigger.Broken broken = digger.lastBroken();
                if (r.wantsAt(broken.pos(), broken.was())) {
                    dug.add(broken.pos().immutable());
                } else {
                    recordBreak(broken);
                }
                clearNoShot();
            }
            default -> clearNoShot();
        }
        return TaskState.RUNNING;
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- 回执 ----

    /**
     * 够不着的那一截(以 {@code "; "} 起头):还剩几格够不着、最近一格在哪、能照抄的下一步;都够得着是空串。
     *
     * @param done 这件活收场时说(那时下一步写成"再挖");受理时说的是这一刻
     */
    private String outOfReach(boolean done) {
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
                + Listing.coords(near) + " about " + blocks + " blocks away" + (done ? " — to dig them: "
                + DigTaskRecord.reachThem(r.named, near) : "");
    }

    /**
     * 手边没有可挖的了(受理时就没有,或开工后一格没挖成)时说的那句话:按缘由——够得着的被不许挖的格挡住、目标本身不许挖、工具收不到、
     * 挖不成、拉不出射线,或者够得着的一格都没有;接着是够不着的在哪、下一步怎么写。
     *
     * @param before 受理之前说(还没开工)
     */
    private String nothingHere(boolean before) {
        String head = before ? "I did not start: " : "I dug nothing: ";
        Feet here = Feet.of(player);
        List<BlockPos> reachable = wantedInReach(here);
        String walled = null;
        for (BlockPos cell : reachable) {
            walled = clearing.walledIn(cell);
            if (walled != null) {
                break;
            }
        }
        String why;
        if (walled != null) {
            why = walled;
        } else if (deniedWhy != null) {
            why = "breaking the " + reachable.size() + " cell(s) of " + r.what + " within my reach is refused: "
                    + deniedWhy;
        } else if (!unharvestable.isEmpty()) {
            why = noTool();
        } else if (!ruledOut.isEmpty()) {
            why = ruledOut.size() + " cell(s) of " + r.what + " within my reach can't be broken here (unbreakable, or "
                    + "fluid or loose falling blocks beside them)";
        } else if (!unworkable.isEmpty()) {
            why = "I found no clear shot at the " + unworkable.size() + " cell(s) of " + r.what + " within my reach";
        } else if (here == null) {
            why = "I am not standing anywhere (falling or stuck in a block), so nothing is within reach";
        } else {
            why = "none of the cells of " + r.what + " still to dig is within my reach where I stand";
        }
        return head + why + outOfReach(false) + leftovers() + ".";
    }

    /** 工具收不到掉落时说的那句:要什么、下一步。 */
    private String noTool() {
        return "my tools can't harvest " + r.label + " — digging it would destroy it without any drop. Equip a "
                + "suitable tool (numen.gear.hold, e.g. a pickaxe) first; to break a block regardless of drops, "
                + "numen.use.hit(pos) on it with whatever is in hand";
    }

    /** 要挖却没挖成的各因为什么(以 {@code "; "} 起头);都没有是空串。 */
    private String leftovers() {
        List<String> parts = new ArrayList<>(3);
        if (!unharvestable.isEmpty()) {
            parts.add(unharvestable.size() + " can't be harvested with my tools");
        }
        if (!unworkable.isEmpty()) {
            parts.add(unworkable.size() + " gave no clear shot from where I stand");
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
     * 掉落物去了哪那一句(以空格起头):各去向的件数与位置({@link Drops#sentence});挖掉了格却什么都没掉也说一句;一格没挖、什么都
     * 没记是空串。
     */
    private String dropsLine() {
        if (drops == null || !drops.any()) {
            return dug.isEmpty() ? "" : " Nothing dropped.";
        }
        return " " + drops.sentence();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        digger.cancel();
        if (drops != null) {
            drops.close();
        }
    }

    /** {@code numen.work.dig} 交回的值。 */
    @com.dwinovo.numen.sdk.Doc("What a dig did.")
    public record Dug(@com.dwinovo.numen.sdk.Doc("Cells it dug.") int dug,
                      @com.dwinovo.numen.sdk.Doc("Cells of what you gave still to dig.") int left,
                      @com.dwinovo.numen.sdk.Doc("Of those, how many your hand does not reach from where you stand.")
                      int outOfReach,
                      @com.dwinovo.numen.sdk.Doc("The nearest of those out of reach.") java.util.Optional<BlockPos> nearest,
                      @com.dwinovo.numen.sdk.Doc("Where what it dug dropped went.") List<Drops.Drop> drops) {}

    /**
     * 挖了几格、点名的里还剩几格要挖、其中几格站在这儿够不着、够不着里最近的那一格、掉落物各去了哪({@link Drops#data},和回执那一句
     * 同一份记录)。
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
                drops == null ? List.of() : drops.data());
    }

    /** 收工:挖了几格;手边还能挖却因为 {@code count} 停下的说一句;够不着的在哪、怎么去;掉落物各去了哪。 */
    @Override
    protected String successMessage() {
        String stopped = r.count != DigTaskRecord.ALL && dug.size() >= r.count ? " (the count " + r.count
                + " I was given)" : "";
        return tally() + stopped + outOfReach(true) + leftovers() + "." + dropsLine();
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after I " + tally() + outOfReach(true) + "." + dropsLine();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after I " + tally() + "." + dropsLine();
    }
}
