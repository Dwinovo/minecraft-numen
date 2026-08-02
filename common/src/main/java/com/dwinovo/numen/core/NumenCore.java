package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.CompanionTaskFactory;
import com.dwinovo.numen.core.task.build.BuildCompanionTask;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.collect.CollectItemsCompanionTask;
import com.dwinovo.numen.core.task.collect.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.act.DropCompanionTask;
import com.dwinovo.numen.core.task.act.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.act.EatCompanionTask;
import com.dwinovo.numen.core.task.act.EatItemTaskRecord;
import com.dwinovo.numen.core.task.act.EquipCompanionTask;
import com.dwinovo.numen.core.task.act.EquipTaskRecord;
import com.dwinovo.numen.core.task.fish.FishCompanionTask;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.task.combat.MeleeAttackCompanionTask;
import com.dwinovo.numen.core.task.combat.MeleeAttackTaskRecord;
import com.dwinovo.numen.core.task.combat.RangedAttackCompanionTask;
import com.dwinovo.numen.core.task.combat.RangedAttackTaskRecord;
import com.dwinovo.numen.core.task.act.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.act.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.act.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.act.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.act.LocateBiomeCompanionTask;
import com.dwinovo.numen.core.task.act.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.act.LocateStructureCompanionTask;
import com.dwinovo.numen.core.task.act.LocateStructureTaskRecord;
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
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.MoveToTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.MeleeAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.RangedAttackTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.LocateBiomeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.CollectItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.FishTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.AutoMineTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.EquipItemTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.BuildTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.job.BlueprintTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.BlueprintReadTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.InteractEntityTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.EatItemTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.task.TaskStopTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.DropItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.TakeItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.TransferTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.CloseGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.GetSelfStatusTool());   // SAMPLE: raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.GetOwnerStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.LookupRecipeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.act.CraftTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.ScanNearbyEntitiesTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.ScanBlocksTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.LookAroundTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.InspectBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.InspectBlockStorageTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.GetWorldInfoTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.TodoWriteTool());   // raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.query.LoadSkillTool());   // raw NumenTool
    }


    private static void registerTaskRunners() {
        CompanionTaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        CompanionTaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        CompanionTaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        CompanionTaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        CompanionTaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        CompanionTaskFactory.register(MeleeAttackTaskRecord.class, (p, r) -> new MeleeAttackCompanionTask(p, r));
        CompanionTaskFactory.register(RangedAttackTaskRecord.class, (p, r) -> new RangedAttackCompanionTask(p, r));
        CompanionTaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsCompanionTask(p, r));
        CompanionTaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        CompanionTaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        CompanionTaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        CompanionTaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        CompanionTaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureCompanionTask(p, r));
        CompanionTaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeCompanionTask(p, r));
    }
}
