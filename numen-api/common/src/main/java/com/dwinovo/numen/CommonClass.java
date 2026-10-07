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
        registerApi();
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
        // 引擎自带姿态链的名册文书:提示词总览里的一行。
        com.dwinovo.numen.task.reflex.ReflexRegistry.register(
                new com.dwinovo.numen.task.chain.SpeakingLookChain());
    }

    /**
     * 引擎自己登记她的四个工具:跑一段程序(名字随脚本语言)、装技能、记计划、记札记。作用于世界的是程序里的 API 函数,不加工具;只管
     * 大脑自己的事、不碰世界的才是工具。
     */
    public static void registerTools() {
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.ScriptTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.SkillTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.TodoTool());
        ToolRegistry.register(new com.dwinovo.numen.agent.tool.MemoryTool());
        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }

    /**
     * 引擎自己的几组 API 函数,和 core 与插件走同一扇门({@code NumenPlugins.register}):{@code api}(帮助)、{@code mc}(原版与模组的
     * 指令)、{@code task}(手上的活与表)、{@code module}(Lua 模块)。它们属于引擎:谁登记了函数,谁都指望帮助与这几样在。
     */
    public static void registerApi() {
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, numen -> {
            numen.api("api", "The API itself: the typed signatures of a group's or a module's functions, or one "
                    + "function in full.", com.dwinovo.numen.sdk.HelpApi.class);
            numen.api("mc", "Minecraft and mod commands, as a player types them in chat, with your own permission "
                    + "level.", com.dwinovo.numen.mc.McApi.class);
            numen.api("task", "The background task and your pending timers.", com.dwinovo.numen.task.TaskApi.class);
            numen.api(com.dwinovo.numen.script.ModuleApi.GROUP, "Modules — functions written in "
                    + com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.language() + " that programs use by name, kept "
                    + "as files on your owner's computer (numen/work.lua is numen.work): the built-in ones, your own, "
                    + "and how the programs that used them went.", com.dwinovo.numen.script.ModuleApi.class);
        });
    }
}
