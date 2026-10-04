package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在一格锅上做一步({@link PotAct})。
 *
 * <h2>它不走路</h2>
 * 身体必须<b>已经</b>在够得着的距离内,否则受理之前就当场拒绝、教她先 {@code numen.move.to}——和 {@code numen.use.block}
 * 同一条规矩。这一格不是锅、配方不成,也在受理之前判({@link #prepare})。
 *
 * <h2>能不能动这口锅,权限层说</h2>
 * 每一步都是右键这口锅(倒油、下料、翻炒、盖盖都是),装盘还要<b>把成品拿走</b>。动手之前交上去,主人要问就站着等,
 * 拒绝就带着他的原话收场。这里不判"厨房里的东西可以随便动",那是替主人做决定。
 */
final class PotActTask implements Task {

    private final PotActRecord r;

    private Dish dish;
    private int[] portions;
    private boolean permitted;
    private ConsentDesk.Ticket consent;
    /** 主人点头之后回执末尾要交代的那一句;没问过主人时是空串。 */
    private String allowance = "";
    /** 走到终态时说给模型听的那句话。 */
    private String outcome = "";
    /** 失败收场时是哪一类、能照抄的下一步(没有为 null)。 */
    private ErrorKind failKind = ErrorKind.FAILED;
    private String failHint;
    /** 最后一次推进做了什么,超时收场时用它说清卡在哪。 */
    private String lastStep = "not started yet";
    private ItemStack plated = ItemStack.EMPTY;

    PotActTask(PotActRecord record) {
        this.r = record;
    }

    @Override
    public String name() {
        return r.getToolName();
    }

    /**
     * 受理之前:这一格是不是锅、配方在不在、这个存档有没有投料量、她够不够得着——和动手之后每刻复核的是同一组判据
     * ({@link #blocker}),不过就当场回那句话,不受理。半空里判不了够不够得着,等她站稳再判。
     */
    @Override
    public Preparation prepare(NumenPlayer cook) {
        return () -> {
            if (!settled(cook)) {
                return null;
            }
            TaskResult why = blocker(cook);
            return why == null ? Preparation.Readiness.READY : Preparation.Readiness.refused(why);
        };
    }

    /** 站稳了:半空里判不了够不够得着。 */
    private static boolean settled(NumenPlayer cook) {
        return cook.onGround() || cook.isInWater() || cook.isPassenger();
    }

    /** 此刻做不了这一步的那条失败:这一格不是锅、配方不成、她够不着;都没有为 null。认出配方时记下这道菜。 */
    private TaskResult blocker(NumenPlayer cook) {
        ServerLevel level = cook.serverLevel();
        Cooker cooker = Cooker.at(level, r.pos);
        if (cooker == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "nothing at " + Cooker.where(r.pos) + " is a pot or a "
                    + "stockpot (steamers, chopping boards, millstones and spits are not wired up yet)", null);
        }
        if (r.act.needsDish && dish == null) {
            TaskResult bad = order(level, cooker.kind());
            if (bad != null) {
                return bad;
            }
        }
        if (!cook.canInteractWithBlock(r.pos, 0.0)) {
            double away = Math.sqrt(cook.distanceToSqr(r.pos.getX() + 0.5, r.pos.getY() + 0.5, r.pos.getZ() + 0.5));
            // arrive = "use" 站到看得见、点得到锅的地方
            return TaskResult.fail(ErrorKind.OUT_OF_REACH, "the " + cooker.kind().id() + " at "
                    + Cooker.where(r.pos) + " is " + String.format("%.1f", away) + " blocks away — out of working "
                    + "reach; walk there first, then call " + r.getToolName() + " again",
                    Call.of("numen.move.to", r.pos, Map.of("arrive", "use")), Map.of("pos", r.pos));
        }
        return null;
    }

    /** 认菜:配方在不在、这个存档的投料量是多少;认不出是那条失败,认出了记下、返回 null。 */
    private TaskResult order(ServerLevel level, Cookware cookware) {
        Dish ordered = Dish.byId(level, r.recipe);
        if (ordered == null) {
            String path = r.recipe.getPath();
            return TaskResult.fail(ErrorKind.NOT_FOUND, "no pot or stockpot recipe has id " + r.recipe
                    + " — take the exact id from kaleidoscope.pot.recipes, do not guess it",
                    Call.of("kaleidoscope.pot.recipes", cookware, Map.of("name", path.substring(path.lastIndexOf('/') + 1))));
        }
        if (ordered.cookware() != cookware) {
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, r.recipe + " is a " + ordered.cookware().id()
                    + " recipe, not a " + cookware.id() + " one", null);
        }
        int[] want = ordered.portions(level);
        if (want == null) {
            return TaskResult.fail(ErrorKind.FAILED, r.recipe + " is a flex recipe and no mix that fits the pot's "
                    + "9 slots grades SUPERB on this world, so there is no ratio to cook to — "
                    + "kaleidoscope.pot.recipes says the same", null);
        }
        dish = ordered;
        portions = want;
        return null;
    }

    @Override
    public TaskState tick(NumenPlayer cook) {
        ServerLevel level = cook.serverLevel();
        // 半空里判不了够不够得着:等她站稳再说
        if (!settled(cook)) {
            return TaskState.RUNNING;
        }
        TaskResult why = blocker(cook);
        if (why != null) {
            return failed(why.kind(), why.message(), why.hint());
        }
        if (!permitted) {
            TaskState pending = permit(cook, level);
            if (pending != null) {
                return pending;
            }
        }
        // 动锅之前先看着它:这些动作直接走方块实体,没有准星射线替身体转头,不看的话她会背对着锅做
        InputDriver.lookAt(cook, Vec3.atCenterOf(r.pos));
        Cooker cooker = Cooker.at(level, r.pos);
        Cooker.Step step = r.act.on(cooker, cook, dish, portions);
        lastStep = step.note();
        return switch (step.kind()) {
            case WORKING -> TaskState.RUNNING;
            case BLOCKED -> failed(ErrorKind.FAILED, step.note(), null);
            case DONE -> {
                plated = step.plated();
                outcome = step.note();
                if (!plated.isEmpty()) {
                    KcEvents.done(cook, cooker.kind(), r.pos, r.recipe, plated);
                }
                yield TaskState.SUCCESS;
            }
            case RUINED -> {
                plated = step.plated();
                if (r.act == PotAct.PLATE) {
                    KcEvents.ruined(cook, cooker.kind(), r.pos, r.recipe, plated, step.note());
                }
                yield failed(ErrorKind.FAILED, step.note(), null);
            }
        };
    }

    /**
     * 动手之前过权限层:动这口锅,装盘时还有把成品拿走。
     *
     * @return null = 可以动手;{@code RUNNING} = 在等主人答复;{@code FAILED} = 不许
     */
    private TaskState permit(NumenPlayer cook, ServerLevel level) {
        BlockState state = level.getBlockState(r.pos);
        List<Action> proposed = r.act == PotAct.PLATE
                ? List.of(Action.useBlock(r.pos, state), Action.take(r.pos, state, dish.result().getItem()))
                : List.of(Action.useBlock(r.pos, state));
        Gate gate = Permission.gateFor(cook);
        List<ConsentItem> asks = new ArrayList<>();
        for (Action action : proposed) {
            Verdict verdict = gate.judgeLive(action, level);
            switch (verdict.kind()) {
                case ALLOW -> { }
                case DENY -> {
                    return failed(ErrorKind.DENIED, "cannot " + action.describe() + ": " + verdict.reason(), null);
                }
                case ASK -> asks.add(gate.consentItemLive(action, verdict, level));
            }
        }
        if (asks.isEmpty()) {
            permitted = true;
            return null;
        }
        if (consent == null || !consent.request().items().equals(asks)) {
            consent = ConsentDesk.of(cook).ask(r, asks);
        }
        ConsentAnswer answer = consent.poll();
        if (answer == null) {
            cook.controls().stop();
            return TaskState.RUNNING;
        }
        consent = null;
        if (!answer.allowed()) {
            return failed(ErrorKind.DENIED, answer.refusal(asks), null);
        }
        allowance = answer.allowance(asks);
        permitted = true;
        return null;
    }

    private TaskState failed(ErrorKind kind, String why, String hint) {
        outcome = why;
        failKind = kind;
        failHint = hint;
        return TaskState.FAILED;
    }

    @Override
    public void stop(NumenPlayer cook, StopReason why) {
        cook.controls().stop();
        // 装盘时为铲菜按下的潜行,还没铲就被停下也松开
        cook.controls().set(com.dwinovo.numen.pathing.body.Controls.Key.SNEAK, false);
    }

    @Override
    public TaskResult result(TaskState terminal) {
        KaleidoscopeApi.Done data = new KaleidoscopeApi.Done(r.pos,
                plated.isEmpty() ? Optional.empty() : Optional.of(Dish.idOf(plated.getItem())));
        String tail = allowance.isEmpty() ? "" : " " + allowance + ".";
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(outcome + tail, data);
            case TIMEOUT -> TaskResult.timeout("ran out of time at " + r.act.word + " on the "
                    + Cooker.where(r.pos) + " cookware; last thing that happened: " + lastStep + "." + tail);
            case CANCELLED -> TaskResult.cancelled("stopped at " + r.act.word + "; last thing that happened: "
                    + lastStep + "." + tail);
            default -> TaskResult.fail(failKind, outcome + tail, failHint, data);
        };
    }
}
