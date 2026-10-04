package com.dwinovo.numen;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;

/**
 * Loader-agnostic mod init. Called once from each platform's mod entry point
 * after the loader has finished registry-registration (entity types, payloads,
 * etc.). Everything that depends on the {@code Services} surface or that is
 * pure data-side initialisation lives here.
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("[numen] common init on {} ({})",
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        // 老版本落盘格式的搬运先行——必须在任何消费方读盘之前。
        java.nio.file.Path numenDir = NumenPaths.config();
        com.dwinovo.numen.config.ConfigMigrations.run(numenDir);

        registerTools();
        wireTaskMachine();
    }

    /**
     * 排程机器的引擎侧接线:生命周期与任务调度的对接,以及引擎自带的姿态链进本能名册。
     * 链/任务执行器/工具是内容,由 numen-core 或第三方在各自 init 注册
     * ({@link com.dwinovo.numen.task.BrainChains} /
     * {@link com.dwinovo.numen.task.TaskFactory})。
     */
    private static void wireTaskMachine() {
        // 引擎自己也走同一条总线,和插件用的是同一套事件——没有"内部另有一条捷径"。
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.DEATH,
                com.dwinovo.numen.task.CompanionTickDispatcher::clearActiveTask);
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.SPAWN,
                com.dwinovo.numen.task.CompanionTickDispatcher::onCompanionSpawned);
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.REMOVE,
                com.dwinovo.numen.task.CompanionTickDispatcher::onCompanionRemoved);
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.ABORT,
                com.dwinovo.numen.agent.tool.ServerToolTransport::abort);
        // 引擎自带姿态链的名册文书:提示词总览里的一行。
        com.dwinovo.numen.task.reflex.ReflexRegistry.register(
                new com.dwinovo.numen.task.chain.SpeakingLookChain());
    }

    /**
     * 引擎自己登记她的三个工具——跑一段脚本(名字随脚本语言)、装技能、记计划——和两组属于 API 本身的动作:{@code api}(帮助)、
     * {@code mc}(原版与模组的指令)。作用于世界的是脚本里的函数:别的动作组是内容,由 {@code numen-core} 与插件经
     * {@code NumenApi.registerCommands} 登记,不加工具;只管大脑自己的事、不碰世界的(技能、计划)才是工具。这些由引擎登记,是因为
     * 插件的动作与技能只依赖引擎——谁登记了动作,谁都指望这个入口与帮助在。
     */
    public static void registerTools() {
        ToolRegistry.register(new com.dwinovo.numen.cli.ScriptTool());
        ToolRegistry.register(new com.dwinovo.numen.cli.SkillTool());
        ToolRegistry.register(new com.dwinovo.numen.cli.TodoTool());
        ToolRegistry.register(new com.dwinovo.numen.cli.MemoryTool());
        com.dwinovo.numen.cli.HelpCommands.install();
        com.dwinovo.numen.cli.McCommands.install();
        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }
}
