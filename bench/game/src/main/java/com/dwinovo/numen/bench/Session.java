package com.dwinovo.numen.bench;

import com.dwinovo.numen.bench.report.EndReason;
import com.dwinovo.numen.bench.report.Run;
import com.dwinovo.numen.bench.report.Summary;
import com.dwinovo.numen.bench.report.Variant;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 一组场景的一次评测,就是一条 GameTest 用例的全部:一次一次地跑,不并行。每个场景先跑两种基线——标准解必须过、空操作
 * 必须挂——两条都对上了才跑真实模型;对不上的场景不花 API,记为自检失败。
 *
 * <p>用例本身的成败只说评测自己靠不靠得住:自检全对、没有评测出错、没被余额不足打断,就通过。模型成不成功只进报告。
 */
final class Session {

    /** 一次运行搭场景、等她开口、收场的余量(刻),加在每次的预算上算用例的超时。 */
    private static final int OVERHEAD_TICKS = 600;

    private record Planned(String id, Variant variant, int number) {}

    private final Suite suite;
    private final Settings settings;
    private final Deque<Planned> plan = new ArrayDeque<>();
    private final Map<String, List<Run>> baselines = new HashMap<>();
    /** 让这条用例失败的理由;空 = 评测靠得住。 */
    private final List<String> problems = new ArrayList<>();
    private final int maxTicks;
    private ServerLevel level;
    private BlockPos anchor;
    private int arenas;
    private Attempt current;
    /** 真实模型不再跑了(没有 key、余额不足)。 */
    private boolean liveOff;

    Session(Suite suite, List<String> ids, Settings settings) {
        this.suite = suite;
        this.settings = settings;
        int ticks = 0;
        for (String id : ids) {
            plan.add(new Planned(id, Variant.SOLUTION, 1));
            plan.add(new Planned(id, Variant.NOOP, 1));
            ticks += (2 + settings.repeats()) * (scenario(id).budget().ticks() + OVERHEAD_TICKS);
        }
        this.maxTicks = ticks;
    }

    int maxTicks() {
        return maxTicks;
    }

    void run(GameTestHelper helper) {
        level = helper.getLevel();
        anchor = helper.absolutePos(BlockPos.ZERO).above();
        settleWorld(level);
        if (settings.repeats() > 0 && !settings.hasKey()) {
            problems.add("要跑真实模型却没有 API key(环境变量 " + Settings.KEY_ENV + ")");
            liveOff = true;
        }
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!step()) {
                        throw new GameTestAssertException("评测进行中");
                    }
                })
                .thenExecute(() -> {
                    if (!problems.isEmpty()) {
                        throw new GameTestAssertException(String.join(";", problems));
                    }
                })
                .thenSucceed();
    }

    /** 往前走一刻;全部跑完返回 {@code true}。 */
    private boolean step() {
        if (current == null) {
            Planned next = plan.poll();
            if (next == null) {
                return true;
            }
            current = new Attempt(suite.name(), scenario(next.id()), next.variant(), next.number(), settings, level,
                    anchor.offset(Arena.SPACING * ++arenas, 0, 0));
            try {
                current.start();
            } catch (Exception | LinkageError e) {
                current.crash(e);
            }
        } else {
            try {
                current.tick();
            } catch (Exception | LinkageError e) {
                current.crash(e);
            }
        }
        if (current.ended()) {
            Attempt done = current;
            current = null;
            Run run = done.finish();
            Results.get().record(run);
            after(done, run);
        }
        return false;
    }

    /** 一次跑完之后:评测出错记下;两种基线齐了就判这个场景可不可信,可信才排上真实模型;余额不足就不再调模型。 */
    private void after(Attempt done, Run run) {
        String name = suite.name() + "/" + run.scenario();
        if (EndReason.valueOf(run.end()) == EndReason.HARNESS_ERROR) {
            problems.add(name + " " + run.variant() + " #" + run.attempt() + " 评测出错:" + run.error());
        }
        if (done.variant != Variant.LIVE) {
            List<Run> both = baselines.computeIfAbsent(run.scenario(), k -> new ArrayList<>());
            both.add(run);
            if (done.variant == Variant.NOOP) {
                String verdict = Summary.verdict(Summary.of(both, Variant.SOLUTION), Summary.of(both, Variant.NOOP));
                if (!verdict.equals("可信")) {
                    problems.add(name + " 自检没过:" + verdict);
                } else if (!liveOff) {
                    for (int i = settings.repeats(); i >= 1; i--) {
                        plan.addFirst(new Planned(run.scenario(), Variant.LIVE, i));
                    }
                }
            }
        } else if (done.outOfBalance()) {
            liveOff = true;
            plan.removeIf(p -> p.variant() == Variant.LIVE);
            problems.add("API 余额不足,真实模型的运行停在 " + name + " #" + run.attempt() + ":" + run.error());
        }
    }

    private Scenario scenario(String id) {
        Supplier<? extends Scenario> make = suite.scenarios().get(id);
        return make.get();
    }

    /**
     * 评测的世界:和平、正午且不走时间、晴天、不刷怪。每次运行都从同一个样子开始,不受上一次跑了多久影响。
     */
    private static void settleWorld(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setWeatherParameters(24000, 0, false, false);
    }
}
