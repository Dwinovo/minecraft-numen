package com.dwinovo.numen.bench;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 真模型评测的入口:登记一组场景,得到跑这组场景的 GameTest 用例。原版一组,每个联动在自己的评测源码集里各登记一组,
 * 写法同登记命令组({@code numen.registerCommands(组名, 一句话, 动作)}):
 *
 * <pre>{@code
 * public final class TlmBench {
 *     @BenchSuite
 *     public static Optional<Bench.Run> scenarios() {
 *         return Bench.suite("tlm", "Touhou Little Maid: taming and keeping maids.",
 *                 suite -> suite.add(MaidFarmhand::new));
 *     }
 * }
 * }</pre>
 *
 * 要跑哪些由 {@code -Dbench.scenarios} 选(见 {@code docs/bench.md});一个都没选中就一条用例都不给,什么都不跑。
 * 一组的场景在一条用例里一次一次地跑,组与组之间各占一批,也是一组跑完再跑下一组。并行跑({@code runBenchParallel})是几个
 * 服务器进程各跑一份场景({@link Settings#mine}),每份自己的世界、目录与静态状态,彼此碰不到。
 *
 * <p>登记进游戏的是 {@link NumenBench}:{@code RegisterGameTestsEvent} 里扫出所有 {@link BenchSuite} 方法,每个选中的组
 * 登记成一条 {@link BenchTestInstance}。
 */
public final class Bench {

    /** 评测用例的命名空间;评测的运行配置只开这一个。 */
    public static final String NAMESPACE = "numen_bench";

    private Bench() {}

    /** 一组被选中的场景,与跑它们的会话:登记成 GameTest 用例前的样子。 */
    public static final class Run {

        private final String name;
        private final Session session;

        private Run(String name, Session session) {
            this.name = name;
            this.session = session;
        }

        String name() {
            return name;
        }

        Session session() {
            return session;
        }
    }

    /**
     * 登记一组场景。
     *
     * @param name      组名:小写字母开头,只含 [a-z0-9_]
     * @param summary   一句话说这组测什么
     * @param scenarios 往组里加场景
     * @return 跑这组里被选中场景的会话;没选中是空的
     */
    public static Optional<Run> suite(String name, String summary, Consumer<Suite> scenarios) {
        Suite suite = new Suite(name, summary);
        scenarios.accept(suite);
        Settings settings = Settings.fromSystem();
        List<String> picked = suite.pick(settings.scenarios());
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        // 并行跑时这一份只跑分给它的;一个都没分到也给一条当场跑完的用例:GameTest 服务器一条用例都没有就起不来
        picked = settings.mine(picked);
        return Optional.of(new Run(name, new Session(suite, picked, settings)));
    }
}
