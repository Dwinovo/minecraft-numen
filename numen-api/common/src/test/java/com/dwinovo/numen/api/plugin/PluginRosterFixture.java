package com.dwinovo.numen.api.plugin;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 单测共用的一步:花名册的停用名单放进这次测试专用的空目录,不去找加载器的配置目录(单测里没有)。一个进程装一次。 */
public final class PluginRosterFixture {

    private static boolean installed;

    /** 什么都不做的门:只验花名册的账与撤回,登记本身不是那里的事。 */
    public static final java.util.function.Function<com.dwinovo.numen.api.agent.scope.Scope, com.dwinovo.numen.api.NumenApi>
            INERT_DOORS = scope -> (com.dwinovo.numen.api.NumenApi) java.lang.reflect.Proxy.newProxyInstance(
                    PluginRosterFixture.class.getClassLoader(), new Class<?>[] {com.dwinovo.numen.api.NumenApi.class},
                    (proxy, method, args) -> null);

    private PluginRosterFixture() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Path dir = Files.createTempDirectory("numen-test-plugins-");
            dir.toFile().deleteOnExit();
            NumenPlugins.useRoster(new PluginRoster(() -> new PluginSwitches(new JsonDoc(dir.resolve(PluginSwitches.FILE),
                    JsonDoc.Format.of(1), Io.direct(e -> {
                        throw e;
                    }))), id -> PluginRoster.Meta.NONE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        installed = true;
    }
}
