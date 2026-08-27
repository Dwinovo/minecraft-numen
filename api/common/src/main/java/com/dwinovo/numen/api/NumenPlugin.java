package com.dwinovo.numen.api;

/**
 * 一个 Numen 插件:拿到 {@link NumenApi},把自己要挂的东西挂上去。
 *
 * <pre>{@code
 * public MyMod() {
 *     NumenPlugins.register(numen -> {
 *         numen.registerTool(new MyTool());
 *         numen.bundleSkills(mySkillsRoot());
 *         numen.on(CompanionEvent.SPAWN, body -> ...);
 *     });
 * }
 * }</pre>
 *
 * <p>在你自己模组的构造期调用 {@link NumenPlugins#register} 即可,不必关心 Numen
 * 那边初始化到哪一步了——晚到的注册会在引擎就绪时补上。
 */
@FunctionalInterface
public interface NumenPlugin {

    /**
     * @param numen 扩展 Numen 的那扇门
     */
    void setup(NumenApi numen);
}
