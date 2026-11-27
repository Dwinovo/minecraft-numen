package com.dwinovo.numen.api.agent.goal;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.Rows;
import com.dwinovo.numen.api.agent.loop.AgentLoop;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.ai.AiLog;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 长期目标,照 Claude Code 的 {@code /goal}:设定、每次 run 做完后评估一次、没做完就把评估员的话推回对话让她接着跑、做完就清掉。
 * 目标的状态都是对话记录里的行({@code goal_status}),这里只管写它们和什么时候判。
 *
 * <p>它订阅循环内核的事件({@link #on}):一次 run 说完({@code RunEnded(DONE)})是评估的时机;开了新 run、
 * 被切断都作废在飞的评估;主人按停止不清目标(想收工说 {@code /goal clear})。评估经 {@link AgentLoop#consult} 发出——不是一次 run,
 * 不带历史与工具,不进对话记录,但用量照样进账。
 *
 * <p>纯 JVM:这一刻的运行期状态、身体手上有没有活,都由宿主给。
 */
public final class GoalSteward {

    /** 给目标评估器看的对话上限。够装下整个目标期间,又不至于把整段会话都发一遍。 */
    private static final int JUDGE_WINDOW_CHARS = 8000;
    /** 每条截到这个长度:工具结果可能上千字,评估器不需要读完。 */
    private static final int JUDGE_LINE_CHARS = 400;

    private final String name;
    private final AgentLoop loop;
    private final ConvoState convo;
    private final EventQueue inbox;
    private final Supplier<String> runtimeState;
    private final BooleanSupplier bodyOnFiniteTask;

    /** 当前的长期目标;{@code null} = 没有。 */
    private GoalState goal;
    /** 在飞的评估;{@code null} = 没在判。开了新 run、被切断、换了目标都作废它。 */
    private CancelToken judging;

    /**
     * @param name             日志里认这只同伴用的名字
     * @param inbox            只读:队里还排着会叫醒她的条目就先不判——那些本来就会开起一次 run
     * @param runtimeState     这一刻的运行期状态({@code <runtime_state>}),评估器对着它判身体事实
     * @param bodyOnFiniteTask 身体手上有一件会结束的活(常驻的跟随不算)——那时不催
     * @param restored         读对话记录恢复的目标;{@code null} = 没有
     */
    public GoalSteward(String name, AgentLoop loop, ConvoState convo, EventQueue inbox,
                       Supplier<String> runtimeState, BooleanSupplier bodyOnFiniteTask, GoalState restored) {
        this.name = name;
        this.loop = loop;
        this.convo = convo;
        this.inbox = inbox;
        this.runtimeState = runtimeState;
        this.bodyOnFiniteTask = bodyOnFiniteTask;
        this.goal = restored;
    }

    /** 当前的长期目标;{@code null} = 没有。 */
    public GoalState goal() {
        return goal;
    }

    /**
     * 定一个目标:写一条设定的 {@code goal_status}。整份目标<b>只在设定时</b>交给她一次({@link GoalPrompts#directive});
     * 之后每轮只补评估员那句"还差什么"。死着、外接驾驶时只记下——那时内脑不开工,交出去也只会躺着。
     *
     * @return 这一刻该不该把指示交给她;交出去走主人的话那条路,聊天里要有主人打的那行字
     */
    public boolean set(GoalState next) {
        cancelJudging();   // 判的是上一个目标
        goal = next;
        status(next.objective(), false, null, true);
        Hold hold = loop.hold();
        if (hold == Hold.DEAD || hold == Hold.EXTERNAL || hold == Hold.AWAY) {
            return false;
        }
        next.countTurn();
        return true;
    }

    /**
     * 收工。目标只有"在"和"不在"两种,所以主人收工和做完的结果都是这里,区别只在 {@code why} 那句话。
     *
     * @param why 收工的原因,只进日志。<b>不往聊天栏说</b>——目标是后台跑着的东西,
     *            结束时不该弹一句打断主人;面板顶上那行消失本身就是信号,想追问 {@code /goal}
     */
    public void clear(String why) {
        if (goal == null) {
            return;
        }
        AiLog.LOG.info("[numen-entity#{}] goal ended ({} turns, {})",
                name, goal.turnsExecuted(), why == null ? "cleared by the owner" : "ended after evaluation");
        cancelJudging();
        status(goal.objective(), true, null, true);
        goal = null;
    }

    /** 目标的一条状态写进对话记录:设定与清除({@code sentinel})、评估员的判定。 */
    private void status(String condition, boolean met, String reason, boolean sentinel) {
        JsonObject fields = new JsonObject();
        fields.addProperty("condition", condition);
        fields.addProperty("met", met);
        if (reason != null) {
            fields.addProperty("reason", reason);
        }
        if (sentinel) {
            fields.addProperty("sentinel", true);
        }
        convo.attach(Rows.GOAL_STATUS, fields);
    }

    /** 内核的事件里目标这一侧要接的。 */
    public void on(LoopEvent event) {
        switch (event) {
            // 评估期间开了新 run,它判的已经不是眼前的局面
            case LoopEvent.RunStarted ignored -> cancelJudging();
            // 链条收尾了——这正是长期目标该接上的时刻:"还没做完就接着做"要等这一轮真的说完才判断得了
            case LoopEvent.RunEnded ended -> {
                if (ended.end() instanceof RunEnd.Done) {
                    steer();
                }
            }
            // 切断作废在飞的评估;目标还在:主人按停止不清它(Claude Code 的打断不清),下一次她停下来照常判
            case LoopEvent.Halted halted -> cancelJudging();
            // 目标的账单:主人得看得见这个目标到现在烧了多少。评估自己的用量在判完那一刻记给被判的那个目标
            case LoopEvent.ModelUsed used -> {
                if (used.purpose() == LoopEvent.Purpose.TURN && goal != null) {
                    goal.addTokens(used.usage().fresh());
                }
            }
            default -> { }
        }
    }

    /**
     * 一次 run 做完了:判一次目标达没达成。
     *
     * <p>判定<b>不由她自己做</b>——另开一次干净的调用(不带对话历史、不带人设、不带工具),
     * 只看条件、身体事实和最近几句。执行的人和判定的人分开,她才骗不了自己。
     *
     * <p>队列里还排着会叫醒她的条目就先不判——那些本来就会开起一次 run,那次做完时再说。旁听的话不算:
     * 它们开不起 run,等它们就是等别的事,目标就停在这儿了;控制命令同样不算,它们执行完不会开 run。
     */
    private void steer() {
        if (goal == null || loop.hold() != null || inbox.hasWaking() || judging != null) {
            return;
        }
        // 身体还在干活就别催。
        //
        // 我们的工具是异步的:派发回执立刻回来,链条当场收尾,而她其实动都还没动完。不拦
        // 的话就是每隔一个 API 往返问一次"挖完了吗"——什么也没推进,纯烧 token。
        //
        // 醒来不用另写:任务干完会推 task_finished 进队列,那本来就会开起一次 run;那次
        // 做完时再走到这里,身体已经空了,续跑自然接上。
        //
        // 常驻任务(跟随这种)要放行:它永远不报完成,等它等于永远不续。
        if (bodyOnFiniteTask.getAsBoolean()) {
            AiLog.LOG.debug("[numen-entity#{}] goal continuation yields: the body has a job running", name);
            return;
        }
        judge();
    }

    /**
     * 跑一次评估。用同伴自己绑的那个模型,但是<b>另一次调用</b>——"新鲜"指的是这个,
     * 不是换个更小的模型。
     */
    private void judge() {
        GoalState target = goal;
        ModelRequest request = new ModelRequest(
                List.of(new ConvoState.Msg.User(
                        GoalPrompts.evaluatorQuery(target, runtimeState.get(), sinceGoalForJudge()))),
                List.of(), GoalPrompts.evaluatorSystem());
        CancelToken cancel = new CancelToken();
        judging = cancel;
        loop.consult(LoopEvent.Purpose.GOAL, request, cancel, outcome -> {
            judging = null;
            finish(target, outcome);
        });
    }

    private void cancelJudging() {
        if (judging != null) {
            judging.cancel();
            judging = null;
        }
    }

    private void finish(GoalState judged, ModelOutcome outcome) {
        // 判的是上一个目标 —— 这次结果作废。
        if (goal == null || goal != judged) {
            return;
        }
        if (outcome instanceof ModelOutcome.Failed failed) {
            // 判不出来不等于做完了。歇一轮,下次做完再判。
            AiLog.LOG.warn("[numen-entity#{}] goal evaluation failed, not continuing this turn: {}", name, failed.cause().forModel());
            return;
        }
        ModelOutcome.Answered answered = (ModelOutcome.Answered) outcome;
        goal.addTokens(answered.usage().fresh());
        var verdict = GoalPrompts.readVerdict(answered.turn().content());
        goal.setLastReason(verdict.reason());
        AiLog.LOG.info("[numen-entity#{}] goal evaluation, turn {}: {}", name, goal.turnsExecuted(),
                verdict.met() ? "met" : "not yet");
        status(goal.objective(), verdict.met(), verdict.reason(), false);
        if (verdict.met()) {
            AiLog.LOG.info("[numen-entity#{}] goal ended ({} turns, goal met)", name, goal.turnsExecuted());
            goal = null;   // 判定的那一行已经是"做完了",不再写第二条清除
            return;
        }
        goal.countTurn();
        // 评估员的话进对话(元信息的 user 行)并叫醒她接着跑;goal 在类型表里恒为急件、投递方式是接续,发送方不另标。
        loop.push(List.of(new EventQueue.Entry(EventTypes.GOAL,
                GoalPrompts.feedback(goal.objective(), verdict.reason()), System.currentTimeMillis(), false)));
    }

    /**
     * 给评估器看的:<b>目标设定以来</b>发生的一切。
     *
     * <p>不是"最近几句"。她可能分三次才凑够数,只看末尾就永远拼不出累计的证据——实测过
     * 一次:第一轮挖到 64/128 那条早滚出窗口,后面几轮评估器咬定"没有挖矿证据",把她赶去
     * 满世界找矿四分钟。
     *
     * <p>从末尾往回扫到目标设定那条({@link GoalPrompts#isDirective}),字数封顶兜底——整理记忆
     * 会把那条冲掉,不封顶就一路扫到会话开头。不记"设定时的历史位置":重进游戏后历史从最后一个整理边界起读回、
     * 位置早就对不上了,扫到那条消息本身才是准的。
     */
    private String sinceGoalForJudge() {
        List<ConvoState.Msg> all = convo.snapshot();
        ArrayDeque<String> lines = new ArrayDeque<>();
        int budget = JUDGE_WINDOW_CHARS;
        for (int i = all.size() - 1; i >= 0 && budget > 0; i--) {
            ConvoState.Msg msg = all.get(i);
            String line = switch (msg) {
                case ConvoState.Msg.User u -> "owner/system: " + u.content();
                case ConvoState.Msg.Assistant a -> "companion: " + a.turn().content();
                case ConvoState.Msg.Tool t -> "tool result: " + t.content();
                // 切断也是证据:一轮没做完是被打断/死亡掐掉的,不是她放弃了
                case ConvoState.Msg.Halt h -> "interrupted: " + h.reason();
            };
            line = truncate(line, JUDGE_LINE_CHARS);
            lines.addFirst(line);
            budget -= line.length();
            if (msg instanceof ConvoState.Msg.User u && GoalPrompts.isDirective(u.content())) {
                break;   // 扫到目标设定那条了,再往前跟这个目标无关
            }
        }
        return String.join("\n", lines).strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
