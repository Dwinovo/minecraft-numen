package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 服务端拥有的、她用到时才取的两样东西在客户端这一头的读口:内置模块的原文({@code modules/builtin})与随发布的技能的正文、附属文件
 * ({@code skill/read})。名字与说明在目录里({@link Catalog}),原文向服务端要。<b>没有一处在座位的答复上等着</b>:技能的读口交回
 * 答复到了才有的 {@link CompletableFuture};内置模块的原文在座位坐上、目录变了的时候一次取齐({@link #refresh}),读口只读已经取到的,
 * 没取齐时说一声还没到。
 */
final class SeatSources {

    private final SeatClient seat;
    private final UUID companion;
    private final Supplier<Catalog> catalog;
    private final Map<String, String> builtinCodes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<SkillInfo>> skillBodies = new ConcurrentHashMap<>();
    private final Map<String, List<String>> skillFiles = new ConcurrentHashMap<>();
    /** 取齐了内置模块的是哪一版目录。 */
    private volatile String loadedFor = "";

    SeatSources(SeatClient seat, UUID companion, Supplier<Catalog> catalog) {
        this.seat = seat;
        this.companion = companion;
        this.catalog = catalog;
    }

    /** 目录到了或变了:旧的原文作废,内置模块的原文取齐。 */
    void refresh() {
        Catalog current = catalog.get();
        builtinCodes.clear();
        skillBodies.clear();
        skillFiles.clear();
        Set<String> names = current.builtinModules();
        if (names.isEmpty()) {
            loadedFor = current.version();
            return;
        }
        CompletableFuture.allOf(names.stream().map(name -> seat.builtinModule(companion, name).thenAccept(code -> {
            if (code != null) {
                builtinCodes.put(name, code);
            }
        })).toArray(CompletableFuture[]::new)).whenComplete((done, failed) -> {
            if (failed != null) {
                Constants.LOG.warn("[numen-mind#{}] could not fetch the built-in modules: {}", companion, failed.toString());
            } else {
                loadedFor = current.version();
            }
        });
    }

    /** 内置模块。 */
    final HerModules.Builtins builtins = new HerModules.Builtins() {
        @Override
        public Set<String> names() {
            return catalog.get().builtinModules();
        }

        @Override
        public boolean loaded() {
            return loadedFor.equals(catalog.get().version());
        }

        @Override
        public String code(String name) {
            return builtinCodes.get(name);
        }
    };

    /** 随发布的技能。 */
    final SkillRegistry.Bundled skills = new SkillRegistry.Bundled() {
        @Override
        public List<SkillInfo> list() {
            return catalog.get().skills();
        }

        @Override
        public CompletableFuture<SkillInfo> get(String name) {
            SkillInfo known = list().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
            if (known == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown skill: " + name));
            }
            return skillBodies.computeIfAbsent(name, n -> seat.skill(companion, n).thenApply(text -> {
                skillFiles.put(n, text.files());
                return new SkillInfo(known.name(), text.description().isEmpty() ? null : text.description(),
                        text.content(), null);
            }).exceptionallyCompose(SeatSources::refused));
        }

        @Override
        public CompletableFuture<String> file(String name, String relPath) {
            return seat.skillFile(companion, name, relPath).exceptionallyCompose(SeatSources::refused);
        }

        @Override
        public CompletableFuture<List<String>> files(String name) {
            return get(name).thenApply(skill -> skillFiles.getOrDefault(name, List.of()));
        }
    };

    /** 服务端回的拒绝(没有这份技能、路径越界……)是 {@link IllegalArgumentException},别的失败原样。 */
    private static <T> CompletableFuture<T> refused(Throwable failed) {
        Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
        return CompletableFuture.failedFuture(cause instanceof RpcException refusal
                ? new IllegalArgumentException(refusal.error().message(), refusal) : cause);
    }
}
