package com.dwinovo.numen.plugins.minecraft.task.station;

import com.dwinovo.numen.plugins.minecraft.FailureType;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskFactory;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.plugins.minecraft.task.MouseButton;
import com.dwinovo.numen.plugins.minecraft.task.base.AbstractCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.base.Precondition;
import com.dwinovo.numen.plugins.minecraft.task.interact.InteractAtCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.plugins.minecraft.task.interact.InteractEntityCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.plugins.minecraft.task.inventory.GuiItemsCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.inventory.GuiItemsTaskRecord;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Step;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对一个目标做一件事({@link StationTaskRecord}):右键它是一件子活(走过去、过权限层、按下去,和 {@code minecraft.use.block} 同一件),
 * 界面开了就一步步做——搬东西的每一步也是子活(能不能动里面的东西问在右键那一下,界面开了之后不再问),当场做完的事直接做——收场时把界面合上:
 * 做成了、失败了、被叫停了都合。子活的账(路上挖的放的、主人点过的头)并进这件活,回执里说一次。
 *
 * <p>回执只说做了什么:哪一步搬了几件什么、做了哪件事。做到一半收场,已经做了的照样说;合上界面时原版退回她背包的东西
 * (附魔台、铁砧这类不存东西的界面,合上就把格子里剩的还给她)也量出来说({@link #shut})。
 */
public final class StationCompanionTask<V> extends AbstractCompanionTask<StationTaskRecord<V>, V> {

    /** 一步搬东西的期限:点击一刻一步,搬空一个大箱子也用不到这么久。 */
    private static final long MOVE_TICKS = 60 * 20;

    /** 右键目标的那件子活;界面开了之后是 null。 */
    private Task opener;
    /** 这件活打开的界面;还没打开是 null。 */
    private AbstractContainerMenu opened;
    /** 正在做第几步。 */
    private int at;
    /** 正在搬的那一步的子活;不在搬是 null。 */
    private GuiItemsCompanionTask mover;
    /** 到此刻为止做了的几步,一句一步。 */
    private final List<String> done = new ArrayList<>();
    /** 目标在回执里的叫法:{@code furnace at 10,64,5}。 */
    private String where;
    /** 合上界面时原版退回她背包的东西,接在回执后面的那半句;没有退回是空串。 */
    private String handedBack = "";
    private boolean finished;

    public StationCompanionTask(NumenPlayer player, StationTaskRecord<V> record) {
        super(player, record);
    }

    /** 登记这种活由谁来跑。记录带着类型参数,表里按它的类登记。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() {
        TaskFactory.Runner runner = (p, r) -> new StationCompanionTask(p, (StationTaskRecord) r);
        TaskFactory.register((Class) StationTaskRecord.class, runner);
    }

    /**
     * 开工之前就判得了的:这一格得是打得开界面的东西(否则右键下去手里的方块会被放上去),要放的东西她得带着。
     */
    @Override
    protected List<Precondition> preconditions() {
        List<Precondition> checks = new ArrayList<>();
        if (r.block != null) {
            checks.add(() -> {
                BlockState state = player.level().getBlockState(r.block);
                return state.hasBlockEntity() || state.getMenuProvider(player.level(), r.block) != null ? null
                        : new Precondition.Failure(name(state) + " at " + cell() + " cannot be opened: it is not "
                                + a(r.kind), FailureType.UNSUPPORTED);
            });
        }
        for (Step step : r.steps) {
            if (step instanceof Move move && move.put()) {
                checks.add(() -> Stacks.pick(player.getInventory(), move.which()).isPresent() ? null
                        : new Precondition.Failure("you carry no " + name(move.which().item()), FailureType.NO_MATERIAL));
            }
        }
        return checks;
    }

    @Override
    protected void onStart() {
        where = r.block != null ? name(player.level().getBlockState(r.block)) + " at " + cell() : r.entity.label();
    }

    @Override
    protected TaskState onTick() {
        if (opened == null) {
            return open();
        }
        if (at == r.steps.size()) {
            finished = true;
            return TaskState.SUCCESS;
        }
        // 有的界面做完一件事自己就合上(讲台的书取下来);那是最后一步时上面已经收场了,到这里就是没做完。正在搬的那一步除外:
        // 它自己认得界面合了、把已经搬的算数(GuiItemsCompanionTask),让它把这一步收完
        if (mover == null && player.containerMenu != opened) {
            fail(where + " closed before the work was done" + soFar(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        return switch (r.steps.get(at)) {
            case Move move -> move(move);
            case Deed deed -> perform(deed);
        };
    }

    /** 右键目标,等它的界面打开;开的不是这件活要的那一种就收场。点开的界面要留着(这件活接着在里面做事),所以点击带着"不合"。 */
    private TaskState open() {
        if (opener == null) {
            opener = r.block != null
                    ? new InteractAtCompanionTask(player,
                            new InteractAtTaskRecord(r.source, MouseButton.RIGHT, r.block, 0, null, false, true))
                    : new InteractEntityCompanionTask(player,
                            new InteractEntityTaskRecord(r.source, MouseButton.RIGHT, r.entity, 0, null, false, null,
                                    true));
        }
        TaskResult clicked = runChild(opener);
        if (clicked == null) {
            return TaskState.RUNNING;
        }
        opener = null;
        if (!clicked.success()) {
            fail(clicked.message(), lastFailure(), clicked.hint());
            return TaskState.FAILED;
        }
        if (player.containerMenu == player.inventoryMenu) {
            fail(where + " did not open (" + clicked.message() + ")", FailureType.UNSUPPORTED);
            return TaskState.FAILED;
        }
        opened = player.containerMenu;
        if (r.menu != null && !r.menu.isInstance(opened)) {
            fail(where + " is not " + a(r.kind) + ": it opened a " + opened.getClass().getSimpleName(),
                    FailureType.UNSUPPORTED);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    /** 搬一步:子活搬完把搬了什么记进这一步;一件都没有而这一步许它没有,照样往下走。 */
    private TaskState move(Move step) {
        if (mover == null) {
            mover = new GuiItemsCompanionTask(player, new GuiItemsTaskRecord(r.source,
                    player.level().getGameTime() + MOVE_TICKS, step.which(), step.count(), step.put(), step.slots()));
        }
        GuiItemsCompanionTask moving = mover;
        TaskResult moved = runChild(moving);
        if (moved == null) {
            return TaskState.RUNNING;
        }
        mover = null;
        step.moved().set(moving.kinds());
        String line = line(step);
        if (line != null) {
            done.add(line);
        }
        if (!moved.success()) {
            boolean none = lastFailure() == FailureType.TARGET_LOST;
            if (!(none && step.optional())) {
                String what = step.which() == null ? "nothing" : "no " + name(step.which().item());
                shut();
                fail((none ? (step.put() ? "you carry " + what : where + " holds " + what + role(step, " in "))
                        : where + ": " + moved.message()) + soFar() + handedBack, lastFailure(), moved.hint());
                return TaskState.FAILED;
            }
        }
        at++;
        return TaskState.RUNNING;
    }

    /** 当场做完的一件事:做成了记下那句话;原版没让做,带着它说的事实收场。 */
    private TaskState perform(Deed deed) {
        String line;
        try {
            line = deed.act().perform(opened, player);
        } catch (ApiError refused) {
            shut();
            fail(refused.getMessage() + soFar() + handedBack, failure(refused.kind()), refused.hint());
            return TaskState.FAILED;
        }
        if (line != null) {
            done.add(line);
        }
        at++;
        return TaskState.RUNNING;
    }

    /** 这一步搬东西说成一句;一件都没搬是 null。 */
    private static String line(Move step) {
        Map<Item, Integer> kinds = step.moved().kinds();
        if (kinds.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        kinds.forEach((item, n) -> parts.add(n + " " + name(item)));
        return (step.put() ? "put " : "took ") + String.join(", ", parts) + role(step, step.put() ? " as " : " from ");
    }

    private static String role(Move step, String joiner) {
        return step.role() == null ? "" : joiner + step.role();
    }

    /** 没做完就收场时接在原因后面的那半句:已经做了的几步;一步都没做成是空串。 */
    private String soFar() {
        return done.isEmpty() ? "" : " — already done: " + String.join("; ", done);
    }

    /** 界面里的事没做成的种类,换成这件活收场的种类;对不上的归"没做成"。 */
    private static FailureType failure(ErrorKind kind) {
        return switch (kind) {
            case NO_MATERIAL -> FailureType.NO_MATERIAL;
            case NOT_FOUND -> FailureType.TARGET_LOST;
            case DENIED -> FailureType.REFUSED;
            case OUT_OF_REACH -> FailureType.OUT_OF_REACH;
            default -> FailureType.UNKNOWN;
        };
    }

    private String cell() {
        return r.block.getX() + "," + r.block.getY() + "," + r.block.getZ();
    }

    /** 这种目标的叫法带上冠词:{@code a furnace}、{@code an anvil}。 */
    private static String a(String kind) {
        return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
    }

    private static String name(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /** 做完了才有值;没做完交回的是 null。 */
    @Override
    protected V value() {
        return finished ? r.value.get() : null;
    }

    /**
     * 合上这件活打开的界面(已经不是它了就不动)。合上是这件活里身体做的最后一件事:不存东西的界面这时把格子里剩的还给她,那是她背包的
     * 变化,所以合之前之后各数一遍她背着的,多出来的写进 {@link #handedBack},跟着回执走。
     */
    private void shut() {
        if (opened == null || player.containerMenu != opened) {
            return;
        }
        Map<Item, Integer> before = carried();
        player.closeContainer();
        List<String> back = new ArrayList<>();
        carried().forEach((item, n) -> {
            int more = n - before.getOrDefault(item, 0);
            if (more > 0) {
                back.add(more + " " + name(item));
            }
        });
        if (!back.isEmpty()) {
            handedBack = " — closing it gave back " + String.join(", ", back);
        }
    }

    /** 她此刻背着的东西,按种类记件数,先见的在前。 */
    private Map<Item, Integer> carried() {
        Map<Item, Integer> kinds = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                kinds.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return kinds;
    }

    /** 回执拼好之前把界面合上:合上时退回来的东西要进回执(失败的那两处在说原因之前已经合了)。 */
    @Override
    protected void closeBooks() {
        shut();
    }

    @Override
    protected String successMessage() {
        return where + ": " + (done.isEmpty() ? "nothing to take" : String.join("; ", done)) + handedBack;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out at " + (where == null ? r.kind : where) + soFar() + handedBack;
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted" + soFar() + handedBack;
    }
}
