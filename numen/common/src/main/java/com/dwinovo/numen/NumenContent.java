package com.dwinovo.numen;

import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.task.TaskFactory;
import com.dwinovo.numen.task.build.BuildCompanionTask;
import com.dwinovo.numen.task.build.BuildTaskRecord;
import com.dwinovo.numen.task.inventory.DropCompanionTask;
import com.dwinovo.numen.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.task.inventory.EatCompanionTask;
import com.dwinovo.numen.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.task.inventory.EquipCompanionTask;
import com.dwinovo.numen.task.inventory.EquipTaskRecord;
import com.dwinovo.numen.task.fish.FishCompanionTask;
import com.dwinovo.numen.task.fish.FishTaskRecord;
import com.dwinovo.numen.task.combat.AttackCompanionTask;
import com.dwinovo.numen.task.combat.AttackTaskRecord;
import com.dwinovo.numen.task.interact.InteractAtCompanionTask;
import com.dwinovo.numen.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.task.interact.InteractEntityCompanionTask;
import com.dwinovo.numen.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.task.locate.LocateBiomeCompanionTask;
import com.dwinovo.numen.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.task.locate.LocateStructureCompanionTask;
import com.dwinovo.numen.task.locate.LocateStructureTaskRecord;
import com.dwinovo.numen.task.dig.DigTaskRecord;
import com.dwinovo.numen.task.dig.DigCompanionTask;
import com.dwinovo.numen.task.move.MoveToCompanionTask;
import com.dwinovo.numen.task.move.MoveToTaskRecord;

/**
 * Loader-agnostic init for the Numen tool pack — the worked example
 * of how a mod adds tools to Numen API. Each loader entry
 * point calls {@link #init()} once (on both sides: a dedicated server runs the
 * task bodies), then registers its own server-tick hooks for the tools that need
 * per-tick server work (scans, the pathfinder caches).
 *
 * <p>Things plug into Numen API here:
 * <ul>
 *   <li>command groups — registered through the plugin door, the same one third-party
 *       packs use; every action becomes a function of the script API, so the only model tool
 *       is Numen API's script tool;</li>
 *   <li>task runners — each {@code TaskRecord} type an action emits is paired with the
 *       {@code CompanionTask} that runs it, via {@link TaskFactory#register};</li>
 *   <li>the survival chains ({@link com.dwinovo.numen.api.task.BrainChains}) and their
 *       entries in the reflex roster;</li>
 *   <li>vanilla armour as the first gear source.</li>
 * </ul>
 */
public final class NumenContent {

    private static boolean initialised = false;

    /** Numen 在插件那扇门里的手柄(名字空间 {@code numen}),登记时拿到,运行中发事件用。 */
    private static volatile com.dwinovo.numen.api.NumenApi api;

    /** Numen 的 {@link com.dwinovo.numen.api.NumenApi}。{@link #init()} 之后才有。 */
    public static com.dwinovo.numen.api.NumenApi api() {
        com.dwinovo.numen.api.NumenApi handle = api;
        if (handle == null) {
            throw new IllegalStateException("NumenContent.init() has not run");
        }
        return handle;
    }

    private NumenContent() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        registerTools();
        registerTaskRunners();
        // 原版四件甲是第一处穿戴来源,和模组的饰品栏走同一扇门;内嵌联动在这之后才开闸,所以原版排在最前
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen ->
                numen.registerGear(new com.dwinovo.numen.gear.VanillaArmor()));
        registerReflexes();
        enlistReflexRoster();
        // 寻路调试开关挂在 /numen 下
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen ->
                numen.command(com.dwinovo.numen.debug.PathCommands.verb()));
        Constants.LOG.info("[numen-content] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), TaskFactory.size());
    }

    /** 把 Numen 的五条生存本能链插进 Numen API 的竞价调度(链登记口)。 */
    private static void registerReflexes() {
        // 注册号小的先问 —— 与原版 addGoal(int priority, goal) 同一惯例:摔落缓冲 > 换气 > 逃跑 > 自卫 > 脱困。
        // 逃跑压过自卫:扛不住时先跑,跑不掉它让出身体,自卫接着打。
        // 本能之间的先后是固定的,不随世界状态变,所以是一个序号,不是一个要现算的出价。
        //
        // 正在坠落是最迫近的死法,所以摔落缓冲压过一切;卡住只是烦人,绝不该压过
        // 打架 —— 这条排序是有单测守着的(ReflexOrderTest)。
        com.dwinovo.numen.api.task.BrainChains.register(10,
                com.dwinovo.numen.task.chain.MLGChain::new);
        com.dwinovo.numen.api.task.BrainChains.register(20,
                com.dwinovo.numen.task.chain.BreathChain::new);
        com.dwinovo.numen.api.task.BrainChains.register(25,
                com.dwinovo.numen.task.chain.FleeChain::new);
        com.dwinovo.numen.api.task.BrainChains.register(30,
                com.dwinovo.numen.task.chain.MobDefenseChain::new);
        com.dwinovo.numen.api.task.BrainChains.register(50,
                com.dwinovo.numen.task.chain.UnstuckChain::new);
    }

    /**
     * The reflex roster (constitution §6): enlist Numen's instincts — the five survival
     * chains — so their one-line self-descriptions reach the prompt. Runs on BOTH sides
     * like the rest of init.
     */
    private static void enlistReflexRoster() {
        com.dwinovo.numen.task.reflex.NumenReflexes.registerAll();
    }

    /**
     * Numen API 自己的 API 组,和插件走同一扇门({@code NumenPlugins.register})。{@code route} 在 {@code move} 之前:路线描述里的几种值与
     * {@code numen.move.go} 收的计划在它那里登记。
     */
    private static void registerTools() {
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> {
            api = numen;
            com.dwinovo.numen.tools.perception.StatusApi.install(numen);
            com.dwinovo.numen.tools.perception.ScanApi.install(numen);
            com.dwinovo.numen.tools.locate.LocateApi.install(numen);
            com.dwinovo.numen.tools.work.RouteApi.install(numen);
            com.dwinovo.numen.tools.work.MoveApi.install(numen);
            com.dwinovo.numen.tools.work.WorkApi.install(numen);
            com.dwinovo.numen.tools.work.BuildApi.install(numen);
            com.dwinovo.numen.tools.work.FightApi.install(numen);
            com.dwinovo.numen.tools.interact.UseApi.install(numen);
            com.dwinovo.numen.tools.interact.GuiApi.install(numen);
            com.dwinovo.numen.tools.inventory.InvApi.install(numen);
            com.dwinovo.numen.tools.inventory.GearApi.install(numen);
            com.dwinovo.numen.tools.inventory.CreativeApi.install(numen);
            com.dwinovo.numen.tools.time.TimeApi.install(numen);
        });
    }


    private static void registerTaskRunners() {
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.task.move.FollowTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.task.move.FollowCompanionTask(p, r));
        TaskFactory.register(DigTaskRecord.class, (p, r) -> new DigCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.task.inventory.UnequipTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.task.inventory.UnequipCompanionTask(p, r));
        TaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.task.inventory.TransferTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.task.inventory.TransferCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.task.inventory.GuiItemsTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.task.inventory.GuiItemsCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.task.wait.WaitTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.task.wait.WaitCompanionTask(p, r));
        TaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        TaskFactory.register(AttackTaskRecord.class, (p, r) -> new AttackCompanionTask(p, r));
        TaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        TaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        TaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        TaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        TaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureCompanionTask(p, r));
        TaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeCompanionTask(p, r));
    }
}
