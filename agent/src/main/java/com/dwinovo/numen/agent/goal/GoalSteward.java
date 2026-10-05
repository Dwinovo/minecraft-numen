package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.loop.RunEnd;
import com.dwinovo.numen.ai.AiLog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 长期目标:设定、每次 run 做完后评估一次、没做完就推一条续跑、做完或卡住就收工。
 *
 * <p>它订阅循环内核的事件({@link #on}):一次 run 说完({@code RunEnded(DONE)})是评估的时机;开了新 run、
 * 被切断都作废在飞的评估;主人按停止时目标跟着收工。评估经 {@link AgentLoop#consult} 发出——不是一次 run,
 * 不带历史与工具,但用量照样进账。
 *
 * <p>纯 JVM:目标落盘、这一刻的运行期状态、身体手上有没有活,都由宿主给。
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
    private final Consumer<GoalState> persist;

    /** 当前的长期目标;{@code null} = 没有。 */
    private GoalState goal;
    /** 在飞的评估;{@code null} = 没在判。开了新 run、被切断、换了目标都作废它。 */
    private CancelToken judging;

    /**
     * @param name             日志里认这只同伴用的名字
     * @param inbox            只读:队里还排着会叫醒她的条目就先不判——那些本来就会开起一次 run
     * @param runtimeState     这一刻的运行期状态({@code <runtime_state>}),评估器对着它判身体事实
     * @param bodyOnFiniteTask 身体手上有一件会结束的活(常驻的跟随不算)——那时不催
     * @param persist          目标落盘(跨重进游戏活着)
     * @param restored         读盘恢复的目标;{@code null} = 没有
     */
    public GoalSteward(String name, AgentLoop loop, ConvoState convo, EventQueue inbox,
                       Supplier<String> runtimeState, BooleanSupplier bodyOnFiniteTask,
                       Consumer<GoalState> persist, GoalState restored) {
        this.name = name;
        this.loop = loop;
        this.convo = convo;
        this.inbox = inbox;
        this.runtimeState = runtimeState;
        this.bodyOnFiniteTask = bodyOnFiniteTask;
        this.persist = persist;
        this.goal = restored;
    }

    /** 当前的长期目标;{@code null} = 没有。 */
    public GoalState goal() {
        return goal;
    }

    /**
     * 定一个目标并落盘。整份目标<b>只在设定时</b>交给她一次;之后每轮只补评估器那句"还差什么"。
     * 死着、外接驾驶时只记下——那时内脑不开工,交出去也只会躺着。
     *
     * @return 这一刻该不该把整份目标交给她({@link GoalPrompts#initialDirective});交出去走主人的话那条路,
     *         聊天里要有主人打的那行字
     */
    public boolean set(GoalState next) {
        cancelJudging();   // 判的是上一个目标
        goal = next;
        persist.accept(next);
        Hold hold = loop.hold();
        if (next == null || hold == Hold.DEAD || hold == Hold.EXTERNAL) {
            return false;
        }
        next.countTurn();
        persist.accept(next);
        return true;
    }

    /**
     * 收工。目标只有"在"和"不在"两种,所以做完、放弃、跑够轮次、主人喊停——<b>结果都是这里</b>,
     * 区别只在 {@code why} 那句话。
     *
     * @param why 收工的原因,只进日志。<b>不往聊天栏说</b>——目标是后台跑着的东西,
     *            结束时不该弹一句打断主人;面板顶上那行消失本身就是信号,想追问 {@code /goal}
     */
    public void clear(String why) {
        if (goal == null) {
            return;
        }
        AiLog.LOG.info("[numen-entity#{}] 目标收工({} 轮,{}):{}",
                name, goal.turnsExecuted(), why == null ? "主人清掉" : why, goal.objective());
        cancelJudging();
        goal = null;
        persist.accept(null);
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
            case LoopEvent.Halted halted -> {
                cancelJudging();
                if (halted.reason().endsGoal() && goal != null) {
                    // 主人按停止 = 不要她接着跑了。目标跟着收工,否则这一轮刚断下一轮又自己续上,
                    // 停止键就成了摆设。想接着做再说一次 /goal,成本就是一句话。
                    clear("按停止收工了:" + goal.objective());
                }
            }
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
            AiLog.LOG.debug("[numen-entity#{}] 目标续跑让位:身体手上有活", name);
            return;
        }
        // 额度不在这儿拦:每一轮的成果都要判过再说。拦在判定前面的话,最后一轮白干——
        // 而那恰恰是最可能已经做完的一轮。额度只管"还要不要再推下一轮",见 finish。
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
            AiLog.LOG.warn("[numen-entity#{}] 目标评估失败,这一轮先不续:{}", name, failed.words());
            return;
        }
        ModelOutcome.Answered answered = (ModelOutcome.Answered) outcome;
        goal.addTokens(answered.usage().fresh());
        var verdict = GoalPrompts.readVerdict(answered.turn().content());
        goal.setLastReason(verdict.reason());
        boolean giveUp = goal.noteStuck(verdict.stuck());
        AiLog.LOG.info("[numen-entity#{}] 目标评估 第{}轮 {}:{}", name, goal.turnsExecuted(),
                verdict.met() ? "达成" : verdict.stuck() ? "打转 x" + goal.stuckStreak() : "还差",
                verdict.reason());
        if (verdict.met()) {
            clear("目标达成:" + verdict.reason());
            return;
        }
        if (giveUp) {
            // 连着几轮同一堵墙:告诉主人卡在哪,别再转了。判"没进展"的是评估器,不是她自报
            // ——她报不准,前面验过。
            clear("过不去,先收工了:" + verdict.reason() + " —— 换个说法或者搭把手再 /goal");
            return;
        }
        if (!goal.hasTurnsLeft()) {
            // 还没做完,但额度到顶了:停下来告诉主人,不是闷头继续——她"以为没做完"是
            // 会一直转的,而每轮主请求两万 token 起。
            clear("跑够 " + GoalState.MAX_GOAL_TURNS
                    + " 轮还没完,先收工了(还差:" + verdict.reason() + ")—— 想接着做再说一次 /goal");
            return;
        }
        long now = System.currentTimeMillis();
        goal.countTurn();
        persist.accept(goal);
        // goal 在类型表里恒为急件、投递方式是接续,发送方不另标。
        loop.push(List.of(new EventQueue.Entry(EventTypes.GOAL,
                GoalPrompts.progress(verdict.reason(), goal, now), now, false)));
    }

    /**
     * 给评估器看的:<b>目标设定以来</b>发生的一切。
     *
     * <p>不是"最近几句"。她可能分三次才凑够数,只看末尾就永远拼不出累计的证据——实测过
     * 一次:第一轮挖到 64/128 那条早滚出窗口,后面几轮评估器咬定"没有挖矿证据",把她赶去
     * 满世界找矿四分钟。
     *
     * <p>从末尾往回扫到目标设定那条({@link GoalPrompts#isDirective}),字数封顶兜底——整理记忆
     * 会把那条冲掉,不封顶就一路扫到会话开头。不记"设定时的历史位置":目标跨重进游戏活着,
     * 重进后历史按条数上限读回、位置早就对不上了,扫到那条消息本身才是准的。
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
