package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.NumenPlugins;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评测这个模组:只在评测的运行配置里加载,平时的游戏、GameTest 与发行 jar 里都没有它。
 *
 * <p>主人客户端起来时把技能接进技能表({@code NumenPlugins.bindClient}),提示词里的技能清单由此而来。评测的进程是
 * 服务端,没有客户端来接,所以在这里接同一扇门——只接技能,插件的客户端块照旧不跑——再扫一遍技能表。
 * 玩家自己的技能目录不扫:评测比的是自带的那一份。
 *
 * <p>评测的用例在 {@code RegisterGameTestsEvent} 里登记:扫出本模组所有 {@link BenchSuite} 方法,每个选中的组一条
 * {@link BenchTestInstance}。场地由代码现搭({@link Arena}),用例的结构模板只是 GameTest 要的一个落脚点,借用运行配置经
 * {@code numen.gametest.structures} 指过来的模板目录里的一块地板。
 */
@Mod(Bench.NAMESPACE)
public final class NumenBench {

    /** 用例的结构模板:核心模组的一块地板。 */
    private static final Identifier ANCHOR = Identifier.fromNamespaceAndPath("numen", "floor16");

    /** 组名 → 会话。{@link BenchTestInstance} 解码时按组名取回同一个会话。 */
    private static final Map<String, Session> SESSIONS = new HashMap<>();

    public NumenBench(IEventBus modBus) {
        NumenPlugins.bindSkills(root -> SkillRegistry.instance().declareBundled(root));
        SkillRegistry.instance().scan(null);

        DeferredRegister<MapCodec<? extends GameTestInstance>> types =
                DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, Bench.NAMESPACE);
        types.register("suite", () -> BenchTestInstance.CODEC);
        types.register(modBus);
        modBus.addListener(NumenBench::onRegisterGameTests);
    }

    static Session session(String suite) {
        Session found = SESSIONS.get(suite);
        if (found == null) {
            throw new IllegalStateException("unknown bench suite: " + suite);
        }
        return found;
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        String structures = System.getProperty("numen.gametest.structures");
        if (structures == null) {
            throw new IllegalStateException("评测要 GameTest 的模板目录:运行配置没有给 -Dnumen.gametest.structures");
        }
        Path dir = Path.of(structures);
        StructureUtils.testStructuresSourceDir = dir;
        StructureUtils.testStructuresTargetDir = dir;

        var scan = ModList.get().getModFileById(Bench.NAMESPACE).getFile().getScanResult();
        var environment = event.registerEnvironment(Identifier.fromNamespaceAndPath(Bench.NAMESPACE, "default"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        scan.getAnnotations().stream()
                .filter(a -> a.annotationType().getClassName().equals(BenchSuite.class.getName()))
                .forEach(a -> {
                    Optional<Bench.Run> run = invoke(a.clazz().getClassName(), a.memberName());
                    run.ifPresent(r -> {
                        SESSIONS.put(r.name(), r.session());
                        var data = new TestData<>(environment, ANCHOR, r.session().maxTicks(), 0, true, Rotation.NONE);
                        event.registerTest(Identifier.fromNamespaceAndPath(Bench.NAMESPACE, r.name()),
                                new BenchTestInstance(r.name(), data));
                    });
                });
    }

    @SuppressWarnings("unchecked")
    private static Optional<Bench.Run> invoke(String owner, String member) {
        String methodName = member.substring(0, member.indexOf('('));
        try {
            Class<?> holder = Class.forName(owner, true, NumenBench.class.getClassLoader());
            for (Method m : holder.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                    return (Optional<Bench.Run>) m.invoke(null);
                }
            }
            throw new IllegalStateException("@BenchSuite method not found: " + owner + "." + member);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot call @BenchSuite method " + owner + "." + member, e);
        }
    }
}
