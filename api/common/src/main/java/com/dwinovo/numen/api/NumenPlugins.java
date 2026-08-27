package com.dwinovo.numen.api;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionEvents;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 插件的登记处:{@code NumenPlugins.register(numen -> …)}。
 *
 * <h2>时机不必你操心</h2>
 * 插件在自己模组的构造期登记就行。引擎内部该就绪的东西各有各的时机(工具注册表在
 * 类加载时就有,技能与头像要等客户端起来),这里替你等——先登记的先跑,能跑的立刻跑,
 * 跑不了的等它就绪。
 *
 * <h2>专用服务器上会安静地少几样</h2>
 * 技能和头像只活在玩家的客户端上。它们由客户端启动时{@linkplain #bindClient 接上来};
 * 专用服务器上没人接,于是 {@code bundleSkills} / {@code registerPortrait} 就是空操作。
 * <b>"有没有接上"本身就是判据</b>,不必再去问一遍"我现在是不是客户端"——那种问法
 * 每个加载器一个写法,写在插件里就是每个插件一个写法。
 */
public final class NumenPlugins {

    /** 客户端接上来的那几样;专用服务器上一直是 null,于是相关调用自然成空操作。 */
    private static volatile Consumer<Path> skills;
    private static volatile BiFunction<UUID, String, Delivery> enqueue;
    /** 客户端接上来了没有。它同时就是"我现在是不是客户端"的答案。 */
    private static volatile boolean clientReady;

    private static final NumenApi API = new Impl();

    private NumenPlugins() {}

    /** 登记一个插件。可在任何时候调用,通常在你模组的构造期。 */
    public static void register(NumenPlugin plugin) {
        if (plugin == null) return;
        try {
            plugin.setup(API);
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen] 插件登记失败,它挂的东西可能只生效了一半", e);
        }
    }

    /**
     * 客户端起来时把只在客户端存在的能力接上来。<b>引擎内部调用</b>,插件不该碰。
     */
    public static void bindClient(Consumer<Path> skillSink,
                                  BiFunction<UUID, String, Delivery> enqueueFn) {
        skills = skillSink;
        enqueue = enqueueFn;
        clientReady = true;
    }

    private static final class Impl implements NumenApi {

        @Override
        public <T> void on(CompanionEvent<T> event, Consumer<T> handler) {
            CompanionEvents.subscribe(event, handler);
        }

        @Override
        public void registerTool(NumenTool tool) {
            ToolRegistry.register(tool);
        }

        @Override
        public void bundleSkills(Path skillsRoot) {
            Consumer<Path> sink = skills;
            if (sink != null && skillsRoot != null) sink.accept(skillsRoot);
        }

        @Override
        public void onClient(Runnable clientOnly) {
            if (!clientReady || clientOnly == null) return;
            try {
                clientOnly.run();
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] 插件的客户端初始化出错", e);
            }
        }

        @Override
        public Delivery enqueue(UUID companion, String message) {
            BiFunction<UUID, String, Delivery> fn = enqueue;
            return fn == null ? Delivery.REJECTED : fn.apply(companion, message);
        }
    }
}
