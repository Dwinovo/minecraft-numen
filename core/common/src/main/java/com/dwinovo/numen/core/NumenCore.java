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
 * <p>Things plug into the engine here:
 * <ul>
 *   <li>command groups — registered through the plugin door, the same one third-party
 *       packs use; an action a group promotes to a quick tool enters the global
 *       {@link ToolRegistry} the moment its group registers, so the tool table's order
 *       is the order of the calls in {@link #registerTools()} (backends that cache the
 *       prompt key off it). {@code todowrite} is the one raw
 *       {@link com.dwinovo.numen.agent.tool.NumenTool};</li>
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
        com.dwinovo.numen.api.NumenPlugins.register(numen ->
                numen.registerGear(new com.dwinovo.numen.core.gear.VanillaArmor()));
        registerReflexes();
        enlistReflexRoster();
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s); survival chains enabled",
                ToolRegistry.size(), TaskFactory.size());
    }

    /** 把 core 的四条生存本能链插进引擎的竞价调度(链登记口)。 */
    private static void registerReflexes() {
        // 注册号小的先问 —— 与原版 addGoal(int priority, goal) 同一惯例:摔落缓冲 > 换气 > 自卫 > 脱困。
        // 本能之间的先后是固定的,不随世界状态变,所以是一个序号,不是一个要现算的出价。
        //
        // 正在坠落是最迫近的死法,所以摔落缓冲压过一切;卡住只是烦人,绝不该压过
        // 打架 —— 这条排序是有单测守着的(ReflexOrderTest)。
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
     * The reflex roster (constitution §6): enlist core's instincts — the four survival
     * chains — so their one-line self-descriptions reach the prompt. Runs on BOTH sides
     * like the rest of init.
     */
    private static void enlistReflexRoster() {
        com.dwinovo.numen.core.task.reflex.CoreReflexes.registerAll();
    }

    private static void registerTools() {
        // 登记的先后就是工具表的顺序(按工具表做提示词缓存的后端要它逐次一致)。每组提升出的快捷工具在它登记这一刻进表。
        // move 组提升出 goto
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.MoveCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.FightCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.locate.LocateCommands::install);
        // work 组提升出 mine
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.WorkCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.inventory.GearCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.BuildCommands::install);
        // throwaway 组连同它挂进身体状态的那一段
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.work.ThrowawayCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.interact.UseCommands::install);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.inventory.InvCommands::install);
        // 引擎的 task 命令组,和插件走同一扇门;它提升出 task_stop
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.task.TaskCommands::install);
        // status 组提升出 get_self_status、get_owner_status
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.perception.StatusCommands::install);
        // scan 组提升出 look_around、scan_blocks、scan_nearby_entities、inspect_block
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.core.tools.perception.ScanCommands::install);
        ToolRegistry.register(new com.dwinovo.numen.core.tools.agent.TodoWriteTool());   // raw NumenTool
        // skill 组提升出 load_skill
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
