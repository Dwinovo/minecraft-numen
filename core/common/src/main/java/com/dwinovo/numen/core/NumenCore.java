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
 *       caching), or a command group registered through the plugin door, whose
 *       promoted actions enter the registry at that same point;</li>
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
        // 原版四件甲是第一处穿戴来源,和模组的饰品栏走同一扇门;内嵌联动在这之后才开闸,所以原版排在最前
        com.dwinovo.numen.api.NumenPlugins.register(numen ->
                numen.registerGear(new com.dwinovo.numen.core.gear.VanillaArmor()));
        registerReflexes();
        enlistReflexRoster();
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
        // move 组提升出的 goto 在这里进表
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.MoveCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.FightCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.locate.LocateCommands::install);
        // work 组提升出的 mine 在这里进表
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.WorkCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.inventory.GearCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.BuildCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.interact.UseCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.inventory.InvCommands::install);
        // 引擎的 task 命令组,和插件走同一扇门;它提升出的 task_stop 就在这里进表,工具表的顺序不变。
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.task.TaskCommands::install);
        // status 组提升出的 get_self_status / get_owner_status 在这里进表
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.perception.StatusCommands::install);
        // scan 组提升出的 look_around / scan_blocks / scan_nearby_entities / inspect_block 在这里进表
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.perception.ScanCommands::install);
        ToolRegistry.register(new com.dwinovo.numen.core.tools.agent.TodoWriteTool());   // raw NumenTool
        // skill 组提升出的 load_skill 就在这里进表,工具表里的位置不变
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.agent.SkillCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.agent.MemoryCommands::install);
    }


    private static void registerTaskRunners() {
        TaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.move.FollowTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.move.FollowCompanionTask(p, r));
        TaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        TaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.inventory.UnequipTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.inventory.UnequipCompanionTask(p, r));
        TaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        TaskFactory.register(com.dwinovo.numen.core.task.inventory.TransferTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.inventory.TransferCompanionTask(p, r));
        TaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        TaskFactory.register(AttackTaskRecord.class, (p, r) -> new AttackCompanionTask(p, r));
        TaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsCompanionTask(p, r));
        TaskFactory.register(FishTaskRecord.class, (p, r) -> new FishCompanionTask(p, r));
        TaskFactory.register(BuildTaskRecord.class, (p, r) -> new BuildCompanionTask(p, r));
        TaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        TaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        TaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureCompanionTask(p, r));
        TaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeCompanionTask(p, r));
    }
}
