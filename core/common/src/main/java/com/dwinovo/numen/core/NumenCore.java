package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskFactory;
import com.dwinovo.numen.core.task.build.BuildCompanionTask;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.collect.CollectItemsCompanionTask;
import com.dwinovo.numen.core.task.collect.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.DropCompanionTask;
import com.dwinovo.numen.core.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.EatCompanionTask;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.task.inventory.EquipCompanionTask;
import com.dwinovo.numen.core.task.inventory.EquipTaskRecord;
import com.dwinovo.numen.core.task.fish.FishCompanionTask;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.task.combat.MeleeAttackCompanionTask;
import com.dwinovo.numen.core.task.combat.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.combat.RangedAttackCompanionTask;
import com.dwinovo.numen.core.task.combat.RangedAttackTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateBiomeCompanionTask;
import com.dwinovo.numen.core.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateStructureCompanionTask;
import com.dwinovo.numen.core.task.locate.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.mine.MineCompanionTask;
import com.dwinovo.numen.core.task.move.MoveToCompanionTask;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;

/**
 * Loader-agnostic init for the {@code numen-core} tool pack — the worked example
 * of how a mod adds tools to the {@code numen-api} engine. Each loader entry
 * point calls {@link #init()} once (on both sides: a dedicated server runs the
 * task bodies), then registers its own server-tick hooks for the tools that need
 * per-tick server work (scans, the pathfinder caches).
 *
 * <p>Two things plug into the engine here:
 * <ul>
 *   <li>tools — each a {@link com.dwinovo.numen.agent.tool.NumenTool} (raw) and
 *       added to the global {@link ToolRegistry} (order preserved for prompt
 *       caching);</li>
 *   <li>task runners — each {@code TaskRecord} type a world-action tool emits is
 *       paired with the {@code CompanionTask} that runs it, via
 *       {@link CompanionTaskFactory#register}.</li>
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
        registerReflexes();
        enlistReflexRoster();
        // Enable the autonomous survival chains (auto-eat / mob-defense / unstuck /
        // MLG). SurvivalConfig's own default is OFF — the safe state a bare library
        // build ships with — and the pack turns it on here, explicitly, at init.
        com.dwinovo.numen.core.task.SurvivalConfig.setEnabled(true);
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), TaskFactory.size());
    }

    /**
     * 把 core 的五条生存本能链插进引擎的竞价调度(链登记口)。运输包与
     * 生命周期对接已随排程机器归引擎,不再是 core 的事。
     */
    private static void registerReflexes() {
        // 注册号小的先问 —— 与原版 addGoal(int priority, goal) 同一惯例。
        // 顺序<b>照搬旧的浮点优先级</b>(MLG 10 > 换气 6 > 自卫 5 > 进食 4/3 > 脱困 2),
        // 那些数值本身已经退役:反射之间的先后是固定的,不随世界状态变,用连续量
        // 表达一个固定序,数值就成了必须维护却没人看得懂的魔法数。
        //
        // 正在坠落是最迫近的死法,所以摔落缓冲压过一切;卡住只是烦人,绝不该压过
        // 打架或吃饭 —— 这条排序是有单测守着的(ReflexOrderTest)。
        com.dwinovo.numen.task.BrainChains.register(10,
                com.dwinovo.numen.core.task.chain.MLGChain::new);
        com.dwinovo.numen.task.BrainChains.register(20,
                com.dwinovo.numen.core.task.chain.BreathChain::new);
        com.dwinovo.numen.task.BrainChains.register(30,
                com.dwinovo.numen.core.task.chain.MobDefenseChain::new);
        com.dwinovo.numen.task.BrainChains.register(40,
                com.dwinovo.numen.core.task.chain.FoodChain::new);
        com.dwinovo.numen.task.BrainChains.register(50,
                com.dwinovo.numen.core.task.chain.UnstuckChain::new);
    }

    /**
     * The reflex roster (constitution §6): enlist core's instincts — the five
     * survival chains and the pure policies. The switch persistence is bound by
     * the engine ({@code CommonClass.wireTaskMachine}). Runs on BOTH sides like
     * the rest of init.
     */
    private static void enlistReflexRoster() {
        com.dwinovo.numen.core.task.reflex.CoreReflexes.registerAll();
    }

    private static void registerTools() {

        // Registration ORDER is preserved (backends with prompt-caching keyed off
        // the tool list cache stably across requests).
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.MoveToTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.MeleeAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.RangedAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.locate.LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.locate.LocateBiomeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.CollectItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.FishTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.AutoMineTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.EquipItemTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.BuildTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.work.BlueprintTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.BlueprintReadTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.interact.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.interact.InteractEntityTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.EatItemTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStopTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.DropItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.TakeItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.interact.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.TransferTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.interact.CloseGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.GetSelfStatusTool());   // SAMPLE: raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.GetOwnerStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.LookupRecipeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.inventory.CraftTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.ScanNearbyEntitiesTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.ScanBlocksTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.LookAroundTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.InspectBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.InspectBlockStorageTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.perception.GetWorldInfoTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.agent.TodoWriteTool());   // raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.agent.LoadSkillTool());   // raw NumenTool
    }


    private static void registerTaskRunners() {
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        TaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        TaskFactory.register(MeleeAttackTaskRecord.class, (p, r) -> new MeleeAttackCompanionTask(p, r));
        TaskFactory.register(RangedAttackTaskRecord.class, (p, r) -> new RangedAttackCompanionTask(p, r));
        TaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsCompanionTask(p, r));
        TaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        TaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        TaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        TaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        TaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureCompanionTask(p, r));
        TaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeCompanionTask(p, r));
    }
}
