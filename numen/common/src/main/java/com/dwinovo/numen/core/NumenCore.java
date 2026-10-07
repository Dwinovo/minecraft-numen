package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskFactory;
import com.dwinovo.numen.core.task.build.BuildCompanionTask;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.inventory.DropCompanionTask;
import com.dwinovo.numen.core.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.EatCompanionTask;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.task.inventory.EquipCompanionTask;
import com.dwinovo.numen.core.task.inventory.EquipTaskRecord;
import com.dwinovo.numen.core.task.fish.FishCompanionTask;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.task.combat.AttackCompanionTask;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateBiomeCompanionTask;
import com.dwinovo.numen.core.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateStructureCompanionTask;
import com.dwinovo.numen.core.task.locate.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.task.move.MoveToCompanionTask;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;

/**
 * Loader-agnostic init for the {@code numen-core} tool pack — the worked example
 * of how a mod adds tools to the {@code numen-api} engine. Each loader entry
 * point calls {@link #init()} once (on both sides: a dedicated server runs the
 * task bodies), then registers its own server-tick hooks for the tools that need
 * per-tick server work (scans, the pathfinder caches).
 *
 * <p>Things plug into the engine here:
 * <ul>
 *   <li>command groups — registered through the plugin door, the same one third-party
 *       packs use; every action becomes a function of the script API, so the only model tool
 *       is the engine's script tool;</li>
 *   <li>task runners — each {@code TaskRecord} type an action emits is paired with the
 *       {@code CompanionTask} that runs it, via {@link TaskFactory#register};</li>
 *   <li>the survival chains ({@link com.dwinovo.numen.task.BrainChains}) and their
 *       entries in the reflex roster;</li>
 *   <li>vanilla armour as the first gear source.</li>
 * </ul>
 */
public final class NumenCore {

    private static boolean initialised = false;

    private NumenCore() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        registerTools();
        registerTaskRunners();
        // 原版四件甲是第一处穿戴来源,和模组的饰品栏走同一扇门;内嵌联动在这之后才开闸,所以原版排在最前
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen ->
                numen.registerGear(new com.dwinovo.numen.core.gear.VanillaArmor()));
        registerReflexes();
        enlistReflexRoster();
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), TaskFactory.size());
    }

    /** 把 core 的五条生存本能链插进引擎的竞价调度(链登记口)。 */
    private static void registerReflexes() {
        // 注册号小的先问 —— 与原版 addGoal(int priority, goal) 同一惯例:摔落缓冲 > 换气 > 逃跑 > 自卫 > 脱困。
        // 逃跑压过自卫:扛不住时先跑,跑不掉它让出身体,自卫接着打。
        // 本能之间的先后是固定的,不随世界状态变,所以是一个序号,不是一个要现算的出价。
        //
        // 正在坠落是最迫近的死法,所以摔落缓冲压过一切;卡住只是烦人,绝不该压过
        // 打架 —— 这条排序是有单测守着的(ReflexOrderTest)。
        com.dwinovo.numen.task.BrainChains.register(10,
                com.dwinovo.numen.core.task.chain.MLGChain::new);
        com.dwinovo.numen.task.BrainChains.register(20,
                com.dwinovo.numen.core.task.chain.BreathChain::new);
        com.dwinovo.numen.task.BrainChains.register(25,
                com.dwinovo.numen.core.task.chain.FleeChain::new);
        com.dwinovo.numen.task.BrainChains.register(30,
                com.dwinovo.numen.core.task.chain.MobDefenseChain::new);
        com.dwinovo.numen.task.BrainChains.register(50,
                com.dwinovo.numen.core.task.chain.UnstuckChain::new);
    }

    /**
     * The reflex roster (constitution §6): enlist core's instincts — the five survival
     * chains — so their one-line self-descriptions reach the prompt. Runs on BOTH sides
     * like the rest of init.
     */
    private static void enlistReflexRoster() {
        com.dwinovo.numen.core.task.reflex.CoreReflexes.registerAll();
    }

    /**
     * 引擎自己的 API 组,和插件走同一扇门({@code NumenPlugins.register})。{@code route} 在 {@code move} 之前:路线描述里的几种值与
     * {@code numen.move.go} 收的计划在它那里登记。
     */
    private static void registerTools() {
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> {
            com.dwinovo.numen.core.tools.perception.StatusApi.install(numen);
            com.dwinovo.numen.core.tools.perception.ScanApi.install(numen);
            com.dwinovo.numen.core.tools.locate.LocateApi.install(numen);
            com.dwinovo.numen.core.tools.work.RouteApi.install(numen);
            com.dwinovo.numen.core.tools.work.MoveApi.install(numen);
            com.dwinovo.numen.core.tools.work.WorkApi.install(numen);
            com.dwinovo.numen.core.tools.work.BuildApi.install(numen);
            com.dwinovo.numen.core.tools.work.FightApi.install(numen);
            com.dwinovo.numen.core.tools.interact.UseApi.install(numen);
            com.dwinovo.numen.core.tools.interact.GuiApi.install(numen);
            com.dwinovo.numen.core.tools.inventory.InvApi.install(numen);
            com.dwinovo.numen.core.tools.inventory.GearApi.install(numen);
            com.dwinovo.numen.core.tools.inventory.CreativeApi.install(numen);
            com.dwinovo.numen.core.tools.time.TimeApi.install(numen);
        });
    }


    private static void registerTaskRunners() {
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.move.FollowTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.move.FollowCompanionTask(p, r));
        TaskFactory.register(DigTaskRecord.class, (p, r) -> new DigCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.inventory.UnequipTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.inventory.UnequipCompanionTask(p, r));
        TaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.inventory.TransferTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.inventory.TransferCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.inventory.GuiItemsTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.inventory.GuiItemsCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.wait.WaitTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.wait.WaitCompanionTask(p, r));
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
