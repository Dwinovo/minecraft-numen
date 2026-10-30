package com.dwinovo.numen.paper.gametest;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.dwinovo.numen.api.gametest.GameTestSuites;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.gametest.framework.StructureGridSpawner;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper 上的 GameTest 入口(docs/paper.md §十一)。原版的 GameTest 框架是 {@code GameTestServer} 那台专用测试服务器在驱动的,
 * Paper 起的是普通服务端,所以这个插件做 {@code GameTestServer} 和模组加载器各做的那几件事,用例本身一个字不改:
 *
 * <ul>
 *   <li><b>登记</b>:{@link GameTestRegistry#register} 登记套件入口 {@link GameTestSuites}(与 NeoForge 的
 *       {@code RegisterGameTestsEvent} 做的是同一件事);</li>
 *   <li><b>推进</b>:不开 {@code SharedConstants.IS_RUNNING_IN_IDE}(开了会把测试用的参数类型塞进内置注册表,有 op 的客户端进服就断),
 *       所以 {@code MinecraftServer} 不推 {@link GameTestTicker},在每刻末自己推;</li>
 *   <li><b>结构模板</b>:原版只在 IDE 旗标下从 SNBT 目录读模板,这里把目录里每个用例要的模板读出来,放进结构管理器的缓存;</li>
 *   <li><b>世界</b>:{@code GameTestServer} 的世界是它自己建的(超平坦、一组固定的游戏规则、种子由 Mixin 指定、每次从空目录开始),
 *       Paper 的世界由起服的一层给:全新的运行目录、{@code server.properties} 里的超平坦与种子;游戏规则在这里定。
 *       真实地形维度({@code numen_test:terrain})要数据包定义的维度,Paper 起服后加不了维度,依赖它的套件({@code numen_pathing_terrain_bake})
 *       不在这里跑;</li>
 *   <li><b>收场</b>:全部用例结束,把结果写成 JSON({@value #RESULT} 指的文件)并关服。</li>
 * </ul>
 */
public final class PaperGameTestPlugin extends JavaPlugin implements Listener {

    /** 结果文件的系统属性。 */
    public static final String RESULT = "numen.gametest.result";

    /** 只跑名字(整个)匹配这个正则的用例;没给就是选中套件的全部。排查单条或一个类时用,{@code -Pgametest.only=…}。 */
    public static final String ONLY = "numen.gametest.only";

    /** 场地铺在世界里的起点:离出生点远,不碰默认的出生区块;和 GameTestServer 一样贴着超平坦的地面。 */
    /** 刻与墙钟的差在这么多刻的窗口里漂移不超过 {@link #STEADY_DRIFT},就算服务端追上了墙钟。 */
    private static final int STEADY_TICKS = 100;
    private static final int STEADY_DRIFT = 2;

    private static final BlockPos ORIGIN = new BlockPos(4096, -59, 4096);

    /**
     * 排查用:给了系统属性 {@code numen.gametest.maxTicks}({@code -PgametestMaxTicks=…}),每条用例最多等这么多刻(见 {@link #capped}),
     * 没给就用每条用例自己写的时限。服务端按 20 刻每秒走,不加速:Paper 的挖掘进度按墙钟算
     * ({@code ServerLevel.getLagCompensationTick}),刻跑得比墙钟快,服务端就认为她挖得太快、把 STOP 退回去,所有生存模式的挖都做不成。
     */
    private static final Integer MAX_TICKS = Integer.getInteger("numen.gametest.maxTicks");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private MinecraftServer server;
    private MultipleTestTracker tracker;
    private List<GameTestInfo> infos = List.of();
    private long startedNanos;
    private long ticks;
    private boolean warmingUp;
    private long lastSkew;
    private int steadyTicks;
    /** 被收短了时限的用例:名字 → 它自己写的时限(刻)。 */
    private final Map<String, Integer> capped = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        GameTestRegistry.register(GameTestSuites.class);
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** 起服完成(世界都在了)。开跑要等服务端的刻追上墙钟,见 {@link #warmUp}。 */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
            warmingUp = true;
        }
    }

    /**
     * 起服花的时间长,服务端的刻欠着墙钟,开服后头几秒是一刻接一刻补回来的,比 20 刻每秒快得多。Paper 的挖掘进度按墙钟算
     * ({@code getLagCompensationTick}),刻跑得比墙钟快的时候,生存模式的挖会被服务端退回。这不是用例要测的,所以等刻和墙钟
     * 的差稳住了(在 {@value #STEADY_TICKS} 刻的窗口里漂移不超过 {@value #STEADY_DRIFT})再开跑。
     */
    private void warmUp() {
        server = ((CraftServer) getServer()).getServer();
        long skew = MinecraftServer.currentTick - server.overworld().getLagCompensationTick();
        // 追赶的时候差是慢慢漂的(每刻只差半刻),逐刻比看不出来,所以和窗口开头比
        if (steadyTicks == 0 || Math.abs(skew - lastSkew) > STEADY_DRIFT) {
            lastSkew = skew;
            steadyTicks = 0;
        }
        if (++steadyTicks >= STEADY_TICKS) {
            warmingUp = false;
            start();
        }
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        if (tracker == null) {
            if (warmingUp) {
                guarded(this::warmUp);
            }
            return;
        }
        guarded(() -> {
            // 与原版一致:刻被冻结(/tick freeze)时用例也停着
            if (server.tickRateManager().runsNormally()) {
                GameTestTicker.SINGLETON.tick();
            }
            if (++ticks % 4000 == 0) {
                getLogger().info("进度 " + tracker.getDoneCount() + "/" + tracker.getTotalCount());
            }
            if (tracker.isDone()) {
                finish(null);
            }
        });
    }

    private void start() {
        server = ((CraftServer) getServer()).getServer();
        ServerLevel level = server.overworld();
        String only = System.getProperty(ONLY);
        List<TestFunction> functions = GameTestRegistry.getAllTestFunctions().stream().filter(f -> !f.manualOnly())
                .filter(f -> only == null || f.testName().matches(only))
                .map(this::capped).toList();
        if (functions.isEmpty()) {
            throw new IllegalStateException("没有登记任何用例,选中的套件是 \"" + System.getProperty(GameTestSuites.SELECTION) + "\"");
        }
        settle(level);
        loadTemplates(level, functions);
        Collection<GameTestBatch> batches = GameTestBatchFactory.fromTestFunction(functions, level);
        GameTestRunner runner = GameTestRunner.Builder.fromBatches(batches, level)
                .newStructureSpawner(new StructureGridSpawner(ORIGIN, GameTestRunner.DEFAULT_TESTS_PER_ROW, false))
                .build();
        infos = runner.getTestInfos();
        tracker = new MultipleTestTracker(infos);
        startedNanos = System.nanoTime();
        getLogger().info(tracker.getTotalCount() + " tests are now running at position " + ORIGIN.toShortString() + "!");
        runner.start();
    }

    /**
     * 给了 {@link #MAX_TICKS} 时,时限不超过它:用例给自己留的时限(最长四十万刻)是为"本来就能很快做完"的行为留的余量,Paper 上一刻
     * 50 毫秒,没做成的那条要等满才失败。排查时收短它能快点看到哪些没做成;收短的用例在结果里记着它自己写的时限,
     * 超时失败的那些要和"真失败"分开看。
     */
    private TestFunction capped(TestFunction f) {
        if (MAX_TICKS == null || f.maxTicks() <= MAX_TICKS) {
            return f;
        }
        capped.put(f.testName(), f.maxTicks());
        return new TestFunction(f.batchName(), f.testName(), f.structureName(), f.rotation(), MAX_TICKS, f.setupTicks(),
                f.required(), f.manualOnly(), f.maxAttempts(), f.requiredSuccesses(), f.skyAccess(), f.function());
    }

    /** GameTestServer 的固定游戏规则与天气。 */
    private void settle(ServerLevel level) {
        GameRules rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, level);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, level);
        level.setDefaultSpawnPos(ORIGIN, 0.0F);
        level.setWeatherParameters(20000000, 20000000, false, false);
    }

    /**
     * 每个用例要的结构模板读进结构管理器。模板文件按路径找({@code 命名空间:名字} 取名字,与 NeoForge 的 SNBT 目录同一个规矩:
     * 目录是平的、命名空间不参与),没有文件的不放,由框架报"缺模板"让那条用例失败。
     */
    private void loadTemplates(ServerLevel level, List<TestFunction> functions) {
        String directory = System.getProperty(GameTestSuites.STRUCTURES);
        if (directory == null) {
            return;
        }
        StructureTemplateManager manager = server.getStructureManager();
        for (String name : functions.stream().map(TestFunction::structureName).distinct().toList()) {
            ResourceLocation id = ResourceLocation.parse(name);
            Path file = Path.of(directory, id.getPath() + ".snbt");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                manager.getOrCreate(id).load(level.holderLookup(Registries.BLOCK), NbtUtils.snbtToStructure(Files.readString(file)));
            } catch (IOException e) {
                throw new UncheckedIOException(file.toString(), e);
            } catch (CommandSyntaxException e) {
                throw new IllegalStateException(file + " 不是合法的结构 SNBT", e);
            }
        }
    }

    /** 收场:把每条用例的结果写成 JSON 并关服。{@code fatal} 非空表示没能把用例跑完。 */
    private void finish(Throwable fatal) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tests = new ArrayList<>();
        if (tracker != null) {
            for (GameTestInfo info : infos) {
                Map<String, Object> test = new LinkedHashMap<>();
                test.put("name", info.getTestName());
                test.put("batch", info.getTestFunction().batchName());
                test.put("required", info.isRequired());
                test.put("passed", info.hasSucceeded());
                if (capped.containsKey(info.getTestName())) {
                    test.put("cappedFromTicks", capped.get(info.getTestName()));
                }
                if (info.hasFailed()) {
                    test.put("error", String.valueOf(info.getError()));
                } else if (!info.isDone()) {
                    test.put("error", "没跑完");
                }
                tests.add(test);
            }
            result.put("durationMillis", (System.nanoTime() - startedNanos) / 1_000_000);
        }
        result.put("tests", tests);
        if (fatal != null) {
            result.put("fatal", fatal.toString());
        }
        try {
            Files.writeString(Path.of(System.getProperty(RESULT)), gson.toJson(result));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        getServer().shutdown();
    }

    /** 框架这一层出了错(不是用例失败):记下来、把结果写出去、关服,不让服务端空等到外面的时限。 */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            getLogger().severe("GameTest 没能进行下去: " + t);
            t.printStackTrace();
            finish(t);
        }
    }
}
