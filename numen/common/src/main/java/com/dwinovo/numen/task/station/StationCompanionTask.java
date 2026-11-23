package com.dwinovo.numen.task.station;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskFactory;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.task.MouseButton;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.base.Precondition;
import com.dwinovo.numen.task.interact.InteractAtCompanionTask;
import com.dwinovo.numen.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.task.interact.InteractEntityCompanionTask;
import com.dwinovo.numen.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.task.inventory.GuiItemsCompanionTask;
import com.dwinovo.numen.task.inventory.GuiItemsTaskRecord;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对一个目标做一件事({@link StationTaskRecord}):右键它是一件子活(走过去、过权限层、按下去,和 {@code numen.use.block} 同一件),
 * 界面开了就按步骤搬东西(每一步也是子活,从别人的容器里拿东西照旧先过权限层),收场时把界面合上——做成了、失败了、被叫停了都合。
 * 子活的账(路上挖的放的、主人点过的头)并进这件活,回执里说一次。
 *
 * <p>回执只说做了什么:哪一步搬了几件什么。做到一半收场,已经搬了的照样说。
 */
public final class StationCompanionTask<V> extends AbstractCompanionTask<StationTaskRecord<V>, V> {

    /** 一步搬东西的期限:点击一刻一步,留的是等主人点头的时间。 */
    private static final long MOVE_TICKS = 60 * 20;

    /** 右键目标的那件子活;界面开了之后是 null。 */
    private Task opener;
    /** 这件活打开的界面;还没打开是 null。 */
    private AbstractContainerMenu opened;
    /** 正在做第几步。 */
    private int at;
    /** 正在搬的那一步的子活;不在搬是 null。 */
    private GuiItemsCompanionTask mover;
    /** 目标在回执里的叫法:{@code furnace at 10,64,5}。 */
    private String where;
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
                        : new Precondition.Failure(name(state) + " at " + cell() + " has no window to open: it is not a "
                                + r.kind, FailureType.UNSUPPORTED);
            });
        }
        for (Move step : r.steps) {
            if (step.put()) {
                checks.add(() -> PlayerInv.count(player.getInventory(), step.item()) > 0 ? null
                        : new Precondition.Failure("you carry no " + name(step.item()), FailureType.NO_MATERIAL));
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
        if (player.containerMenu != opened) {
            fail(where + " closed before the work was done" + soFar(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (at == r.steps.size()) {
            finished = true;
            return TaskState.SUCCESS;
        }
        return move(r.steps.get(at));
    }

    /** 右键目标,等它的界面打开;开的不是这件活要的那一种就收场。 */
    private TaskState open() {
        if (opener == null) {
            opener = r.block != null
                    ? new InteractAtCompanionTask(player,
                            new InteractAtTaskRecord(r.source, MouseButton.RIGHT, r.block, 0, null, false))
                    : new InteractEntityCompanionTask(player,
                            new InteractEntityTaskRecord(r.source, MouseButton.RIGHT, r.entity, 0, null, false, null));
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
            fail(where + " opened no window (" + clicked.message() + ")", FailureType.UNSUPPORTED);
            return TaskState.FAILED;
        }
        opened = player.containerMenu;
        if (r.menu != null && !r.menu.isInstance(opened)) {
            fail(where + " is not a " + r.kind + ": it opened a " + opened.getClass().getSimpleName(),
                    FailureType.UNSUPPORTED);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    /** 搬一步:子活搬完把搬了什么记进这一步;一件都没有而这一步许它没有,照样往下走。 */
    private TaskState move(Move step) {
        if (mover == null) {
            mover = new GuiItemsCompanionTask(player, new GuiItemsTaskRecord(r.source,
                    player.level().getGameTime() + MOVE_TICKS, step.item(), step.count(), step.put(), step.slots()));
        }
        GuiItemsCompanionTask moving = mover;
        TaskResult moved = runChild(moving);
        if (moved == null) {
            return TaskState.RUNNING;
        }
        mover = null;
        step.moved().set(moving.kinds());
        if (!moved.success()) {
            boolean none = lastFailure() == FailureType.TARGET_LOST;
            if (!(none && step.optional())) {
                String what = step.item() == null ? "nothing" : "no " + name(step.item());
                fail((none ? (step.put() ? "you carry " + what : where + " holds " + what + slots(step, " in "))
                        : where + ": " + moved.message()) + soFar(), lastFailure(), moved.hint());
                return TaskState.FAILED;
            }
        }
        at++;
        return TaskState.RUNNING;
    }

    /** 这一步说成一句;一件都没搬的那一步是 null。 */
    private static String line(Move step) {
        Map<Item, Integer> kinds = step.moved().kinds();
        if (kinds.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        kinds.forEach((item, n) -> parts.add(n + " " + name(item)));
        return (step.put() ? "put " : "took ") + String.join(", ", parts) + slots(step, step.put() ? " as " : " from ");
    }

    private static String slots(Move step, String joiner) {
        return step.role() == null ? "" : joiner + step.role();
    }

    /** 到此刻为止做了的几步,一句一步;一步都没做成是空表。 */
    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Move step : r.steps) {
            String line = line(step);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 没做完就收场时接在原因后面的那半句:已经做了的几步;一步都没做成是空串。 */
    private String soFar() {
        List<String> lines = lines();
        return lines.isEmpty() ? "" : " — already done: " + String.join("; ", lines);
    }

    private String cell() {
        return r.block.getX() + "," + r.block.getY() + "," + r.block.getZ();
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

    /** 合上这件活打开的界面(已经不是它了就不动),再放手。 */
    @Override
    protected void cleanup() {
        if (opened != null && player.containerMenu == opened) {
            player.closeContainer();
        }
        super.cleanup();
    }

    @Override
    protected String successMessage() {
        List<String> lines = lines();
        return where + ": " + (lines.isEmpty() ? "nothing to take" : String.join("; ", lines));
    }

    @Override
    protected String timeoutMessage() {
        return "timed out at " + (where == null ? r.kind : where) + soFar();
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted" + soFar();
    }
}
