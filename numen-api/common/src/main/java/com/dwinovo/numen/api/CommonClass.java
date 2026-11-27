package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.platform.Services;

/**
 * Loader-agnostic mod init. Called once from each platform's mod entry point
 * after the loader has finished registry-registration (entity types, payloads,
 * etc.). Everything that depends on the {@code Services} surface or that is
 * pure data-side initialisation lives here.
 */
public class CommonClass {

    public static void init() {
        Constants.LOG.info("[numen] common init on {} ({})",
                Services.platform().getPlatformName(), Services.platform().getEnvironmentName());

        // 老版本落盘格式的搬运先行——必须在任何消费方读盘之前。
        java.nio.file.Path numenDir = NumenPaths.config();
        com.dwinovo.numen.api.config.ConfigMigrations.run(numenDir);

        registerTools();
        registerApi();
        registerBundledContent(Services.platform().modResource(Constants.MOD_ID, "numen"));
        wireTaskMachine();
        wireRegistrationChanges();
        com.dwinovo.numen.api.seat.ServerSeats.register();
    }

    /**
     * 排程机器的 Numen API 侧接线:生命周期与任务调度的对接,以及 Numen API 自带的姿态链进本能名册。
     * 链/任务执行器/工具是内容,由 Numen 或第三方在各自 init 注册
     * ({@link com.dwinovo.numen.api.task.BrainChains} /
     * {@link com.dwinovo.numen.api.task.TaskFactory})。
     */
    private static void wireTaskMachine() {
        // Numen API 自己也走同一扇门(宿主的作用域),和插件用的是同一套事件与指令登记——没有"内部另有一条捷径"。
        com.dwinovo.numen.api.NumenPlugins.registerHost(numen -> {
            numen.on(com.dwinovo.numen.api.CompanionEvent.DEATH,
                    com.dwinovo.numen.api.task.CompanionTickDispatcher::clearActiveTask);
            numen.on(com.dwinovo.numen.api.CompanionEvent.SPAWN,
                    com.dwinovo.numen.api.task.CompanionTickDispatcher::onCompanionSpawned);
            numen.on(com.dwinovo.numen.api.CompanionEvent.REMOVE,
                    com.dwinovo.numen.api.task.CompanionTickDispatcher::onCompanionRemoved);
        });
        // Numen API 自带姿态链的名册文书:提示词总览里的一行。
        com.dwinovo.numen.api.task.reflex.ReflexRegistry.register(
                new com.dwinovo.numen.api.task.chain.SpeakingLookChain());
        com.dwinovo.numen.api.task.reflex.ReflexRegistry.register(
                new com.dwinovo.numen.api.task.chain.FloatChain());
    }

    /**
     * 登记变了(花名册装上或撤回了一个插件)之后:已经发给玩家的 {@code /numen} 指令树是服务器发的一份拷贝,在服务器线程上重发。
     * 系统提示、帮助与脚本目录每次都从当前的表现算,不用跟着动。
     */
    private static void wireRegistrationChanges() {
        com.dwinovo.numen.api.plugin.Registrations.onChanged(() -> {
            net.minecraft.server.MinecraftServer server = com.dwinovo.numen.api.platform.ServerLifecycle.running();
            if (server != null) {
                server.execute(() -> com.dwinovo.numen.api.entity.NumenCommands.resend(server));
            }
        });
    }

    /**
     * Numen API 自己登记她的五个工具:跑一段程序(名字随脚本语言)、装技能、记计划、记札记、问主人。作用于世界的是程序里的 API 函数,不加工具;只管
     * 大脑自己的事、不碰世界的才是工具。
     */
    public static void registerTools() {
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.ScriptTool());
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.SkillTool());
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.TodoTool());
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.MemoryTool());
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.ApiSearchTool());
        ToolRegistry.register(new com.dwinovo.numen.api.agent.tool.AskUserQuestionTool());
        Constants.LOG.info("[numen] registered {} tool(s)", ToolRegistry.size());
    }

    /**
     * 引擎自带的内容(它 jar 里的 {@code numen/} 目录:{@code numen.geometry} 模块,每个位置都有的加减比较与挪动),和插件的自带内容走同一扇门
     * ({@code NumenApi.bundleContent}),只是登记者是宿主。目录怎么从 jar 里取出来是加载器的事({@code IPlatformHelper#modResource}),
     * 插件用的是同一个。
     *
     * @param root 引擎 jar 里的 {@code numen/} 目录
     */
    public static void registerBundledContent(java.nio.file.Path root) {
        if (root == null) {
            throw new IllegalStateException("[numen] no bundled numen/ dir found in the engine jar");
        }
        com.dwinovo.numen.api.NumenPlugins.registerHost(numen -> numen.bundleContent(root));
    }

    /**
     * Numen API 自己的几组 API 函数,走宿主自己的登记({@code NumenPlugins.registerHost}),和 Numen 与插件登记进同一张表:{@code permission}、{@code task}(手上的活与表)。它们属于 Numen API:谁登记了函数,谁都指望这几样在。
     */
    public static void registerApi() {
        com.dwinovo.numen.api.NumenPlugins.registerHost(numen -> {
            numen.api("permission", "What your owner lets you do to the world: read it, tighten it yourself, ask "
                    + "your owner to loosen it.", com.dwinovo.numen.api.permission.PermissionApi.class);
            numen.api("task", "The background task and your pending timers.", com.dwinovo.numen.api.task.TaskApi.class);
        });
    }
}
