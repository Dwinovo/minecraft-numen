package com.dwinovo.numen.plugins.tlm.bench;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import com.dwinovo.numen.bench.Bench;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;

import java.util.Collection;
import java.util.List;

/**
 * 车万女仆联动的评测场景(套件 tlm)。只在挂着车万女仆的那一次评测里跑({@code :plugins:tlm:runBench},见本模块的 build.gradle);
 * 插件给自己的联动加场景就照这个写:评测源码集里一个套件类、一个生成器,{@link Bench#suite} 登记一组,
 * 资源 {@code META-INF/numen/gametest/套件名} 写套件类的全名。
 */
public final class TlmBench implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TlmBench.class);
    }

    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
        return Bench.suite("tlm", "Touhou Little Maid: taming, keeping and reviving maids.",
                suite -> suite.add(MaidFarmhand::new)
                        .add(ReviveMaid::new));
    }
}
