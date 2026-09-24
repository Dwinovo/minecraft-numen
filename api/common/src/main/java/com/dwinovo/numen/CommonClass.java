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
     * 排程机器的引擎侧接线:本能开关的持久化、生命周期与任务调度的对接。
     * 链/任务执行器/工具是内容,由 numen-core 或第三方在各自 init 注册
     * ({@link com.dwinovo.numen.task.BrainChains} /
     * {@link com.dwinovo.numen.task.CompanionTaskFactory})。
     */
    private static void wireTaskMachine() {
        // 引擎自己也走同一条总线,和插件用的是同一套事件——没有"内部另有一条捷径"。
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.DEATH,
                com.dwinovo.numen.task.CompanionTickDispatcher::clearActiveTask);
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.REMOVE,
                com.dwinovo.numen.task.CompanionTickDispatcher::onCompanionRemoved);
        com.dwinovo.numen.entity.CompanionEvents.subscribe(
                com.dwinovo.numen.api.CompanionEvent.ABORT,
                com.dwinovo.numen.agent.tool.ServerToolTransport::abort);
        // 引擎自带姿态链的名册文书(主人开关 + 提示词总览一行)。
        com.dwinovo.numen.task.reflex.ReflexRegistry.register(
                new com.dwinovo.numen.task.chain.SpeakingLookChain());
    }

    /**
     * 引擎自己只登记一个工具:{@code numen},命令行的入口。命令组与动作是内容,由 {@code numen-core} 与插件经
     * {@code NumenApi.registerCommands} 登记;它们各自的工具也在各自的初始化里进 {@link ToolRegistry}。
     * {@code numen} 由引擎登记,是因为插件的命令只依赖引擎——谁登记了命令,谁都指望这个入口在。
     */
    public static void registerTools() {
        ToolRegistry.register(new com.dwinovo.numen.cli.CommandLineTool());
        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }
}
