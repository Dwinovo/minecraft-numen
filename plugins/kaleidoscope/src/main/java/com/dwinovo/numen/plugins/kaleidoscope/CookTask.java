package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在一格锅上把一道菜从头做到尾。
 *
 * <h2>它不走路</h2>
 * 身体必须<b>已经</b>在够得着的距离内,否则当场教学失败让她先 {@code goto}——和 {@code use block}
 * 同一条规矩。寻路住在核心里,联动够不着,自己再发明一套到场方式就是第二个判据。
 *
 * <h2>能不能动这口锅,权限层说</h2>
 * 开工前把这件活要做的两件事交上去:<b>动这口锅</b>(倒油、下料、翻炒、盖盖都是右键它)和
 * <b>把成品拿走</b>。主人要问就站着等,拒绝就带着他的原话收场。这里不判"厨房里的东西可以随便动",
 * 那是替主人做决定。
 */
final class CookTask implements Task {

    private final CookRecord r;

    private Dish dish;
    private int[] portions;
    private boolean permitted;
    /** 已经对这口锅下过第一手了——在那之前它必须一直是空的。 */
    private boolean touched;
    private ConsentDesk.Ticket consent;
    /** 主人点头之后回执末尾要交代的那一句;没问过主人时是空串。 */
    private String allowance = "";
    /** 走到终态时说给模型听的那句话。 */
    private String outcome = "";
    /** 最后一次推进做了什么,超时收场时用它说清卡在哪一步。 */
    private String lastStep = "not started yet";
    private ItemStack plated = ItemStack.EMPTY;

    CookTask(CookRecord record) {
        this.r = record;
    }

    @Override
    public String name() {
        return KaleidoscopeCommands.GROUP + " " + KaleidoscopeCommands.COOK;
    }

    @Override
    public TaskState tick(NumenPlayer cook) {
        ServerLevel level = cook.serverLevel();
        Cooker cooker = Cooker.at(level, r.pos);
        if (cooker == null) {
            return failed("nothing at " + Cooker.where(r.pos) + " is a pot or a stockpot"
                    + " (steamers, chopping boards, millstones and spits are not wired up yet)");
        }
        if (dish == null) {
            TaskState bad = order(level);
            if (bad != null) {
                return bad;
            }
        }
        // 半空里判不了够不够得着:等她站稳再说
        if (!(cook.onGround() || cook.isInWater() || cook.isPassenger())) {
            return TaskState.RUNNING;
        }
        if (!cook.canInteractWithBlock(r.pos, 0.0)) {
            double away = Math.sqrt(cook.distanceToSqr(r.pos.getX() + 0.5, r.pos.getY() + 0.5, r.pos.getZ() + 0.5));
            return failed("the " + cooker.kind().id() + " at " + Cooker.where(r.pos) + " is "
                    + String.format("%.1f", away) + " blocks away — out of working reach."
                    + " goto it first (goto stops right beside a solid block), then run "
                    + KaleidoscopeCommands.line(KaleidoscopeCommands.COOK) + " again.");
        }
        if (!permitted) {
            TaskState pending = permit(cook, level);
            if (pending != null) {
                return pending;
            }
        }
        // 还没下第一手之前每刻都复核这口锅空不空:等主人答复可能要等上一分钟,这期间别人
        // 完全可以先用上它。下了第一手之后锅就是我们的了,不能再拿"锅里有东西"判它被占。
        if (!touched) {
            String taken = cooker.cannotStart(dish);
            if (taken != null) {
                return failed(taken);
            }
        }
        // 动锅之前先看着它:这些动作直接走方块实体,没有准星射线替身体转头,不看的话她会
        // 背对着锅把菜炒完
        InputDriver.lookAt(cook, Vec3.atCenterOf(r.pos));
        Cooker.Step step = cooker.advance(cook, dish, portions);
        lastStep = step.note();
        touched |= step.kind() != Cooker.Step.Kind.BLOCKED;
        return switch (step.kind()) {
            case WORKING -> TaskState.RUNNING;
            case BLOCKED -> failed(step.note());
            case DONE -> {
                plated = step.plated();
                outcome = step.note();
                KcEvents.done(cook, cooker.kind(), r.pos, r.recipe, plated);
                yield TaskState.SUCCESS;
            }
            case RUINED -> {
                plated = step.plated();
                outcome = step.note();
                KcEvents.ruined(cook, cooker.kind(), r.pos, r.recipe, plated, step.note());
                yield TaskState.FAILED;
            }
        };
    }

    /** 认菜:配方在不在、这个存档的投料量是多少。锅空不空由上面每刻复核,不在这里判第二遍。 */
    private TaskState order(ServerLevel level) {
        Dish ordered = Dish.byId(level, r.recipe);
        if (ordered == null) {
            return failed("no pot or stockpot recipe has id " + r.recipe
                    + " — take the exact id from " + KaleidoscopeCommands.line(KaleidoscopeCommands.RECIPES)
                    + ", do not guess it");
        }
        int[] want = ordered.portions(level);
        if (want == null) {
            return failed(r.recipe + " is a flex recipe and no mix that fits the pot's 9 slots grades SUPERB"
                    + " on this world, so there is no ratio to cook to — "
                    + KaleidoscopeCommands.line(KaleidoscopeCommands.RECIPES) + " says the same");
        }
        dish = ordered;
        portions = want;
        // 炖煮那一段的时间只有认出配方之后才知道
        r.extendDeadlineTo(level.getGameTime() + ordered.time() + CookRecord.PREP_BUDGET_TICKS);
        return null;
    }

    /**
     * 开工前过权限层:动这口锅、把成品拿走。
     *
     * @return null = 可以动手;{@code RUNNING} = 在等主人答复;{@code FAILED} = 不许
     */
    private TaskState permit(NumenPlayer cook, ServerLevel level) {
        BlockState state = level.getBlockState(r.pos);
        List<Action> proposed = List.of(
                Action.useBlock(r.pos, state),
                Action.take(r.pos, state, dish.result().getItem()));
        Gate gate = Permission.gateFor(cook);
        List<ConsentItem> asks = new ArrayList<>();
        for (Action action : proposed) {
            Verdict verdict = gate.judgeLive(action, level);
            switch (verdict.kind()) {
                case ALLOW -> { }
                case DENY -> {
                    return failed("cannot " + action.describe() + ": " + verdict.reason());
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
            InputDriver.halt(cook);
            return TaskState.RUNNING;
        }
        consent = null;
        if (!answer.allowed()) {
            return failed(answer.refusal(asks));
        }
        allowance = answer.allowance(asks);
        permitted = true;
        return null;
    }

    private TaskState failed(String why) {
        outcome = why;
        return TaskState.FAILED;
    }

    @Override
    public void stop(NumenPlayer cook, StopReason why) {
        InputDriver.halt(cook);
    }

    @Override
    public TaskResult result(TaskState terminal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipe", r.recipe.toString());
        data.put("at", Cooker.where(r.pos));
        if (!plated.isEmpty()) {
            data.put("plated", Dish.idOf(plated.getItem()));
        }
        String tail = allowance.isEmpty() ? "" : " " + allowance + ".";
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(outcome + tail, data);
            case TIMEOUT -> TaskResult.timeout("ran out of time cooking " + r.recipe
                    + "; last thing that happened: " + lastStep + "." + tail);
            case CANCELLED -> TaskResult.cancelled("stopped while cooking " + r.recipe
                    + "; last thing that happened: " + lastStep + "." + tail);
            default -> TaskResult.fail(outcome + tail, data);
        };
    }
}
