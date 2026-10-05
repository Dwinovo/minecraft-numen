package com.dwinovo.numen.bench;

import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * 真模型评测的入口:登记一组场景,得到跑这组场景的 GameTest 用例。原版一组,每个联动在自己的评测源码集里各登记一组,
 * 写法同登记命令组({@code numen.registerCommands(组名, 一句话, 动作)}):
 *
 * <pre>{@code
 * @GameTestHolder(Bench.NAMESPACE)
 * public final class TlmBench {
 *     @GameTestGenerator
 *     public static Collection<TestFunction> scenarios() {
 *         return Bench.suite("tlm", "Touhou Little Maid: taming and keeping maids.",
 *                 suite -> suite.add(MaidFarmhand::new));
 *     }
 * }
 * }</pre>
 *
 * 要跑哪些由 {@code -Dbench.scenarios} 选(见 {@code docs/bench.md});一个都没选中就一条用例都不给,什么都不跑。
 * 一组的场景在一条用例里一次一次地跑,组与组之间各占一批,也是一组跑完再跑下一组。并行跑({@code runBenchParallel})是几个
 * 服务器进程各跑一份场景({@link Settings#mine}),每份自己的世界、目录与静态状态,彼此碰不到。
 */
public final class Bench {

    /** 评测用例的命名空间;评测的运行配置只开这一个。 */
    public static final String NAMESPACE = "numen_bench";
    /**
     * 用例的结构模板。场地由代码现搭({@link Arena}),模板只是 GameTest 要的一个落脚点,借用运行配置经
     * {@code numen.gametest.structures} 指过来的模板目录里的一块地板。
     */
    private static final String ANCHOR = "floor16";

    private Bench() {}

    /**
     * 登记一组场景。
     *
     * @param name      组名:小写字母开头,只含 [a-z0-9_]
     * @param summary   一句话说这组测什么
     * @param scenarios 往组里加场景
     * @return 跑这组里被选中场景的用例;没选中是空的
     */
    public static Collection<TestFunction> suite(String name, String summary, Consumer<Suite> scenarios) {
        Suite suite = new Suite(name, summary);
        scenarios.accept(suite);
        Settings settings = Settings.fromSystem();
        List<String> picked = suite.pick(settings.scenarios());
        if (picked.isEmpty()) {
            return List.of();
        }
        // 并行跑时这一份只跑分给它的;一个都没分到也给一条当场跑完的用例:GameTest 服务器一条用例都没有就起不来
        picked = settings.mine(picked);
        String structures = System.getProperty("numen.gametest.structures");
        if (structures == null) {
            throw new IllegalStateException("评测要 GameTest 的模板目录:运行配置没有给 -Dnumen.gametest.structures");
        }
        StructureUtils.testStructuresDir = structures;
        Session session = new Session(suite, picked, settings);
        return List.of(new TestFunction("numen_bench_" + name, "bench_" + name, NAMESPACE + ":" + ANCHOR,
                Rotation.NONE, session.maxTicks(), 0, true, session::run));
    }
}
