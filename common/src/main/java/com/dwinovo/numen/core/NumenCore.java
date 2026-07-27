package com.dwinovo.numen.core;

import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.CompanionTask;

import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.network.payload.TaskResultPayload;
import com.dwinovo.numen.task.CompanionTaskFactory;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.BuildCompanionTask;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.core.task.CollectItemsTaskGoal;
import com.dwinovo.numen.core.task.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.DropCompanionTask;
import com.dwinovo.numen.core.task.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.EatCompanionTask;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.EquipCompanionTask;
import com.dwinovo.numen.core.task.EquipTaskRecord;
import com.dwinovo.numen.core.task.FishCompanionTask;
import com.dwinovo.numen.core.task.FishTaskRecord;
import com.dwinovo.numen.core.task.MeleeAttackCompanionTask;
import com.dwinovo.numen.core.task.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.RangedAttackCompanionTask;
import com.dwinovo.numen.core.task.RangedAttackTaskRecord;
import com.dwinovo.numen.core.task.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.LocateBiomeTaskGoal;
import com.dwinovo.numen.core.task.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.LocateStructureTaskGoal;
import com.dwinovo.numen.core.task.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MineCompanionTask;
import com.dwinovo.numen.core.task.MoveToCompanionTask;
import com.dwinovo.numen.core.task.MoveToTaskRecord;

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
        registerChains();
        registerReflexes();
        // Enable the autonomous survival chains (auto-eat / mob-defense / unstuck /
        // MLG). SurvivalConfig's own default is OFF — the safe state a bare library
        // build ships with — and the pack turns it on here, explicitly, at init.
        com.dwinovo.numen.core.task.SurvivalConfig.setEnabled(true);
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), CompanionTaskFactory.size());
    }

    /**
     * 把 core 的五条生存本能链插进引擎的竞价调度(链登记口)。运输包与
     * 生命周期对接已随排程机器归引擎,不再是 core 的事。
     */
    private static void registerChains() {
        com.dwinovo.numen.task.BrainChains.register(10,
                bodyLog -> new com.dwinovo.numen.core.task.chain.UnstuckChain());
        com.dwinovo.numen.task.BrainChains.register(20,
                com.dwinovo.numen.core.task.chain.MobDefenseChain::new);
        com.dwinovo.numen.task.BrainChains.register(30,
                com.dwinovo.numen.core.task.chain.FoodChain::new);
        com.dwinovo.numen.task.BrainChains.register(40,
                com.dwinovo.numen.core.task.chain.MLGChain::new);
        com.dwinovo.numen.task.BrainChains.register(50,
                com.dwinovo.numen.core.task.chain.BreathChain::new);
    }

    /**
     * The reflex roster (constitution §6): enlist core's instincts — the five
     * survival chains and the pure policies. The switch persistence is bound by
     * the engine ({@code CommonClass.wireTaskMachine}). Runs on BOTH sides like
     * the rest of init.
     */
    private static void registerReflexes() {
        com.dwinovo.numen.core.task.reflex.CoreReflexes.registerAll();
    }

    private static void registerTools() {

        // Registration ORDER is preserved (backends with prompt-caching keyed off
        // the tool list cache stably across requests).
        ToolRegistry.register(new com.dwinovo.numen.core.tools.MoveToTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.MeleeAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.RangedAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LocateBiomeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CollectItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.FishTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.AutoMineTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.EquipItemTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.BuildTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.BlueprintListTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.BlueprintBuildTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InteractEntityTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.EatItemTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStopTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.DropItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TransferTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CloseGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetSelfStatusTool());   // SAMPLE: raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetOwnerStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LookupRecipeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CraftTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ScanNearbyEntitiesTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ScanBlocksTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LookAroundTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectBlockStorageTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetWorldInfoTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TodoWriteTool());   // raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LoadSkillTool());   // raw NumenTool
    }


    private static void registerTaskRunners() {
        CompanionTaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        CompanionTaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        CompanionTaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        CompanionTaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        CompanionTaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        CompanionTaskFactory.register(MeleeAttackTaskRecord.class, (p, r) -> new MeleeAttackCompanionTask(p, r));
        CompanionTaskFactory.register(RangedAttackTaskRecord.class, (p, r) -> new RangedAttackCompanionTask(p, r));
        CompanionTaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsTaskGoal(p, r));
        CompanionTaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        CompanionTaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        CompanionTaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        CompanionTaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        CompanionTaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureTaskGoal(p, r));
        CompanionTaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeTaskGoal(p, r));
    }
}
