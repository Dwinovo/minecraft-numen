package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskFactory;

import java.nio.file.Path;

/**
 * 森罗物语:厨房联动——让同伴用得上那一套炊具。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口
 * ——装没装森罗由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。它不在的话这个类
 * <b>一次都不会被加载</b>,而这一点是必须的:本联动直接编译依赖森罗的类
 * ({@code PotBlockEntity}、{@code QualityEvaluator} 等),类加载了就会去找那些类。
 *
 * <p>整件事全在服务端:锅的状态机、配方表、品质评估都住在那边,主人的客户端一样都碰不到。
 *
 * <p>第一版只接炒锅与汤锅。
 */
public final class NumenKaleidoscope {

    private NumenKaleidoscope() {}

    /** 由 {@code Builtin} 在确认森罗在场后调用。 */
    public static void install(Path skillsRoot) {
        NumenPlugins.register(numen -> {
            KaleidoscopeCommands.install(numen);

            // kaleidoscope cook 派下来的记录由谁来跑
            TaskFactory.register(CookRecord.class, (player, record) -> new CookTask(record));

            // 事件两侧都要登记(服务端的发出口靠它挡,主人客户端的队列靠它投递),
            // 所以直接调,不放进 onClient
            KcEvents.bind(numen);

            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
