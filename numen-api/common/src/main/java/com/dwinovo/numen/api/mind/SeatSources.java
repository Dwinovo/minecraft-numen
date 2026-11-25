package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.skill.SkillInfo;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.script.HerModules;
import com.dwinovo.numen.api.seat.Catalog;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.mcp.rpc.RpcException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 服务端拥有的、她用到时才取的两样东西在客户端这一头的读口:内置模块的原文({@code modules/builtin})与随发布的技能的正文、附属文件
 * ({@code skill/read})。名字与说明在目录里({@link Catalog}),原文随用随取,取过的留着直到目录变了。
 *
 * <p>取是在座位上等一个答复:不要在客户端的渲染线程之外还怕阻塞的地方调。
 */
final class SeatSources {

    /** 等服务端答复最多多久。 */
    private static final long TIMEOUT_SECONDS = 10;

    private final SeatClient seat;
    private final UUID companion;
    private final Supplier<Catalog> catalog;
    private final Map<String, String> builtinCodes = new ConcurrentHashMap<>();
    private final Map<String, SkillInfo> skillBodies = new ConcurrentHashMap<>();
    private final Map<String, List<String>> skillFiles = new ConcurrentHashMap<>();
    private volatile String forVersion = "";

    SeatSources(SeatClient seat, UUID companion, Supplier<Catalog> catalog) {
        this.seat = seat;
        this.companion = companion;
        this.catalog = catalog;
    }

    /** 目录变了:取过的原文作废。 */
    private void fresh() {
        String version = catalog.get().version();
        if (!version.equals(forVersion)) {
            synchronized (this) {
                if (!version.equals(forVersion)) {
                    builtinCodes.clear();
                    skillBodies.clear();
                    skillFiles.clear();
                    forVersion = version;
                }
            }
        }
    }

    /** 内置模块。 */
    final HerModules.Builtins builtins = new HerModules.Builtins() {
        @Override
        public Set<String> names() {
            return catalog.get().builtinModules();
        }

        @Override
        public String code(String name) {
            fresh();
            if (!names().contains(name)) {
                return null;
            }
            return builtinCodes.computeIfAbsent(name, n -> await(seat.builtinModule(companion, n)));
        }
    };

    /** 随发布的技能。 */
    final SkillRegistry.Bundled skills = new SkillRegistry.Bundled() {
        @Override
        public List<SkillInfo> list() {
            return catalog.get().skills();
        }

        @Override
        public SkillInfo get(String name) {
            fresh();
            SkillInfo known = list().stream().filter(s -> s.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown skill: " + name));
            return skillBodies.computeIfAbsent(name, n -> {
                SeatClient.SkillText text = await(seat.skill(companion, n));
                skillFiles.put(n, text.files());
                return new SkillInfo(known.name(), text.description().isEmpty() ? null : text.description(),
                        text.content(), null);
            });
        }

        @Override
        public String file(String name, String relPath) {
            try {
                return await(seat.skillFile(companion, name, relPath));
            } catch (IllegalStateException refused) {
                throw new IllegalArgumentException(refused.getMessage(), refused);
            }
        }

        @Override
        public List<String> files(String name) {
            get(name);
            return skillFiles.getOrDefault(name, List.of());
        }
    };

    /**
     * 写得读不读得通、撞没撞第 ① 层。
     *
     * @return 没事是 {@code null},有事是那句话
     */
    String checkModule(String name, String code) {
        return await(seat.checkModule(companion, name, code));
    }

    private static <T> T await(CompletableFuture<T> answer) {
        try {
            return answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RpcException refused) {
                throw new IllegalStateException(refused.error().message(), refused);
            }
            throw new IllegalStateException("the server did not answer: " + failed.getCause(), failed);
        } catch (TimeoutException late) {
            throw new IllegalStateException("the server did not answer in " + TIMEOUT_SECONDS + " seconds", late);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server", interrupted);
        }
    }
}
