package com.dwinovo.numen.pathing;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * 每次跑单测先引导一次原版环境({@link Vanilla}),再开始任何测试类。26.1 起物品的数据组件在数据包加载期才绑定,测试类各自只调
 * {@code Bootstrap.bootStrap()} 的话构造 {@code ItemStack} 就抛 "Components not bound yet";引导放在会话开头这一处,寻路与 Numen
 * 的单测(它们的类路径上都有本源码集)都从同一个已绑好的环境开始。经 {@code META-INF/services} 登记给 JUnit。
 */
public final class VanillaSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Vanilla.boot();
    }
}
